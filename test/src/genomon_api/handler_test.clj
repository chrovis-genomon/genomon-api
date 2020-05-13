(ns genomon-api.handler-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.core.async :as async]
            [integrant.core :as ig]
            [medley.core :as medley]
            [duct.core :as duct]
            [ring.mock.request :as mock]
            [shrubbery.core :as shrubbery]
            [shrubbery.clojure.test]
            [genomon-api.test-common :as common]
            [genomon-api.handler :as handler]
            [genomon-api.executor :as exec]
            [genomon-api.storage :as storage]
            [genomon-api.docker :as docker]
            [genomon-api-dev.db.sql]))

(def ^:dynamic *handler* nil)
(def ^:const handler-key :duct.handler/root)

(defn- handler-fixture [f]
  (binding [*handler* (val (ig/find-derived-1 common/*system* handler-key))]
    (f)))

(use-fixtures :once
  (partial common/system-fixture [handler-key :genomon-api.db.worker/worker])
  handler-fixture)

(defn- request
  ([url]
   (request :get url))
  ([method url]
   (request method url nil))
  ([method url params]
   (-> (mock/request method url params)
       (assoc-in [:headers "content-type"] "application/edn")
       (assoc-in [:headers "accept"] "application/edn")
       *handler*
       (update :body (comp edn/read-string slurp)))))

(def ^:const ^:private example-bucket
  "s3://genomon-api/test/")
(def ^:const ^:private example-image-id
  "sha256:33735e4ee2ed536589a151d520a48649688ce403abc32be770c0ea532b503762")
(def ^:const ^:private example-container-id-1
  "9e73a636eb02a3b36928c362f2c60c32cff76aad44209b73d81f63e86029bb70")
(def ^:const ^:private example-container-id-2
  "9e73a636eb02a3b36928c362f2c60c32cff76aad44209b73d81f63e86029bb70")

(defmethod ig/prep-key ::executor [_ opts]
  (merge {:ch (ig/ref :genomon-api.db.worker/ch)} opts))

(defmethod ig/init-key ::executor [_ {:keys [ch]}]
  (reify exec/IExecutor
    (run-dna-pipeline [this run-id config samples]
      (let [run {:run-id run-id,
                 :pipeline-type :dna,
                 :status :created,
                 :samples samples,
                 :output-dir (str example-bucket run-id),
                 :image-id example-image-id,
                 :container-id example-container-id-1,
                 :config config,
                 :results {:normal-bam (when (and (get-in samples [:normal :r1])
                                                  (get-in samples [:normal :r2]))
                                         (str example-bucket run-id "/normal.bam")),
                           :tumor-bam (str example-bucket run-id "/tumor.bam"),
                           :mutations (str example-bucket run-id "/mut.txt"),
                           :svs (str example-bucket run-id "/sv.txt")}}]
        (async/go
          (async/<! (async/timeout 100))
          (async/>! ch (assoc run :status :started))
          (async/<! (async/timeout 500))
          (async/>! ch (assoc run :status :succeeded)))
        run))
    (interrupt-dna-pipeline [this run-id] nil)
    (run-rna-pipeline [this run-id config samples]
      (let [run {:run-id run-id,
                 :pipeline-type :rna,
                 :status :created,
                 :samples samples,
                 :output-dir (str example-bucket run-id),
                 :image-id example-image-id,
                 :container-id example-container-id-2,
                 :config config,
                 :results {:bam (str example-bucket run-id "/tumor.bam"),
                           :fusions (str example-bucket run-id "/fusions.tsv"),
                           :expressions (str example-bucket run-id "/expressions.tsv"),
                           :intron-retentions (str example-bucket run-id "/intron-retentions.tsv")}}]
        (async/go
          (async/<! (async/timeout 100))
          (async/>! ch (assoc run :status :started))
          (async/<! (async/timeout 500))
          (async/>! ch (assoc run :status :succeeded)))
        run))
    (interrupt-rna-pipeline [this run-id] nil)))

(defmethod ig/init-key ::storage [_ _]
  (reify storage/IStorage
    (stat [this url] true)
    (stream-content [this url]
      (when url
        {:body (java.io.ByteArrayInputStream. (byte-array []))}))
    (delete-dir [this url] true)))

(defmethod ig/init-key ::docker [_ _]
  (reify docker/IDocker
    (info [this] {})))

;;;; Tests

(deftest smoke-test
  (testing "status"
    (let [{:keys [status body]} (request "/api/status")]
      (is (= 200 status))
      (is (map? body))))
  (testing "DNA config exists"
    (let [{:keys [status body]} (request "/api/pipelines/dna/config")]
      (is (= 200 status) "response ok")
      (is (map? (:config body)))
      (is (every? keyword? (keys (:config body))))
      (is (every? map? (vals (:config body))))))
  (testing "List DNA runs"
    (let [{:keys [status body]} (request :get "/api/pipelines/dna/runs"
                                         {:limit 10, :offset 0})]
      (is (= 200 status) "response ok")
      (is (seq? (:runs body)))))
  (testing "Start new DNA run"
    (let [{post-status :status
           {:keys [run-id]}
           :body} (request :post "/api/pipelines/dna/runs"
                           (pr-str {:normal {:r1 "normal-r1",
                                             :r2 "normal-r2"},
                                    :tumor {:r1 "tumor-r1",
                                            :r2 "tumor-r2"}}))
          {get-status :status
           {:keys [run]} :body} (request
                                 (str "/api/pipelines/dna/runs/" run-id))
          config (-> (request "/api/pipelines/dna/config")
                     :body
                     :config)]
      (is (= 201 post-status))
      (is (uuid? run-id))
      (is (= 200 get-status))
      (is (= {:run-id run-id,
              :status :created,
              :image-id example-image-id,
              :container-id example-container-id-1,
              :output-dir (str example-bucket run-id),
              :samples {:normal {:r1 "normal-r1",
                                 :r2 "normal-r2"},
                        :tumor {:r1 "tumor-r1",
                                :r2 "tumor-r2"}},
              :results {:normal-bam nil,
                        :tumor-bam nil,
                        :mutations nil,
                        :svs nil},
              :config config}
             (dissoc run :created-at :updated-at)))
      (loop [i 0]
        (when (< i 5)
          (when-not (->> run-id
                         (str "/api/pipelines/dna/runs/")
                         request
                         :body
                         :run
                         :status
                         (= :succeeded))
            (Thread/sleep 1000)
            (recur (inc i)))))
      (let [normal-bam (*handler* (mock/request :get (str "/api/pipelines/dna/runs/" run-id "/normal.bam")))
            tumor-bam (*handler* (mock/request :get (str "/api/pipelines/dna/runs/" run-id "/tumor.bam")))
            mutations (*handler* (mock/request :get (str "/api/pipelines/dna/runs/" run-id "/mutations.tsv")))
            svs (*handler* (mock/request :get (str "/api/pipelines/dna/runs/" run-id "/svs.tsv")))]
        (is (= 200 (:status normal-bam)))
        (is (= 200 (:status tumor-bam)))
        (is (= 200 (:status mutations)))
        (is (= 200 (:status svs))))))
  (testing "Start new DNA run without normal sample"
    (let [{post-status :status
           {:keys [run-id]}
           :body} (request :post "/api/pipelines/dna/runs"
                           (pr-str {:tumor {:r1 "tumor-r1",
                                            :r2 "tumor-r2"}}))
          {get-status :status
           {:keys [run]} :body} (request
                                 (str "/api/pipelines/dna/runs/" run-id))
          config (-> (request "/api/pipelines/dna/config")
                     :body
                     :config)]
      (is (= 201 post-status))
      (is (uuid? run-id))
      (is (= 200 get-status))
      (is (= {:run-id run-id,
              :status :created,
              :image-id example-image-id,
              :container-id example-container-id-1,
              :output-dir (str example-bucket run-id),
              :samples {:normal {:r1 nil, :r2 nil}
                        :tumor {:r1 "tumor-r1",
                                :r2 "tumor-r2"}},
              :results {:normal-bam nil,
                        :tumor-bam nil,
                        :mutations nil,
                        :svs nil},
              :config config}
             (dissoc run :created-at :updated-at)))
      (loop [i 0]
        (when (< i 5)
          (when-not (->> run-id
                         (str "/api/pipelines/dna/runs/")
                         request
                         :body
                         :run
                         :status
                         (= :succeeded))
            (Thread/sleep 1000)
            (recur (inc i)))))
      (let [normal-bam (*handler* (mock/request :get (str "/api/pipelines/dna/runs/" run-id "/normal.bam")))
            tumor-bam (*handler* (mock/request :get (str "/api/pipelines/dna/runs/" run-id "/tumor.bam")))
            mutations (*handler* (mock/request :get (str "/api/pipelines/dna/runs/" run-id "/mutations.tsv")))
            svs (*handler* (mock/request :get (str "/api/pipelines/dna/runs/" run-id "/svs.tsv")))]
        (is (= 404 (:status normal-bam)))
        (is (= 200 (:status tumor-bam)))
        (is (= 200 (:status mutations)))
        (is (= 200 (:status svs))))))
  (testing "RNA config exists"
    (let [{:keys [status body]} (request "/api/pipelines/rna/config")]
      (is (= 200 status) "response ok")
      (is (map? (:config body)))
      (is (every? keyword? (keys (:config body))))
      (is (every? map? (vals (:config body))))))
  (testing "List RNA runs"
    (let [{:keys [status body]} (request :get "/api/pipelines/rna/runs"
                                         {:limit 10, :offset 0})]
      (is (= 200 status) "response ok")
      (is (seq? (:runs body)))))
  (testing "Start new RNA run"
    (let [{post-status :status
           {:keys [run-id]}
           :body} (request :post "/api/pipelines/rna/runs"
                           (pr-str {:r1 "rna-r1",
                                    :r2 "rna-r2"}))
          {get-status :status
           {:keys [run]} :body} (request
                                 (str "/api/pipelines/rna/runs/" run-id))
          config (-> (request "/api/pipelines/rna/config")
                     :body
                     :config)]
      (is (= 201 post-status))
      (is (uuid? run-id))
      (is (= 200 get-status))
      (is (= {:run-id run-id,
              :status :created,
              :image-id example-image-id,
              :container-id example-container-id-1,
              :output-dir (str example-bucket run-id),
              :samples {:r1 "rna-r1", :r2 "rna-r2"},
              :results {:bam nil,
                        :fusions nil,
                        :expressions nil,
                        :intron-retentions nil},
              :config config}
             (dissoc run :created-at :updated-at)))
      (loop [i 0]
        (when (< i 5)
          (when-not (->> run-id
                         (str "/api/pipelines/rna/runs/")
                         request
                         :body
                         :run
                         :status
                         (= :succeeded))
            (Thread/sleep 1000)
            (recur (inc i)))))
      (let [tumor-bam (*handler* (mock/request :get (str "/api/pipelines/rna/runs/" run-id "/tumor.bam")))
            fusions (*handler* (mock/request :get (str "/api/pipelines/rna/runs/" run-id "/fusions.tsv")))
            expressions (*handler* (mock/request :get (str "/api/pipelines/rna/runs/" run-id "/expressions.tsv")))
            intron-retentions (*handler* (mock/request :get (str "/api/pipelines/rna/runs/" run-id "/intron-retentions.tsv")))]
        (is (= 200 (:status tumor-bam)))
        (is (= 200 (:status fusions)))
        (is (= 200 (:status expressions)))
        (is (= 200 (:status intron-retentions)))))))
