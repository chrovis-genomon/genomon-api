(ns genomon-api.db.sql-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [duct.core :as duct]
            [duct.database.sql]
            [duct.database.sql.hikaricp :as hikari]
            [genomon-api.test-common :as common]
            [genomon-api.db :as db]
            [genomon-api.db.sql :as sql]
            [genomon-api-dev.db.sql]))

(def ^:dynamic *db*)

(defn- db-fixture [f]
  (binding [*db* (val (ig/find-derived-1 common/*system* :duct.database/sql))]
    (f)))

(use-fixtures :once
  (partial common/system-fixture [:duct.database/sql
                                  :genomon-api.executor.genomon-pipeline-cloud.config/dna
                                  :genomon-api.executor.genomon-pipeline-cloud.config/dna])
  db-fixture)

(def ^:const dna-run-id1 #uuid "bd067680-2670-4b3d-8564-5025ce2b3cc1")
(def ^:const dna-run-id2 #uuid "dc2a3897-6b1d-49e5-b506-da33ce1ec8f9")
(def ^:const dna-run-id3 #uuid "5ac065c5-4384-4e83-a862-8d6e8eb59bab")
(def ^:const rna-run-id1 #uuid "883ef3e4-caac-49aa-b956-960856aa632c")
(def ^:const rna-run-id2 #uuid "8525b74e-a35c-421b-9344-197e88098e48")
(def ^:const image-id "sha256:bba9d76fd02ee2344ffe807c1aaa4d20171196d6d2f26b46479bb727f4b79b8c")
(def ^:const container-id "d97405db841d5bc685c2079d188ac829119cca142d1154545a69374ce5acc237")

(deftest db-test
  (testing "dna-runs"
    (is (nil? (db/get-dna-run *db* {:run-id dna-run-id1})))
    (is (not (nil? (db/create-dna-run *db* {:run-id dna-run-id1,
                                            :status :created,
                                            :samples {:normal {:r1 "nr1", :r2 "nr2"},
                                                      :tumor {:r1 "tr1", :r2 "tr2"},
                                                      :control-panel nil},
                                            :output-dir (str "s3://genomon-api/test/" dna-run-id1),
                                            :image-id image-id,
                                            :container-id container-id,
                                            :config {}}))))
    (is (= {:run-id dna-run-id1,
            :status :created,
            :samples {:normal {:r1 "nr1", :r2 "nr2"},
                      :tumor {:r1 "tr1", :r2 "tr2"}
                      :control-panel nil},
            :output-dir (str "s3://genomon-api/test/" dna-run-id1),
            :image-id image-id,
            :container-id container-id,
            :config {},
            :results {:normal-bam nil, :tumor-bam nil, :svs nil, :mutations nil}}
           (dissoc (db/get-dna-run *db* {:run-id dna-run-id1}) :created-at :updated-at)))
    (is (not (nil? (seq (db/list-dna-runs *db* {:limit 10 :offset 0})))))
    (is (nil? (db/update-dna-run-status *db* {:run-id dna-run-id1,
                                              :status :started})))
    (is (= 1 (db/update-dna-run-status *db* {:run-id dna-run-id1,
                                             :status :succeeded,
                                             :results {:normal-bam ""
                                                       :tumor-bam ""
                                                       :svs ""
                                                       :mutations ""}})))
    (is (= 1 (db/delete-dna-run *db* {:run-id dna-run-id1})))
    (is (nil? (db/get-dna-run *db* {:run-id dna-run-id1}))))
  (testing "dna-runs without normal sample"
    (is (nil? (db/get-dna-run *db* {:run-id dna-run-id2})))
    (is (not (nil? (db/create-dna-run *db* {:run-id dna-run-id2,
                                            :status :created,
                                            :samples {:normal {:r1 nil, :r2 nil},
                                                      :tumor {:r1 "tr1", :r2 "tr2"},
                                                      :control-panel nil},
                                            :output-dir (str "s3://genomon-api/test/" dna-run-id2),
                                            :image-id image-id,
                                            :container-id container-id,
                                            :config {}}))))
    (is (= {:run-id dna-run-id2,
            :status :created,
            :samples {:normal {:r1 nil, :r2 nil},
                      :tumor {:r1 "tr1", :r2 "tr2"},
                      :control-panel nil},
            :output-dir (str "s3://genomon-api/test/" dna-run-id2),
            :image-id image-id,
            :container-id container-id,
            :config {},
            :results {:normal-bam nil, :tumor-bam nil, :svs nil, :mutations nil}}
           (dissoc (db/get-dna-run *db* {:run-id dna-run-id2}) :created-at :updated-at)))
    (is (not (nil? (seq (db/list-dna-runs *db* {:limit 10 :offset 0})))))
    (is (nil? (db/update-dna-run-status *db* {:run-id dna-run-id2,
                                              :status :started})))
    (is (= 1 (db/update-dna-run-status *db* {:run-id dna-run-id2,
                                             :status :succeeded,
                                             :results {:normal-bam nil
                                                       :tumor-bam ""
                                                       :svs ""
                                                       :mutations ""}})))
    (is (= 1 (db/delete-dna-run *db* {:run-id dna-run-id2})))
    (is (nil? (db/get-dna-run *db* {:run-id dna-run-id2}))))
  (testing "dna-runs with control panel"
    (is (nil? (db/get-dna-run *db* {:run-id dna-run-id3})))
    (is (not (nil? (db/create-dna-run *db* {:run-id dna-run-id3,
                                            :status :created,
                                            :samples {:normal {:r1 nil, :r2 nil},
                                                      :tumor {:r1 "tr1", :r2 "tr2"},
                                                      :control-panel ["bam1" "bam2"]},
                                            :output-dir (str "s3://genomon-api/test/" dna-run-id3),
                                            :image-id image-id,
                                            :container-id container-id,
                                            :config {}}))))
    (is (= {:run-id dna-run-id3,
            :status :created,
            :samples {:normal {:r1 nil, :r2 nil},
                      :tumor {:r1 "tr1", :r2 "tr2"},
                      :control-panel ["bam1" "bam2"]},
            :output-dir (str "s3://genomon-api/test/" dna-run-id3),
            :image-id image-id,
            :container-id container-id,
            :config {},
            :results {:normal-bam nil, :tumor-bam nil, :svs nil, :mutations nil}}
           (dissoc (db/get-dna-run *db* {:run-id dna-run-id3}) :created-at :updated-at)))
    (is (not (nil? (seq (db/list-dna-runs *db* {:limit 10 :offset 0})))))
    (is (nil? (db/update-dna-run-status *db* {:run-id dna-run-id3,
                                              :status :started})))
    (is (= 1 (db/update-dna-run-status *db* {:run-id dna-run-id3,
                                             :status :succeeded,
                                             :results {:normal-bam nil
                                                       :tumor-bam ""
                                                       :svs ""
                                                       :mutations ""}})))
    (is (= 1 (db/delete-dna-run *db* {:run-id dna-run-id3})))
    (is (nil? (db/get-dna-run *db* {:run-id dna-run-id3}))))
  (testing "rna-runs"
    (is (nil? (db/get-rna-run *db* {:run-id rna-run-id1})))
    (is (not (nil? (db/create-rna-run *db* {:run-id rna-run-id1,
                                            :status :created,
                                            :samples {:r1 "r1", :r2 "r2", :control-panel nil},
                                            :output-dir (str "s3://genomon-api/test/" rna-run-id1),
                                            :image-id image-id,
                                            :container-id container-id,
                                            :config {}}))))
    (is (= {:run-id rna-run-id1,
            :status :created,
            :samples {:r1 "r1", :r2 "r2", :control-panel nil},
            :output-dir (str "s3://genomon-api/test/" rna-run-id1),
            :image-id image-id,
            :container-id container-id,
            :config {},
            :results {:bam nil, :fusions nil, :expressions nil, :intron-retentions nil}}
           (dissoc (db/get-rna-run *db* {:run-id rna-run-id1})
                   :created-at :updated-at)))
    (is (not (nil? (seq (db/list-rna-runs *db* {:limit 10 :offset 0})))))
    (is (nil? (db/update-rna-run-status *db* {:run-id rna-run-id1,
                                              :status :started})))
    (is (= 1 (db/update-rna-run-status *db* {:run-id rna-run-id1,
                                             :status :succeeded,
                                             :results {:bam "",
                                                       :fusions "",
                                                       :expressions "",
                                                       :intron-retentions ""}})))
    (is (= 1 (db/delete-rna-run *db* {:run-id rna-run-id1})))
    (is (nil? (db/get-rna-run *db* {:run-id rna-run-id1}))))
  (testing "rna-runs with control panel"
    (is (nil? (db/get-rna-run *db* {:run-id rna-run-id2})))
    (is (not (nil? (db/create-rna-run *db* {:run-id rna-run-id2,
                                            :status :created,
                                            :samples {:r1 "r1", :r2 "r2",
                                                      :control-panel ["bam1" "bam2"]},
                                            :output-dir (str "s3://genomon-api/test/" rna-run-id2),
                                            :image-id image-id,
                                            :container-id container-id,
                                            :config {}}))))
    (is (= {:run-id rna-run-id2,
            :status :created,
            :samples {:r1 "r1", :r2 "r2", :control-panel ["bam1" "bam2"]},
            :output-dir (str "s3://genomon-api/test/" rna-run-id2),
            :image-id image-id,
            :container-id container-id,
            :config {},
            :results {:bam nil, :fusions nil, :expressions nil, :intron-retentions nil}}
           (dissoc (db/get-rna-run *db* {:run-id rna-run-id2})
                   :created-at :updated-at)))
    (is (not (nil? (seq (db/list-rna-runs *db* {:limit 10 :offset 0})))))
    (is (nil? (db/update-rna-run-status *db* {:run-id rna-run-id2,
                                              :status :started})))
    (is (= 1 (db/update-rna-run-status *db* {:run-id rna-run-id2,
                                             :status :succeeded,
                                             :results {:bam "",
                                                       :fusions "",
                                                       :expressions "",
                                                       :intron-retentions ""}})))
    (is (= 1 (db/delete-rna-run *db* {:run-id rna-run-id2})))
    (is (nil? (db/get-rna-run *db* {:run-id rna-run-id2})))))
