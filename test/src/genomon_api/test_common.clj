(ns genomon-api.test-common
  (:require [integrant.core :as ig]
            [medley.core :as medley]
            [duct.core :as duct]))

(duct/load-hierarchy)

(def ^:dynamic *system* nil)
(def ^:private lock (Object.))

(defn- dissoc-in-keys [m & ks]
  (reduce medley/dissoc-in m ks))

(defn system-fixture [ks f]
  (let [system (locking lock
                 (-> (duct/resource "genomon_api/config.edn")
                     (duct/read-config)
                     (dissoc-in-keys
                      [:duct.profile/base
                       :genomon-api.executor.genomon-pipeline-cloud/executor]
                      [:duct.profile/base
                       :genomon-api.aws/client]
                      [:duct.profile/base
                       :genomon-api.docker.core/client])
                     (duct/prep-config [:duct.profile/test])
                     (ig/init (cons :duct.migrator/ragtime ks))))]
    (binding [*system* system]
      (f))
    (ig/halt! system)))
