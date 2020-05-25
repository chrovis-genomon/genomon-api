(ns genomon-api.util.csv
  (:require [clojure.string :as str]
            [camel-snake-kebab.core :as csk]))

(defn- gen-input-file [xs]
  (str/join
   "\n\n"
   (for [[k vs] xs]
     (str/join \newline (cons (str \[ (csk/->snake_case_string k) \])
                              (map #(str/join \, %) vs))))))

(defn- gen-controlpanel-config [control-samples]
  (when (seq control-samples)
    (let [names (map-indexed (fn [i _] (str "control_sample" i)) control-samples)]
      `[[:bam-tofastq ~(mapv vector names control-samples)]
        [:controlpanel [["controlpanel" ~@names]]]])))

(defn gen-dna-input [{{normal-r1 :r1 normal-r2 :r2} :normal
                      {tumor-r1 :r1 tumor-r2 :r2} :tumor
                      :keys [controlpanel]}]
  (let [tumor-only? (not (and normal-r1 normal-r2))
        input (conj ["tumor"]
                    (if tumor-only? "None" "normal")
                    (if (seq controlpanel) "controlpanel" "None"))]
    (-> `[[:fastq ~(cond-> [["tumor" tumor-r1 tumor-r2]]
                     (not tumor-only?)
                     (conj ["normal" normal-r1 normal-r2]))]
          ~@(gen-controlpanel-config controlpanel)
          [:mutation-call [~input]]
          [:sv-detection [~input]]
          [:qc [["tumor"] ~@(when-not tumor-only? [["normal"]])]]]
        (gen-input-file))))

(defn gen-rna-input [{:keys [r1 r2 controlpanel]}]
  (-> `[[:fastq [["rna" ~r1 ~r2]]]
        ~@(gen-controlpanel-config controlpanel)
        [:fusion [~(conj ["rna"] (if (seq controlpanel) "controlpanel" "None"))]]
        [:expression [["rna"]]]
        [:intron-retention [["rna"]]]
        [:qc [["rna"]]]]
      (gen-input-file)))
