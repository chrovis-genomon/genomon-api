(ns genomon-api.db.sql
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [integrant.core :as ig]
            [medley.core :as medley]
            [duct.core :as duct]
            [duct.database.sql]
            [hugsql.core :as hugsql]
            [jsonista.core :as json]
            [resauce.core :as resauce]
            [genomon-api.db :as db]
            [camel-snake-kebab.extras :as csk-ex])
  (:import [java.nio ByteBuffer]
           [java.util UUID]
           [java.sql Timestamp]
           [java.time Instant LocalDateTime ZoneId]))

(defn- uuid->bin ^bytes [^UUID uuid]
  (let [bb (ByteBuffer/allocate 16)
        lb (.asLongBuffer bb)]
    (.put lb (.getMostSignificantBits uuid))
    (.put lb (.getLeastSignificantBits uuid))
    (.array bb)))

(defn- bin->uuid ^UUID [^bytes b]
  (let [bb (ByteBuffer/wrap b)
        lb (.asLongBuffer bb)
        msb (.get lb)
        lsb (.get lb)]
    (UUID. msb lsb)))

(defn- ts->inst ^Instant [^LocalDateTime ldt]
  (.toInstant (.atZone ldt (ZoneId/systemDefault))))

(defn- inst->ts ^Timestamp [^Instant i]
  (Timestamp/from i))

(defn- ->json-str [x]
  (json/write-value-as-string x))

(defn- json-str-> [x]
  (csk-ex/transform-keys keyword (json/read-value x)))

