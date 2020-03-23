(ns genomon-api.executor.genomon-pipeline-cloud.config
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [camel-snake-kebab.core :as csk]))

(defn- instance-option->str [instance-option]
  (->> instance-option
       (mapcat (fn [[k v]]
                 [(str "--" (csk/->kebab-case-string k)) v]))
       (str/join \space)))

(defmethod ig/init-key ::config [_ {:keys [instance-option config]}]
  (assoc-in config [:general :instance-option]
            (instance-option->str instance-option)))
