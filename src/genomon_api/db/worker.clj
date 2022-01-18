(ns genomon-api.db.worker
  (:require [clojure.core.async :as async]
            [integrant.core :as ig]
            [duct.logger :as log]
            [genomon-api.db :as db]))

(defmethod ig/prep-key ::ch [_ opts]
  (merge {:logger (ig/ref :duct/logger)} opts))

(defmethod ig/init-key ::ch [_ {:keys [buf-or-n logger]}]
  (async/chan buf-or-n
              identity
              (fn [e] (log/error logger ::unhandled {:error e}))))

(defmethod ig/halt-key! ::ch [_ ch]
  (async/close! ch))

(defmethod ig/prep-key ::worker [_ opts]
  (merge {:ch (ig/ref ::ch),
          :db (ig/ref :duct.database/sql),
          :logger (ig/ref :duct/logger),
          :migrator (ig/ref :duct.migrator/ragtime)} opts))

(defmethod ig/init-key ::worker [_ {:keys [db ch logger]}]
  (log/debug logger ::start-worker {})
  (async/go-loop []
    (if-let [{:keys [pipeline-type status] :as m} (async/<! ch)]
      (do
        (try
          (when (#{:failed} status)
            (log/error logger ::failed m))
          (case pipeline-type
            :dna (db/update-dna-run-status db m)
            :rna (db/update-rna-run-status db m))
          (catch Throwable e
            (log/error logger ::error (assoc m :error e))))
        (recur))
      (log/debug logger ::end-worker {}))))
