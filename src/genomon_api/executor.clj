(ns genomon-api.executor)

(defprotocol IExecutor
  (run-dna-pipeline [this run-id config samples])
  (interrupt-dna-pipeline [this run-id])
  (run-rna-pipeline [this run-id config samples])
  (interrupt-rna-pipeline [this run-id]))
