(ns genomon-api.util.tar
  (:require [clojure.java.io :as io])
  (:import [java.util ArrayDeque]
           [java.io
            InputStream ByteArrayOutputStream File]
           [java.nio.file Path Paths Files]
           [java.net URL]
           [org.apache.commons.compress.compressors
            CompressorStreamFactory
            CompressorException]
           [org.apache.commons.compress.compressors.gzip
            GzipCompressorOutputStream]
           [org.apache.commons.compress.archivers.tar
            TarArchiveEntry
            TarArchiveInputStream
            TarArchiveOutputStream]))

(def ^:private ^:const buffer-size (* 1024 1024))

(defprotocol IArchiveEntryTransferable
  (transfer-entry [this entry taos]))

(extend-protocol IArchiveEntryTransferable
  String
  (transfer-entry [this ^TarArchiveEntry entry ^TarArchiveOutputStream taos]
    (.setSize entry (.length this))
    (.putArchiveEntry taos entry)
    (.write taos (.getBytes this))
    (.closeArchiveEntry taos))
  InputStream
  (transfer-entry [this ^TarArchiveEntry entry ^TarArchiveOutputStream taos]
    (let [xs (ArrayDeque. 1)]
      (loop [total 0]
        (let [buf (byte-array buffer-size)
              len (.read this buf)]
          (if (neg? len)
            (do
              (.setSize entry total)
              (.putArchiveEntry taos entry)
              (dotimes [_ (.size xs)]
                (let [[l x] (.poll xs)]
                  (.write taos x 0 l)))
              (.closeArchiveEntry taos))
            (do
              (when (pos? len)
                (.add xs [len buf]))
              (recur (+ total len))))))))
  File
  (transfer-entry [this ^TarArchiveEntry entry ^TarArchiveOutputStream taos]
    (transfer-entry (.toPath this) entry taos))
  Path
  (transfer-entry [this ^TarArchiveEntry entry ^TarArchiveOutputStream taos]
    (.setSize entry (Files/size this))
    (.putArchiveEntry taos entry)
    (Files/copy this taos)
    (.closeArchiveEntry taos))
  URL
  (transfer-entry [this entry taos]
    (if (= "file" (.getProtocol this))
      (transfer-entry (Paths/get (.toURI this)) entry taos)
      (with-open [is (.openStream this)]
        (transfer-entry is entry taos)))))

;; HACK: `extend-protocol` fails to expand (Class/forName "[B") as a class
(extend-type (Class/forName "[B")
  IArchiveEntryTransferable
  (transfer-entry [this ^TarArchiveEntry entry ^TarArchiveOutputStream taos]
    (.setSize entry (alength ^bytes this))
    (.putArchiveEntry taos entry)
    (.write taos ^bytes this)
    (.closeArchiveEntry taos)))

(defn tar ^bytes [m]
  (with-open [baos (ByteArrayOutputStream.)]
    (with-open [gcos (GzipCompressorOutputStream. baos)
                taos (TarArchiveOutputStream. gcos)]
      (.setLongFileMode taos TarArchiveOutputStream/LONGFILE_GNU)
      (doseq [[k v] m
              :let [e (TarArchiveEntry. ^String k)]]
        (transfer-entry v e taos)))
    (.flush baos)
    (.toByteArray baos)))

(defn- compressor-input-stream ^InputStream [in]
  (let [is (io/input-stream in)]
    (try
      (.. (CompressorStreamFactory. true)
          (createCompressorInputStream is))
      (catch CompressorException _
        is))))

(defn untar [in]
  (with-open [cis (compressor-input-stream in)
              tais (TarArchiveInputStream. cis)]
    (->> #(when-let [e (.getNextTarEntry tais)]
            (if (and (not (.isDirectory e))
                     (.canReadEntryData tais e))
              (let [buf (byte-array (.getSize e))]
                (.read tais buf)
                [(.getName e) buf])
              []))
         repeatedly
         (into {} (comp (take-while identity) (filter seq))))))
