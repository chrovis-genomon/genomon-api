(ns genomon-api.util.ini
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [instaparse.core :as insta]
            [flatland.ordered.map :as om]
            [camel-snake-kebab.core :as csk]))

;; TODO: support indented multi-line values
(insta/defparser ini
  "
ini = section*
section = <'['> header <']'> <'\n'> ((kv? | <comment>) <'\n'>)*
header = #\"[^\\[\\]\n]+\"
kv = key <' '*> <'='> <' '*> value? <' '*>
key = #\"\\S[^\\n\\=\\s]*\"
value = #\"\\S[^\\n]*\"
comment = #\"[#;][^\\n]*\"
")

(defn- transform-keys
  [t coll]
  (letfn [(transform [[k v]] [(t k) v])
          (walk [x] (if (map? x) (into (om/ordered-map) (map transform x)) x))]
    (walk/postwalk walk coll)))

(defn parse-ini [s]
  (let [m (ini s)]
    (if (insta/failure? m)
      (throw (ex-info "Failed to parse ini" {:error (insta/get-failure m)}))
      (transform-keys
       csk/->kebab-case-keyword
       (into (om/ordered-map)
             (for [[_ [_ header] & kvs] (rest m)]
               [header (into (om/ordered-map)
                             (for [[_ [_ k] [_ v]] kvs] [k v]))]))))))

(defn gen-ini [m]
  (->> (for [[section kvs] (transform-keys csk/->snake_case_string m)]
         (cons (str \[ section \]) (for [[k v] kvs] (str k " = " v))))
       (apply concat)
       (str/join \newline)))
