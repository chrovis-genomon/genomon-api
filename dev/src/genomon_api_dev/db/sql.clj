(ns genomon-api-dev.db.sql
  (:require [clojure.java.jdbc :as jdbc])
  (:import [org.h2.jdbc JdbcClob]
           [java.sql Timestamp]))

(extend-type JdbcClob
  jdbc/IResultSetReadColumn
  (result-set-read-column [clob _ _]
    (with-open [r (.getCharacterStream clob)
                w (java.io.StringWriter.)]
      (.transferTo r w)
      (.flush w)
      (.toString w))))

(extend-type Timestamp
  jdbc/IResultSetReadColumn
  (result-set-read-column [ts _ _]
    (.toLocalDateTime ts)))
