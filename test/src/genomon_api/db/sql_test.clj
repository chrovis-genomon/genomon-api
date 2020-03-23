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

(def ^:const dna-run-id #uuid "bd067680-2670-4b3d-8564-5025ce2b3cc1")
(def ^:const rna-run-id #uuid "883ef3e4-caac-49aa-b956-960856aa632c")
(def ^:const image-id "sha256:bba9d76fd02ee2344ffe807c1aaa4d20171196d6d2f26b46479bb727f4b79b8c")
(def ^:const container-id "d97405db841d5bc685c2079d188ac829119cca142d1154545a69374ce5acc237")

(deftest db-test
  (testing "dna-runs"
    (is (nil? (db/get-dna-run *db* {:run-id dna-run-id})))
    (is (not (nil? (db/create-dna-run *db* {:run-id dna-run-id,
                                            :status :created,
                                            :samples {:normal {:r1 "nr1", :r2 "nr2"},
                                                      :tumor {:r1 "tr1", :r2 "tr2"}},
                                            :output-dir (str "s3://genomon-api/test/" dna-run-id),
                                            :image-id image-id,
                                            :container-id container-id,
                                            :config {}}))))
    (is (= {:run-id dna-run-id,
            :status :created,
            :samples {:normal {:r1 "nr1", :r2 "nr2"},
                      :tumor {:r1 "tr1", :r2 "tr2"}},
            :output-dir (str "s3://genomon-api/test/" dna-run-id),
            :image-id image-id,
            :container-id container-id,
            :config {},
            :results {:normal-bam nil, :tumor-bam nil, :svs nil, :mutations nil}}
           (dissoc (db/get-dna-run *db* {:run-id dna-run-id}) :created-at :updated-at)))
    (is (not (nil? (seq (db/list-dna-runs *db* {:limit 10 :offset 0})))))
    (is (nil? (db/update-dna-run-status *db* {:run-id dna-run-id,
                                              :status :started})))
    (is (= 1 (db/update-dna-run-status *db* {:run-id dna-run-id,
                                             :status :succeeded,
                                             :results {:normal-bam ""
                                                       :tumor-bam ""
                                                       :svs ""
                                                       :mutations ""}})))
    (is (= 1 (db/delete-dna-run *db* {:run-id dna-run-id})))
    (is (nil? (db/get-dna-run *db* {:run-id dna-run-id}))))
  (testing "rna-runs"
    (is (nil? (db/get-rna-run *db* {:run-id rna-run-id})))
    (is (not (nil? (db/create-rna-run *db* {:run-id rna-run-id,
                                            :status :created,
                                            :samples {:r1 "r1", :r2 "r2"},
                                            :output-dir (str "s3://genomon-api/test/" rna-run-id),
                                            :image-id image-id,
                                            :container-id container-id,
                                            :config {}}))))
    (is (= {:run-id rna-run-id,
            :status :created,
            :samples {:r1 "r1", :r2 "r2"},
            :output-dir (str "s3://genomon-api/test/" rna-run-id),
            :image-id image-id,
            :container-id container-id,
            :config {},
            :results {:bam nil, :fusions nil, :expressions nil, :intron-retentions nil}}
           (dissoc (db/get-rna-run *db* {:run-id rna-run-id})
                   :created-at :updated-at)))
    (is (not (nil? (seq (db/list-rna-runs *db* {:limit 10 :offset 0})))))
    (is (nil? (db/update-rna-run-status *db* {:run-id rna-run-id,
                                              :status :started})))
    (is (= 1 (db/update-rna-run-status *db* {:run-id rna-run-id,
                                             :status :succeeded,
                                             :results {:bam "",
                                                       :fusions "",
                                                       :expressions "",
                                                       :intron-retentions ""}})))
    (is (= 1 (db/delete-rna-run *db* {:run-id rna-run-id})))
    (is (nil? (db/get-rna-run *db* {:run-id rna-run-id})))))
