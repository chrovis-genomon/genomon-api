(ns genomon-api.aws
  (:require [integrant.core :as ig]
            [cognitect.anomalies :as anomalies]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [ring.util.http-response :as hr]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as csk-ex]
            [genomon-api.storage :as storage])
  (:import [java.util Date]
           [cognitect.aws.client Client]))

(defn- ->edn [response]
  (-> (csk-ex/transform-keys csk/->kebab-case-keyword response)
      (update :last-modified #(.toInstant ^Date %))))

(defmethod ig/init-key ::profile-credentials-provider [_ {:keys [profile-name]}]
  (credentials/profile-credentials-provider profile-name))

(defmethod ig/init-key ::client [_ {:keys [credentials-provider]}]
  (cond-> {:api :s3}
    credentials-provider (assoc :credentials-provider credentials-provider)
    true aws/client))

(defn- match-s3-url [s]
  (if-let [[_ bucket key] (re-matches #"s3://([^/]+)/(.+)" s)]
    {:bucket bucket, :key key}
    ;; TODO: organize exceptions
    (hr/bad-request! {:error {:message "Requiere a S3 url (s3://bucket/key)",
                              :url s}})))

(defn- head-object [aws url]
  (let [{:keys [bucket key] :as m} (match-s3-url url)]
    (let [head (aws/invoke aws {:op :HeadObject,
                                :request {:Bucket bucket, :Key key}})]
      (if (::anomalies/category head)
        (hr/bad-request! {:error {:message "Requested resource not found",
                                  :url url}})
        (-> (->edn head) (assoc :url url) (merge m))))))

(defn- list-objects [aws bucket prefix]
  (->> {:IsTruncated true}
       (iterate
        (fn [{:keys [IsTruncated NextContinuationToken]}]
          (when IsTruncated
            (aws/invoke
             aws
             {:op :ListObjectsV2,
              :request (-> (if NextContinuationToken
                             {:ContinuationToken NextContinuationToken}
                             {:Prefix prefix})
                           (assoc :Bucket bucket))}))))
       (take-while some?)
       (mapcat :Contents)
       (map ->edn)))

(defn- get-object [aws url]
  (let [{:keys [bucket key] :as m} (match-s3-url url)]
    (let [resp (aws/invoke aws {:op :GetObject,
                                :request {:Bucket bucket, :Key key}})]
      (if (::anomalies/category resp)
        (hr/bad-request! {:error {:message "Requested resource not found",
                                  :url url}})
        (-> (->edn resp) (assoc :url url) (merge m))))))

(defn- delete-objects [aws url]
  (let [{:keys [bucket key]} (match-s3-url url)
        objects (doall (list-objects aws bucket key))
        delete (map (fn [{:keys [key]}] {:Key key}) objects)]
    (doseq [xs (partition-all 1000 delete)]
      (when-let [resp (not-empty
                       (aws/invoke aws {:op :DeleteObjects
                                        :request {:Bucket bucket,
                                                  :Delete {:Objects xs,
                                                           :Quiet true}}}))]
        (throw (ex-info "Failed to delete objects"
                        {:keys xs, :error resp}))))))

(extend-type Client
  storage/IStorage
  (stat [this url]
    (head-object this url))
  (stream-content [this url]
    (get-object this url))
  (delete-dir [this url]
    (delete-objects this url)))
