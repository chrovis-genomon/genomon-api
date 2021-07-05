(ns genomon-api.executor.genomon-pipeline-cloud
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.edn :as edn]
            [clojure.core.async :as async]
            [integrant.core :as ig]
            [duct.logger :as log]
            [duct.core.env :as env]
            [camel-snake-kebab.core :as csk]
            [ring.util.http-response :as hr]
            [genomon-api.executor :as exec]
            [genomon-api.docker :as d]
            [genomon-api.docker.async]
            [genomon-api.storage :as storage]
            [genomon-api.util.ini :as ini]
            [genomon-api.util.csv :as csv]))

(defn- map-leaves [f m]
  (walk/postwalk
   (fn [x]
     (cond (map-entry? x)
           (if (and (not (coll? (val x))) (not (nil? (val x))))
             (update x 1 f)
             x)

           (vector? x) (mapv f x)
           :else x))
   m))

(defn- fill-lazy-env [m]
  (into {} (map (fn [[k v]]
                  (if (and (vector? v) (= (first v) :lazy-env))
                    [k (apply env/env (subvec v 1))]
                    [k v]))) m))

(def ^:const ^:private dna-result-paths
  {:tumor-bam "bam/tumor/tumor.markdup.bam",
   :normal-bam "bam/normal/normal.markdup.bam",
   :mutations "mutation/tumor/tumor.genomon_mutation.result.filt.txt",
   :svs "sv/tumor/tumor.genomonSV.result.filt.txt"})

(def ^:const ^:private rna-result-paths
  {:bam "star/rna/rna.Aligned.sortedByCoord.out.bam",
   :fusions "fusion/rna/rna.genomonFusion.result.filt.txt",
   :expressions "expression/rna/rna.genomonExpression.result.txt",
   :intron-retentions "intron_retention/rna/rna.genomonIR.result.txt"
   :svs "sv/rna/rna.genomonSV.result.filt.txt"})

(defn- ->s3 [output-bucket id s]
  (str output-bucket "/" id "/" s))

(defn- parse-log [{{:keys [body]} :log :as event}]
  (condp re-matches body
    #".*ecsub submit .*--tasks \S+?([^/\s]+)-tasks.*\n"
    :>> (fn [[_ x]]
          {:task (keyword x),
           :status :submitted})
    #".*aws ecs deregister-task-definition .*--task-definition [^\s/]+/(\S+)-tasks-.*\n"
    :>> (fn [[_ x]]
          {:task (keyword x),
           :status :terminated})
    #".*\[([^\]]+)-tasks[^\]]+(?<!\d\d\d)\] task file is empty.*\n"
    :>> (fn [[_ x]]
          {:task (keyword x),
           :status :skipped})
    ;; TODO: error?
    nil))

