(ns genomon-api.db)

(defprotocol IDNARunDB
  (create-dna-run [this run])
  (update-dna-run-status [this {:keys [run-id status]}])
  (list-dna-runs [this {:keys [limit offset]}])
  (get-dna-run [this {:keys [run-id]}])
  (delete-dna-run [this {:keys [run-id]}]))

(defprotocol IRNARunDB
  (create-rna-run [this run])
  (update-rna-run-status [this {:keys [run-id status]}])
  (list-rna-runs [this {:keys [limit offset]}])
  (get-rna-run [this {:keys [run-id]}])
  (delete-rna-run [this {:keys [run-id]}]))
