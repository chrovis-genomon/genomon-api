(defproject genomon-api "0.1.0-SNAPSHOT"
  :description "An API server of genomon_pipeline_cloud"
  :url "https://github.com/chrovis/genomon-api"
  :license {:name "GNU General Pulibc License, Version 3.0"
            :url "https://www.gnu.org/licenses/gpl-3.0.html"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "0.7.559"]
                 [integrant "0.8.0"]
                 [duct/core "0.8.0"
                  :exclusions [integrant]]
                 [duct/module.logging "0.5.0"]
                 [duct/module.sql "0.6.0"]
                 [duct/module.web "0.7.0"
                  :exclusions [metosin/muuntaja
                               ring/ring-codec
                               org.eclipse.jetty/jetty-server]]
                 [org.eclipse.jetty/jetty-server "9.4.15.v20190215"]
                 [ring/ring-codec "1.1.2"
                  :exclusions [commons-codec]] ;; duct/module.web
                 [commons-codec "1.14"] ;; ring/ring-codec
                 [org.apache.commons/commons-compress "1.19"]
                 [mysql/mysql-connector-java "8.0.19"]
                 [com.layerware/hugsql "0.5.1"]
                 [metosin/muuntaja "0.6.6"] ;; duct/module.web
                 [metosin/jsonista "0.2.5"] ;; duct/module.web
                 [metosin/reitit "0.4.2"]
                 [metosin/ring-http-response "0.9.1"]
                 [com.github.docker-java/docker-java "3.1.5"]
                 [javax.activation/activation "1.1.1"]
                 [com.cognitect/anomalies "0.1.12"]
                 [com.cognitect.aws/api "0.8.423"]
                 [com.cognitect.aws/endpoints "1.1.11.714"]
                 [com.cognitect.aws/s3 "784.2.593.0"]
                 [clj-commons/clj-yaml "0.7.0"]
                 [camel-snake-kebab "0.4.1"]
                 [instaparse "1.4.10"]
                 [org.flatland/ordered "1.5.7"]
                 [io.dropwizard.metrics/metrics-core "4.1.2"]
                 [io.dropwizard.metrics/metrics-healthchecks "4.1.2"]]
  :plugins [[duct/lein-duct "0.12.1"]
            [lein-eftest "0.5.9"]]
  :main ^:skip-aot genomon-api.main
  :resource-paths ["resources" "target/resources"]
  :test-paths     ["test/src"]
  :prep-tasks     ["javac" "compile" ["run" ":duct/compiler"]]
  :middleware     [lein-duct.plugin/middleware]
  :test-selectors {:default (complement :integration),
                   :integration :integration,
                   :all (constantly true)}
  :profiles
  {:dev  [:project/dev :profiles/dev]
   :repl {:prep-tasks   ^:replace ["javac" "compile"]
          :repl-options {:init-ns user}}
   :uberjar {:aot :all}
   :profiles/dev {}
   :project/dev  {:source-paths   ["dev/src"]
                  :resource-paths ["dev/resources" "test/resources"]
                  :dependencies   [[integrant/repl "0.3.1"]
                                   [hawk "0.2.11"]
                                   [eftest "0.5.9"]
                                   [kerodon "0.9.1"
                                    :exclusions [ring/ring-codec]]
                                   [com.h2database/h2 "1.4.200"]
                                   [com.gearswithingears/shrubbery "0.4.1"]]
                  :global-vars {*warn-on-reflection* true}}})