(defn- pipe-events
  [state
   {:keys [logger storage]}
   {:keys [id pipeline-type results] :as run}
   {:keys [status] :as event}]
  (let [run-event (merge run event)]
    (case status
      :succeeded (try
                   (when-not (seq results)
                     (throw (ex-info "Results not found" {})))
                   (map-leaves (partial storage/stat storage) results)
                   (when-not (every? #{:terminated :skipped} (vals @state))
                     (log/warn logger ::incomplete-sub-tasks @state))
                   (assoc run-event :sub-tasks @state)
                   (catch Throwable e
                     (assoc run-event :status :failed :error e)))
      (:started :failed) run-event
      :running (do (log/info logger ::log run-event)
                   (when-let [task (parse-log run-event)]
                     (->> task
                          ((juxt :task :status))
                          (apply swap! state assoc)
                          (assoc run-event :sub-tasks)))))))

(defn- handle-error [{:keys [logger]} run e]
  (log/error logger ::local-channel-error {:run run :error e}))

(defn- run-pipeline!
  [{:keys [storage output-bucket docker image tag env ch logger] :as m}
   pipeline-type config id samples]
  (let [s3-samples (map-leaves (partial storage/stat storage) samples)
        samples-str ((case pipeline-type
                       :dna csv/gen-dna-input
                       :rna csv/gen-rna-input) samples)
        config-str (ini/gen-ini config)
        s3-output (str output-bucket "/" id)
        run {:run-id id,
             :pipeline-type pipeline-type,
             :output-dir s3-output,
             :results (map-leaves
                       (partial ->s3 output-bucket id)
                       (case pipeline-type
                         :dna (cond-> dna-result-paths
                                (not (:normal samples))
                                (assoc :normal-bam nil))
                         :rna rna-result-paths)),
             :image-id (:id image),
             :samples samples,
             :config config}
        local-ch (async/chan 100 (keep (partial pipe-events (atom {}) m run))
                             (partial handle-error m run))
        _ (async/pipe local-ch ch false) ;; ch
        m (d/run-async
           docker (:image image) local-ch
           {:tag tag,
            :env (fill-lazy-env env),
            :container-name id,
            :labels {:requester "genomon-api",
                     :pipeline-type (name pipeline-type),
                     :run (pr-str run)}, ;; Save info in a label
            :working-dir "/root",
            :command [(name pipeline-type) "sample.csv" s3-output "param_ecsub.cfg"],
            :cp {"sample.csv" samples-str, "param_ecsub.cfg" config-str},
            :remote-path "/root"})]
    (merge run m)))

(defn- listen-running-pipelines!
  [{:keys [logger ch docker] :as m}]
  (log/debug logger ::search-containers {})
  (doseq [{:keys [id state labels]
           {{:strs [ExitCode FinishedAt]} :state} :inspection
           :as c} (d/list-containers
                   docker
                   {:show-all? true, :label-filter {"requester" "genomon-api"}})
          :let [run (edn/read-string (get labels "run")) ;; Get info from a label
                local-ch (async/chan
                          100
                          (keep (partial pipe-events (atom {}) logger run))
                          (partial handle-error m run))
                _ (async/pipe local-ch ch false)
                info {:container-id id, :run run, :container c}]]
    (case (keyword state)
      :created
      (do
        (log/info logger ::start-created-container info)
        (d/start-container docker id)
        (d/wait-async docker id local-ch))

      :running
      (do
        (log/info logger ::wait-running-container info)
        (d/wait-async docker id local-ch))

      :exited
      (do
        (log/info logger ::remove-finished-container info)
        (if (zero? ExitCode)
          (async/>!! local-ch {:container-id id, :status :succeeded})
          (async/>!! local-ch {:container-id id, :status :failed, :code ExitCode}))
        (d/remove-container docker id {}))

      (:paused :restarting :removing :dead) ;; TODO: consider other states
      (log/warn logger ::unknown-status info))))

(defn- interrupt-pipeline!
  [docker pipeline-type run-id]
  (let [{:keys [id state labels] :as c} (->> {:name-filter [(str run-id)]}
                                             (d/list-containers docker)
                                             first)]
    (when-not (= (get labels "pipeline-type") (name pipeline-type))
      (throw (ex-info "Invalid pipeline type" {:pipeline-type pipeline-type,
                                               :run-id run-id,
                                               :container c})))
    (cond
      (nil? id) (hr/not-found!)
      (= state "exited") (hr/throw! (hr/no-content))
      :else (d/kill-container docker id {}))))

(defrecord
 GenomonPipelineCloudExecutor
 [image output-bucket env
  docker storage ch logger]
  exec/IExecutor
  (run-dna-pipeline [this run-id config samples]
    (run-pipeline! this :dna config run-id samples))
  (interrupt-dna-pipeline [this run-id]
    (interrupt-pipeline! docker :dna run-id))
  (run-rna-pipeline [this run-id config samples]
    (run-pipeline! this :rna config run-id samples))
  (interrupt-rna-pipeline [this run-id]
    (interrupt-pipeline! docker :rna run-id)))

(defmethod ig/prep-key ::executor [_ opts]
  (merge {:docker (ig/ref :genomon-api/docker),
          :storage (ig/ref :genomon-api/storage),
          :logger (ig/ref :duct/logger),
          :ch (ig/ref :genomon-api.db.worker/ch),
          :worker (ig/ref :genomon-api.db.worker/worker)} opts))

(s/def ::image string?)
(s/def ::tag string?)
(s/def ::aws-subnet-id
  (s/and string? #(re-matches #"(subnet-[0-9a-f]+)(,subnet-[0-9a-f]+)*" %)))
(s/def ::aws-security-group-id (s/and string? #(re-matches #"sg-[0-9a-f]+" %)))
(s/def ::aws-key-name (s/and string? not-empty))
(s/def ::aws-ecs-instance-role-name (s/and string? not-empty))
(s/def ::instance-option
  (s/keys :req-un [::aws-subnet-id ::aws-security-group-id
                   ::aws-key-name ::aws-ecs-instance-role-name]))
(s/def ::output-bucket (s/and string? #(str/starts-with? % "s3://")))
(s/def ::lazy-env (s/cat :tag #{:lazy-env} :env-var-name string?))
(s/def ::env (s/map-of string? (s/or :env-var string? :lazy-env-var ::lazy-env)))

(defmethod ig/pre-init-spec ::executor [_]
  (s/keys :req-un [::image ::output-bucket ::env]
          :opt-un [::tag]))

(defmethod ig/init-key ::executor
  [_ {:keys [docker logger ch image tag auth-config] :as opts}]
  (log/info logger ::prep-image {:image image, :tag tag})
  (if-let [img (d/prep-image docker image
                             (cond-> {}
                               tag (assoc :tag tag)
                               auth-config (assoc
                                            :auth-config
                                            (fill-lazy-env auth-config))))]
    (do
      (log/info logger ::use-image img)
      (listen-running-pipelines! opts)
      (-> opts
          (assoc :image (assoc img :image (:image opts) :tag tag))
          map->GenomonPipelineCloudExecutor))
    (throw (ex-info "Failed to find an image" {:image image, :tag tag}))))
