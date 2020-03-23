(ns genomon-api.util.csv
  (:require [clojure.string :as str]
            [camel-snake-kebab.core :as csk]))

(defn- gen-input-file [xs]
  (str/join
   "\n\n"
   (for [[k vs] xs]
     (str/join \newline (cons (str \[ (csk/->snake_case_string k) \])
                              (map #(str/join \, %) vs))))))

(defn gen-dna-input [{{normal-r1 :r1 normal-r2 :r2} :normal
                      {tumor-r1 :r1 tumor-r2 :r2} :tumor}]
  (gen-input-file [[:fastq [["tumor" tumor-r1 tumor-r2]
                            ["normal" normal-r1 normal-r2]]]
                   [:mutation-call [["tumor" "normal" "None"]]]
                   [:sv-detection [["tumor" "normal" "None"]]]
                   [:qc [["tumor"]
                         ["normal"]]]]))

(defn gen-rna-input [{:keys [r1 r2]}]
  (gen-input-file [[:fastq [["rna" r1 r2]]]
                   [:fusion [["rna" "None"]]]
                   [:expression [["rna"]]]
                   [:intron-retention [["rna"]]]
                   [:qc [["rna"]]]]))
