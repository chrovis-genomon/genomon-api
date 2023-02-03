(defproject genomon-api "0.1.4-SNAPSHOT"
  :description "An API server of genomon_pipeline_cloud"
  :url "https://github.com/chrovis/genomon-api"
  :license {:name "GNU General Pulibc License, Version 3.0"
            :url "https://www.gnu.org/licenses/gpl-3.0.html"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.6.673"]
                 [integrant "0.8.0"]
                 [duct/core "0.8.0"
                  :exclusions [medley]]
                 [duct/module.logging "0.5.0"]
                 [duct/module.sql "0.6.1"]
                 [duct/module.web "0.7.3"
                  :exclusions [ring/ring-core
                               ring/ring-codec
                               metosin/jsonista
                               crypto-random
                               commons-codec
                               com.fasterxml.jackson.datatype/jackson-datatype-jsr310]]
                 [org.eclipse.jetty/jetty-server "9.4.48.v20220622"]
                 [org.apache.commons/commons-compress "1.22"]
                 [mysql/mysql-connector-java "8.0.32"]
                 [com.layerware/hugsql "0.5.3"]
                 [metosin/reitit "0.5.18"]
                 [metosin/ring-http-response "0.9.3"
                  :exclusions [ring/ring-core]]
                 [com.github.docker-java/docker-java "3.2.14"
                  :exclusions [commons-io
                               commons-codec]]
                 [javax.activation/activation "1.1.1"]
                 [com.cognitect/anomalies "0.1.12"]
                 [com.cognitect.aws/api "0.8.641"]
                 [com.cognitect.aws/endpoints "1.1.12.398"]
                 [com.cognitect.aws/s3 "825.2.1250.0"]
                 [camel-snake-kebab "0.4.3"]
                 [instaparse "1.4.12"]
                 [org.flatland/ordered "1.15.10"]
                 [io.dropwizard.metrics/metrics-core "4.2.15"]
                 [io.dropwizard.metrics/metrics-healthchecks "4.2.15"]]
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
   :uberjar {:aot :all,
             :uberjar-exclusions ["genomon_api/config.edn"
                                  "genomon_api/dna_param.edn"
                                  "genomon_api/rna_param.edn"]}
   :profiles/dev {}
   :project/dev  {:source-paths   ["dev/src"]
                  :resource-paths ["dev/resources" "test/resources"]
                  :dependencies   [[integrant/repl "0.3.2"]
                                   [hawk "0.2.11"]
                                   [eftest "0.5.9"
                                    :exclusions [mvxcvi/puget]]
                                   [kerodon "0.9.1"
                                    :exclusions [clj-time
                                                 ring/ring-codec]]
                                   [com.h2database/h2 "2.1.214"]
                                   [com.gearswithingears/shrubbery "0.4.1"]]
                  :global-vars {*warn-on-reflection* true}}})
