(ns genomon-api-dev.db.sql
  (:require [clojure.java.jdbc :as jdbc])
  (:import [org.h2.jdbc JdbcClob]))

(extend-type JdbcClob
  jdbc/IResultSetReadColumn
  (result-set-read-column [clob _ _]
    (let [])
    (with-open [r (.getCharacterStream clob)
                w (java.io.StringWriter.)]
      (.transferTo r w)
      (.flush w)
      (.toString w))))