(defn- rename-keys-in [keymap map]
  (reduce-kv
   (fn [r ks ks']
     (let [v (get-in r ks ::not-found)]
       (if-not (identical? ::not-found v)
         (cond-> (medley/dissoc-in r ks)
           (or (some? v) (not (:ignore-if-nil (meta ks))))
           (assoc-in ks' v))
         r)))
   map
   keymap))

(def ^:private statuses
  #{:created :started :running :interrupted :succeeded :failed})

(defn- parser [m] (fn [x] (reduce-kv update x m)))

(defn- unparser [m] (fn [x] (reduce-kv medley/update-existing x m)))

(def ^:private run-unparser
  {:run-id uuid->bin,
   :created-at inst->ts,
   :updated-at inst->ts,
   :status (comp name statuses),
   :control-panel ->json-str,
   :config ->json-str})

(def ^:privat run-parser
  {:run-id bin->uuid,
   :created-at ts->inst,
   :updated-at ts->inst,
   :status keyword,
   :control-panel json-str->,
   :config json-str->})

(hugsql/def-db-fns "hugsql/runs.sql")

;; DNA

(def ^:private dna-samples-key
  {^:ignore-if-nil
   [:normal-r1] [:samples :normal :r1]
   ^:ignore-if-nil
   [:normal-r2] [:samples :normal :r2]
   ^:ignore-if-nil
   [:tumor-r1] [:samples :tumor :r1]
   ^:ignore-if-nil
   [:tumor-r2] [:samples :tumor :r2]
   ^:ignore-if-nil
   [:control-panel] [:samples :control-panel]
   [:normal-bam] [:results :normal-bam]
   [:tumor-bam] [:results :tumor-bam]
   [:mutations] [:results :mutations]
   [:svs] [:results :svs]})

(def ^:private parse-dna
  (comp (partial rename-keys-in dna-samples-key)
        (parser run-parser)))

(def ^:private unparse-dna
  (comp (unparser run-unparser)
        (partial rename-keys-in (set/map-invert dna-samples-key))))

(hugsql/def-db-fns "hugsql/dna_runs.sql")

(extend-type duct.database.sql.Boundary
  db/IDNARunDB
  (create-dna-run [{:keys [spec]} run]
    (jdbc/with-db-transaction [tx spec]
      (let [run (cond-> (merge {:normal-r1 nil, :normal-r2 nil
                                :tumor-r1 nil, :tumor-r2 nil,
                                :control-panel nil} (unparse-dna run))
                  (nil? (get-in run [:samples :normal :bam]))
                  (assoc :normal-bam nil)

                  (nil? (get-in run [:samples :tumor :bam]))
                  (assoc :tumor-bam nil))]
        (_insert-run! tx run)
        (_insert-dna-run! tx run))))
  (update-dna-run-status [{:keys [spec]} {:keys [status] :as run}]
    (let [run (unparse-dna run)]
      (jdbc/with-db-transaction [tx spec]
        (when-not (= :running status) ;; TODO: sub-tasks
          (_update-run-status! tx run))
        (when (= :succeeded status)
          (_update-dna-results! tx run)))))
  (list-dna-runs [{:keys [spec]} opts]
    (_list-dna-runs spec opts {} {:row-fn parse-dna}))
  (get-dna-run [{:keys [spec]} run]
    (_get-dna-run spec (unparse-dna run) {} {:row-fn parse-dna}))
  (delete-dna-run [{:keys [spec]} run]
    (_delete-run! spec (unparse-dna run))))

;; RNA

(def ^:private rna-samples-key
  {[:r1] [:samples :r1]
   [:r2] [:samples :r2]
   ^:ignore-if-nil
   [:control-panel] [:samples :control-panel]
   [:bam] [:results :bam]
   [:fusions] [:results :fusions]
   [:expressions] [:results :expressions]
   [:intron-retentions] [:results :intron-retentions]
   [:svs] [:results :svs]})

(def ^:private parse-rna
  (comp (partial rename-keys-in rna-samples-key)
        (parser run-parser)))

(def ^:private unparse-rna
  (comp (unparser run-unparser)
        (partial rename-keys-in (set/map-invert rna-samples-key))))

(hugsql/def-db-fns "hugsql/rna_runs.sql")

(extend-type duct.database.sql.Boundary
  db/IRNARunDB
  (create-rna-run [{:keys [spec]} run]
    (jdbc/with-db-transaction [tx spec]
      (let [run (->> (unparse-rna run)
                     (merge {:control-panel nil}))]
        (_insert-run! tx run)
        (_insert-rna-run! tx run))))
  (update-rna-run-status [{:keys [spec]} {:keys [status] :as run}]
    (let [run (unparse-rna run)]
      (jdbc/with-db-transaction [tx spec]
        (when-not (= :running status) ;; TODO: sub-tasks
          (_update-run-status! tx run))
        (when (= :succeeded status)
          (_update-rna-results! tx run)))))
  (list-rna-runs [{:keys [spec]} opts]
    (_list-rna-runs spec opts {} {:row-fn parse-rna}))
  (get-rna-run [{:keys [spec]} run]
    (_get-rna-run spec (unparse-rna run) {} {:row-fn parse-rna}))
  (delete-rna-run [{:keys [spec]} run]
    (_delete-run! spec (unparse-rna run))))

;;;; Module

(defn- migrations [dir]
  (let [file (str (io/file dir))
        uri (str (.normalize (.toURI (io/resource dir))))
        _ (assert (str/ends-with? uri file))
        base (subs uri 0 (- (count uri) (count file)))]
    (->> dir
         resauce/resource-dir
         (map (fn [x]
                (let [s (str (.normalize (.toURI (io/as-url x))))]
                  (assert (str/starts-with? s base))
                  (subs s (count base)))))
         (group-by (partial re-find #"[^/.]+(?=[^/]+$)"))
         (sort-by key)
         (map
          (fn [[k vs]]
            (->> vs
                 (group-by
                  (comp keyword
                        (partial re-find #"(?:up|down)(?=\.sql$)")))
                 (medley/map-vals (partial map duct/resource))
                 (vector [:duct.migrator.ragtime/sql
                          (keyword (namespace ::x) k)])))))))

(defmethod ig/init-key ::module [_ {:keys [migration-dir]}]
  (let [migrations (migrations migration-dir)
        ragtime {:database (ig/ref :duct.database/sql),
                 :logger (ig/ref :duct/logger),
                 :strategy :rebase,
                 :migrations-table "ragtime_migrations",
                 :migrations (map (comp ig/ref second first) migrations)}]
    #(duct/merge-configs % {:duct.migrator/ragtime ragtime} (into {} migrations))))
