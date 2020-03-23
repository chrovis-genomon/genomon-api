(ns genomon-api.handler.status
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as csk-ex]
            [genomon-api.docker :as docker]
            [clojure.string :as str])
  (:import [java.io File]
           [com.codahale.metrics MetricRegistry]
           [com.codahale.metrics.health HealthCheckRegistry]
           [com.zaxxer.hikari.metrics.dropwizard CodahaleMetricsTrackerFactory]
           [com.fasterxml.jackson.databind ObjectMapper]))

(deftype Dummy [])

(defn- as-file ^File [x]
  (when-let [f (io/as-file x)]
    (when (.isFile f)
      f)))

(defn- get-manifest []
  (some->> Dummy
           .getProtectionDomain
           .getCodeSource
           .getLocation
           as-file
           java.util.jar.JarFile.
           .getManifest
           .getMainAttributes
           (into {} (map (fn [[k v]] [(csk/->kebab-case-keyword (str k)) v])))))

(defn- ->map [m]
  (.convertValue (ObjectMapper.) m java.util.Map))

(def ^:const ^:private docker-info-keys
  [:server-version :kernel-version
   :containers :containers-paused :containers-running :containers-stopped
   :images :n-events-listener :n-fd])

(defmethod ig/prep-key ::get-status [_ opts]
  (merge {:docker (ig/ref :genomon-api/docker),
          :hikari-cp-health (ig/ref ::health-check),
          :hikari-cp-metrics (ig/ref ::metrics)} opts))

(defmethod ig/init-key ::get-status
  [_ {:keys [git-commit
             ^HealthCheckRegistry hikari-cp-health
             ^MetricRegistry hikari-cp-metrics
             docker]}]
  {:summary "Get server status",
   :handler
   (fn [req]
     (let [{:keys [leiningen-project-version build-jdk]} (get-manifest)
           app-ver (or (System/getProperty "genomon-api.version")
                       leiningen-project-version)
           hcp-health (->> hikari-cp-health .runHealthChecks ->map)
           hcp-metrics (->> hikari-cp-metrics .getMetrics ->map)
           docker-info (-> docker docker/info (select-keys docker-info-keys))]
       {:status 200,
        :body
        {:api-version 1,
         :application-version app-ver,
         :build-jdk build-jdk,
         :java-version (System/getProperty "java.version"),
         :java-runtime-version (System/getProperty "java.runtime.version"),
         :docker docker-info,
         :git-commit git-commit,
         :db-connection {:health hcp-health,
                         :metrics hcp-metrics}}}))})

(defmethod ig/init-key ::health-check [_ opts]
  (HealthCheckRegistry.))

(defmethod ig/init-key ::metrics [_ opts]
  (MetricRegistry.))

(defmethod ig/prep-key ::metrics-tracker-factory [_ opts]
  (merge {:registry (ig/ref ::metrics)} opts))

(defmethod ig/init-key ::metrics-tracker-factory [_ {:keys [registry]}]
  (CodahaleMetricsTrackerFactory. registry))
