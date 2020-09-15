(ns genomon-api.handler.util
  (:require [integrant.core :as ig]))

(defmacro defhandler [key params & body]
  (let [refs {:executor `(ig/ref :genomon-api/executor)
              :db `(ig/ref :duct.database/sql)
              :storage `(ig/ref :genomon-api/storage)
              :logger `(ig/ref :duct/logger)
              :dna-config `(ig/ref :genomon-api.executor.genomon-pipeline-cloud.config/dna),
              :rna-config `(ig/ref :genomon-api.executor.genomon-pipeline-cloud.config/rna)
              :options `(ig/ref :genomon-api.handler/options)}
        m (->> params
               second
               :keys
               (map (comp (juxt identity refs) keyword))
               (filter second)
               (into {}))]
    `(do
       (defmethod ig/prep-key ~key [_# opts#]
         (merge ~m opts#))
       (defmethod ig/init-key ~key ~params ~@body))))
