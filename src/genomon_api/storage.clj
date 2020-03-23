(ns genomon-api.storage)

(defprotocol IStorage
  (stat [this url])
  (stream-content [this url])
  (delete-dir [this url]))
