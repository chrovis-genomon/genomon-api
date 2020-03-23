(ns genomon-api.util.tar-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [genomon-api.util.tar :as tar])
  (:import [java.util Arrays]
           [java.io ByteArrayInputStream]
           [java.nio.file Paths]))

(deftest tar-untar-test
  (let [size (* 3 1024 1024)
        element (byte 16)]
    (with-open [bais-short (ByteArrayInputStream. (.getBytes "short"))
                bais-long (ByteArrayInputStream.
                           (byte-array (repeat size element)))]
      (let [in {"foo/str.txt" "foo bar",
                "bytes.dat" (byte-array [0 1 2 3]),
                "project.clj" (io/file "project.clj"),
                "url.edn" (io/resource "genomon_api/config.edn"),
                "path.md" (Paths/get "README.md" (into-array String [])),
                "short.txt" bais-short,
                "long.bin" bais-long}
            tar (tar/tar in)
            out (tar/untar tar)]
        (is (= (map unchecked-byte [0x1f 0x8b 0x08 0x00])
               (take 4 tar)))
        (is (= (keys in) (keys out)))
        (is (= (in "foo/str.txt")
               (String. ^bytes (out "foo/str.txt"))))
        (is (= [0 1 2 3] (seq (out "bytes.dat"))))
        (is (= (slurp "project.clj")
               (String. ^bytes (out "project.clj"))))
        (is (= (slurp (io/resource "genomon_api/config.edn"))
               (String. ^bytes (out "url.edn"))))
        (is (= (slurp "README.md")
               (String. ^bytes (out "path.md"))))
        (is (= "short"
               (String. ^bytes (out "short.txt"))))
        (let [arr (byte-array size)]
          (Arrays/fill arr element)
          (is (Arrays/equals arr ^bytes (out "long.bin"))))))))
