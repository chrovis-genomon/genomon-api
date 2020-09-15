(ns genomon-api.handler
  (:require [clojure.walk :as walk]
            [integrant.core :as ig]
            [duct.core :as duct]
            [duct.core.env :as duct-env]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [reitit.coercion.spec :as rcs]
            [reitit.ring.coercion :as rrc]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [ring.middleware.http-response :as hr]
            [genomon-api.handler.status :as status]
            [genomon-api.handler.pipelines.dna :as dna]
            [genomon-api.handler.pipelines.rna :as rna])
  (:import [java.time Instant]
           [java.sql Timestamp]))

(def ^:private routes
  ["/api" {:response {400 {:error {:message string?}}}}
   ["/status" {:name ::status/status
               :get ::status/get-status}]
   ["/pipelines"
    ["/dna" {:swagger {:tags ["DNA"]}}
     ["/config" {:name ::dna/config,
                 :get ::dna/get-pipeline-info}]
     ["/runs"
      ["" {:name ::dna/runs,
           :get ::dna/list-runs,
           :post ::dna/create-new-run}]
      ["/:run-id" {:parameters {:path {:run-id uuid?}}}
       ["" {:name ::dna/run,
            :get ::dna/get-run,
            :put ::dna/interrupt-run,
            :delete ::dna/delete-run}]
       ["/normal.bam" {:name ::dna/normal-bam,
                       :get ::dna/get-normal-bam}]
       ["/tumor.bam" {:name ::dna/tumor-bam,
                      :get ::dna/get-tumor-bam}]
       ["/mutations.tsv" {:name ::dna/mutations,
                          :get ::dna/get-mutations}]
       ["/svs.tsv" {:name ::dna/svs,
                    :get ::dna/get-svs}]]]]
    ["/rna" {:swagger {:tags ["RNA"]}}
     ["/config" {:name ::rna/config,
                 :get ::rna/get-pipeline-info}]
     ["/runs"
      ["" {:name ::rna/runs,
           :get ::rna/list-runs,
           :post ::rna/create-new-run}]
      ["/:run-id" {:parameters {:path {:run-id uuid?}}}
       ["" {:name ::rna/run,
            :get ::rna/get-run,
            :put ::rna/interrupt-run,
            :delete ::rna/delete-run}]
       ["/tumor.bam" {:name ::rna/bam,
                      :get ::rna/get-bam}]
       ["/fusions.tsv" {:name ::rna/fusions,
                        :get ::rna/get-fusions}]
       ["/expressions.tsv" {:name ::rna/expressions,
                            :get ::rna/get-expressions}]
       ["/intron-retentions.tsv" {:name ::rna/intron-retentions,
                                  :get ::rna/get-intron-retentions}]
       ["/svs.tsv" {:name ::rna/svs,
                    :get ::rna/get-svs}]]]]]])

(def ^:private handler-refs
  (let [http-methods #{:get :head :post :put :delete :options :patch}
        xf (comp
            (filter map?)
            cat
            (filter (comp http-methods key))
            (map (comp (juxt identity ig/ref) val)))]
    (into {} xf (flatten routes))))

(defmethod ig/prep-key ::app [_ opts]
  (assoc opts :handlers handler-refs))

(defmethod ig/init-key ::app [_ {:keys [handlers]}]
  (ring/ring-handler
   (ring/router
    [["/swagger.json"
      {:get
       {:no-doc true,
        :swagger {:info {:title "genomon-api", :description "Genomon API"}},
        :handler (swagger/create-swagger-handler)}}]
     (walk/postwalk #(if-let [h (handlers %)] (assoc h :name %) %) routes)]
    {:data {:coercion rcs/coercion,
            :middleware [swagger/swagger-feature
                         rrc/coerce-exceptions-middleware
                         rrc/coerce-request-middleware
                         rrc/coerce-response-middleware
                         hr/wrap-http-response]}})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler
     {:path "/", :config {:validatorUrl nil, :operationsSorter "alpha"}})
    (ring/create-default-handler))))

;; For muuntaja application/edn

(defmethod print-method Instant [^Instant v w]
  (print-method (Timestamp/from v) w))

;; Module that merges all required handlers
(defmethod ig/init-key ::module [_ options]
  #(duct/merge-configs
    %
    (into {} (map (juxt identity (constantly {})) (keys handler-refs)))
    {::app {}, [:duct/const ::options] options}))
