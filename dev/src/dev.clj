(ns dev
  (:refer-clojure :exclude [test])
  (:require [clojure.repl :refer :all]
            [fipp.edn :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [ragtime.core :as ragtime]
            [ragtime.jdbc :as jdbc]
            [duct.core :as duct]
            [duct.core.repl :as duct-repl :refer [auto-reset]]
            [eftest.runner :as eftest]
            [integrant.core :as ig]
            [integrant.repl :refer [clear halt go init prep reset]]
            [integrant.repl.state :refer [config system]]
            [genomon-api-dev.db.sql]))

(duct/load-hierarchy)

(defn read-config []
  (duct/read-config (io/resource "genomon_api/config.edn")))

(defn test []
  (eftest/run-tests (eftest/find-tests "test/src")))

(def profiles
  [:duct.profile/dev :duct.profile/local])

(clojure.tools.namespace.repl/set-refresh-dirs "dev/src" "src" "test/src")

(when (io/resource "local.clj")
  (load "local"))

(integrant.repl/set-prep! #(duct/prep-config (read-config) profiles))

(defn- rollback-last
  ([]
   (rollback-last 1))
  ([n]
   (rollback-last n {}))
  ([n options]
   (let [[_ db] (ig/find-derived-1 system :duct.database/sql)
         [_ index] (ig/find-derived-1 system :duct.migrator/ragtime)
         store (jdbc/sql-database (:spec db))]
     (ragtime/rollback-last store index n options))))
