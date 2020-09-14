(ns genomon-api.handler.pipelines.rna
  (:require [clojure.spec.alpha :as s]
            [integrant.core :as ig]
            [ring.util.response :as rur]
            [ring.util.http-response :as hr]
            [reitit.core :as r]
            [duct.logger :as log]
            [genomon-api.executor :as exec]
            [genomon-api.db :as db]
            [genomon-api.storage :as storage]
            [genomon-api.handler.util :refer [defhandler]]
            [camel-snake-kebab.extras :as csk-ex]
            [camel-snake-kebab.core :as csk]
            [spec-tools.data-spec :as ds])
  (:import [java.util UUID]))

(defhandler ::get-pipeline-info [_ {:keys [rna-config]}]
  {:summary "Get pipeline config",
   :description "Get current config of the RNA pipeline.",
   :response {200 {:body {:config (s/map-of string? ;; TODO: def spec
                                            (s/map-of string?
                                                      (s/nilable string?)))}}},
   :handler (fn [_]
              {:status 200,
               :body {:config rna-config}})})

(defhandler ::list-runs [_ {:keys [db]}]
  {:summary "List all runs",
   :parameters {:query {:limit nat-int?, :offset nat-int?}},
   :response {200 {:body {:runs []}}}, ;; TODO
   :handler (fn [{{:keys [query]} :parameters}]
              {:status 200,
               :body {:runs (db/list-rna-runs db query)}})})

(defhandler ::create-new-run [_ {:keys [db executor rna-config logger options]}]
  {:summary "Create a new run",
   :parameters {:body (cond-> {:r1 string?, :r2 string?,
                               (ds/opt :control-panel) [string?]}
                        (:allow-config-overrides? options)
                        (assoc (ds/opt :config)
                               (s/map-of keyword?
                                         (s/map-of keyword?
                                                   (s/nilable string?)))))},
   :response {201 {:body {:run-id uuid?}}},
   :handler (fn [{{:keys [body]
                   {:keys [config] :or {config rna-config}} :body} :parameters,
                  ::r/keys [router]}]
              (let [id (UUID/randomUUID)
                    samples (dissoc body :config)
                    run (exec/run-rna-pipeline executor id config samples)]
                (try
                  (db/create-rna-run db run)
                  (rur/created
                   (-> router
                       (r/match-by-name ::run run)
                       (r/match->path {}))
                   {:run-id id})
                  (catch Throwable e
                    (log/error logger ::create-run-fail {:body body, :error e})
                    (exec/interrupt-rna-pipeline executor id)
                    (throw e)))))})

(defhandler ::get-run [_ {:keys [db]}]
  {:summary "Get detailed info of a run",
   :response {200 {:body {:run {:run-id uuid?, ;; TODO: spec
                                :created-at inst?,
                                :status :created}}}},
   :handler (fn [{{:keys [path]} :parameters}]
              (if-let [run (db/get-rna-run db path)]
                {:status 200, :body {:run run}}
                (hr/not-found)))})

(defhandler ::interrupt-run [_ {:keys [db executor]}]
  {:summary "Interrupt a running job",
   :handler (fn [{{{:keys [run-id] :as path} :path} :parameters}]
              (exec/interrupt-rna-pipeline executor run-id) ;; TODO: handle error
              (db/update-rna-run-status db (assoc path :status :interrupted))
              {:status 200, :body nil})})

(defhandler ::delete-run [_ {:keys [db storage]}]
  {:summary "Delete a job history and all results and intermediate files",
   :response {204 {:body nil?},
              404 {:body nil?},
              409 {:body {:error {:message string?, :status keyword?}}}},
   :handler (fn [{{{:keys [run-id] :as path} :path} :parameters}]
              (let [{:keys [status output-dir] :as run} (db/get-rna-run db path)]
                (when-not run
                  (hr/not-found!))
                (when-not (#{:succeeded :failed :interrupted} status)
                  (hr/conflict! {:error
                                 {:message "Interrupt to delete this run.",
                                  :status status}}))
                (do (storage/delete-dir storage output-dir)
                    (db/delete-rna-run db path) ;; TODO: handle errors
                    {:status 204, :body nil})))})

(defn- result-handler [{:keys [db storage]} result-key]
  {:response {200 {},
              202 {}},
   :handler
   (fn [{{:keys [path]} :parameters}]
     (let [{:keys [status results]} (db/get-rna-run db path)]
       (case status
         :succeeded (let [m (->> results
                                 result-key
                                 (storage/stream-content storage))]
                      {:status 200,
                       :headers (->> (select-keys m [:e-tag :content-type])
                                     (csk-ex/transform-keys
                                      csk/->HTTP-Header-Case-String)),
                       :body (:body m)})
         (:created :started :running) {:status 202}
         (:interrupted :failed) {:status 404})))})

(defhandler ::get-bam [_ {:keys [db storage] :as opts}]
  (assoc (result-handler opts :bam)
         :summary "Get alignments in BAM format"
         :swagger {:produces ["application/octet-stream"]}))

(defhandler ::get-fusions [_ {:keys [db storage] :as opts}]
  (assoc (result-handler opts :fusions)
         :summary "Get a list of filtered fusion genes in TSV format"
         :swagger {:produces ["text/tab-separated-values"]}))

(defhandler ::get-expressions [_ {:keys [db storage] :as opts}]
  (assoc (result-handler opts :expressions)
         :summary "Get a list of gene expressions in TSV format"
         :swagger {:produces ["text/tab-separated-values"]}))

(defhandler ::get-intron-retentions [_ {:keys [db storage] :as opts}]
  (assoc (result-handler opts :intron-retentions)
         :summary "Get a list of intron-retentions in TSV format"
         :swagger {:produces ["text/tab-separated-values"]}))

(defhandler ::get-svs [_ {:keys [db storage] :as opts}]
  (assoc (result-handler opts :svs)
         :summary "Get a list of structural variants in TSV format"
         :swagger {:produces ["text/tab-separated-values"]}))
