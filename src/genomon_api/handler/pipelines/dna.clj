(ns genomon-api.handler.pipelines.dna
  (:require [clojure.spec.alpha :as s]
            [integrant.core :as ig]
            [duct.logger :as log]
            [ring.util.response :as rur]
            [ring.util.http-response :as hr]
            [reitit.core :as r]
            [genomon-api.executor :as exec]
            [genomon-api.db :as db]
            [genomon-api.storage :as storage]
            [genomon-api.handler.util :refer [defhandler]]
            [camel-snake-kebab.extras :as csk-ex]
            [camel-snake-kebab.core :as csk]
            [spec-tools.data-spec :as ds])
  (:import [java.util UUID]))

(defhandler ::get-pipeline-info [_ {:keys [dna-config]}]
  {:summary "Get pipeline config",
   :description "Get config of the DNA pipeline.",
   :response {200 {:body {:config (s/map-of string? ;; TODO: def spec
                                            (s/map-of string?
                                                      (s/nilable string?)))}}},
   :handler (fn [_]
              {:status 200,
               :body {:config dna-config}})})

(defhandler ::list-runs [_ {:keys [db]}]
  {:summary "List all runs",
   :parameters {:query {:limit nat-int?, :offset nat-int?}},
   :response {200 {:body {:runs []}}}, ;; TODO
   :handler (fn [{{:keys [query]} :parameters}]
              {:status 200,
               :body {:runs (db/list-dna-runs db query)}})})

(defhandler ::create-new-run [_ {:keys [db executor dna-config logger]}]
  {:summary "Create a new run",
   :parameters {:body {:tumor {:r1 string?, :r2 string?},
                       (ds/opt :normal) {:r1 string?, :r2 string?},
                       (ds/opt :controlpanel) [string?]}},
   :response {201 {:body {:run-id uuid?}}},
   :handler (fn [{{:keys [body]} :parameters, ::r/keys [router]}]
              (let [id (UUID/randomUUID)
                    run (->> (merge {:normal {:r1 nil :r2 nil}} body)
                             (exec/run-dna-pipeline executor id dna-config))]
                (try
                  (db/create-dna-run db run)
                  (rur/created
                   (-> router
                       (r/match-by-name ::run run)
                       (r/match->path {}))
                   {:run-id id})
                  (catch Throwable e
                    (log/error logger ::create-run-fail {:body body, :error e})
                    (exec/interrupt-dna-pipeline executor id)
                    (throw e)))))})

(defhandler ::get-run [_ {:keys [db]}]
  {:summary "Get detailed info of a run",
   :response {200 {:body {:run {:run-id uuid?, ;; TODO: spec
                                :created-at inst?,
                                :status :created}}}},
   :handler (fn [{{:keys [path]} :parameters}]
              (if-let [run (db/get-dna-run db path)]
                {:status 200, :body {:run run}}
                (hr/not-found)))})

(defhandler ::interrupt-run [_ {:keys [db executor]}]
  {:summary "Interrupt a running job",
   :handler (fn [{{{:keys [run-id] :as path} :path} :parameters}]
              (exec/interrupt-dna-pipeline executor run-id) ;; TODO: handle error
              (db/update-dna-run-status db (assoc path :status :interrupted))
              {:status 200, :body nil})})

(defhandler ::delete-run [_ {:keys [db storage]}]
  {:summary "Delete a job history and all results and intermediate files",
   :response {204 {:body nil?},
              404 {:body nil?},
              409 {:body {:error {:message string?, :status keyword?}}}},
   :handler (fn [{{{:keys [run-id] :as path} :path} :parameters}]
              (let [{:keys [status output-dir] :as run} (db/get-dna-run db path)]
                (when-not run
                  (hr/not-found!))
                (when-not (#{:succeeded :failed :interrupted} status)
                  (hr/conflict! {:error
                                 {:message "Interrupt to delete this run.",
                                  :status status}}))
                (do (storage/delete-dir storage output-dir)
                    (db/delete-dna-run db path) ;; TODO: handle errors
                    {:status 204, :body nil})))})

(defn- result-handler [{:keys [db storage]} result-key]
  {:response {200 {},
              202 {},
              404 {}},
   :handler
   (fn [{{:keys [path]} :parameters}]
     (let [{:keys [status results]} (db/get-dna-run db path)]
       (case status
         :succeeded (if-let [m (some->> results
                                        result-key
                                        (storage/stream-content storage))]
                      {:status 200,
                       :headers (->> (select-keys m [:e-tag :content-type])
                                     (csk-ex/transform-keys
                                      csk/->HTTP-Header-Case-String)),
                       :body (:body m)}
                      {:status 404})
         (:created :started :running) {:status 202}
         (:interrupted :failed) {:status 404})))})

(defhandler ::get-normal-bam [_ {:keys [db storage] :as opts}]
  (assoc (result-handler opts :normal-bam)
         :summary "Get normal alignments in BAM format"
         :swagger {:produces ["application/octet-stream"]}))

(defhandler ::get-tumor-bam [_ {:keys [db storage] :as opts}]
  (assoc (result-handler opts :tumor-bam)
         :summary "Get tumor alignments in BAM format"
         :swagger {:produces ["applicaiton/octet-stream"]}))

(defhandler ::get-mutations [_ {:keys [db storage] :as opts}]
  (assoc (result-handler opts :mutations)
         :summary "Get a list of filtered mutations in TSV format"
         :swagger {:produces ["text/tab-separated-values"]}))

(defhandler ::get-svs [_ {:keys [db storage] :as opts}]
  (assoc (result-handler opts :svs)
         :summary "Get a list of structural variants in TSV format"
         :swagger {:produces ["text/tab-separated-values"]}))
