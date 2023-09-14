(ns genomon-api.util.csv
  (:require [clojure.string :as str]
            [camel-snake-kebab.core :as csk]))

(defn- gen-input-file [xs]
  (str/join
   "\n\n"
   (for [[k vs] xs]
     (str/join \newline (cons (str \[ (csk/->snake_case_string k) \])
                              (map #(str/join \, %) vs))))))

(defn- gen-controlpanel-config [control-panel]
  (when (seq control-panel)
    (let [names (map-indexed (fn [i _] (str "control_sample" i)) control-panel)]
      `[[:bam-tofastq ~(mapv vector names control-panel)]
        [:controlpanel [["control_panel" ~@names]]]])))

(defn gen-dna-input
  ([samples] (gen-dna-input samples {}))
  ([{{normal-r1 :r1 normal-r2 :r2 normal-bam :bam} :normal
     {tumor-r1 :r1 tumor-r2 :r2 tumor-bam :bam} :tumor
     :keys [control-panel] :as input}
    {:keys [include-qc?]}]
   (let [align-normal? (and (not normal-bam) normal-r1 normal-r2)
         align-tumor? (and (not tumor-bam) tumor-r1 tumor-r2)
         tumor-only? (not (or (and normal-r1 normal-r2) normal-bam))]
     (when-not (or tumor-bam (and tumor-r1 tumor-r2))
       (throw (ex-info "Either bam or R1/R2 fastq of tumor is required" input)))
     (when (or (and tumor-bam (or tumor-r1 tumor-r2))
               (and normal-bam (or normal-r1 normal-r2)))
       (throw (ex-info "Bam and fastq cannot be specified at once" input)))
     (-> `[~@(when (or normal-bam tumor-bam)
               `[[:bam-import
                  ~(cond-> []
                     normal-bam (conj ["normal" normal-bam])
                     tumor-bam (conj ["tumor" tumor-bam]))]])
           ~@(when (or align-normal? align-tumor?)
               `[[:fastq
                  ~(cond-> []
                     align-tumor? (conj ["tumor" tumor-r1 tumor-r2])
                     align-normal? (conj ["normal" normal-r1 normal-r2]))]])
           ~@(gen-controlpanel-config control-panel)
           [:mutation-call
            [["tumor" ~(if tumor-only? "None" "normal") "None"]]]
           [:sv-detection
            [~(conj ["tumor"]
                    (if tumor-only? "None" "normal")
                    (if (seq control-panel) "control_panel" "None"))]]
           ~@(when include-qc?
               `[[:qc
                  [["tumor"] ~@(when-not tumor-only? [["normal"]])]]])]
         (gen-input-file)))))

(defn gen-rna-input
  ([samples] (gen-rna-input samples {}))
  ([{:keys [r1 r2 control-panel]} {:keys [include-qc?]}]
   (-> `[[:fastq [["rna" ~r1 ~r2]]]
         ~@(gen-controlpanel-config control-panel)
         [:sv-detection
          [["rna" "None" ~(if (seq control-panel) "control_panel" "None")]]]
         [:fusion
          [["rna" ~(if (seq control-panel) "control_panel" "None")]]]
         [:expression [["rna"]]]
         [:intron-retention [["rna"]]]
         ~@(when include-qc?
             [[:qc [["rna"]]]])]
       (gen-input-file))))
