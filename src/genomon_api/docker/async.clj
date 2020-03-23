(ns genomon-api.docker.async
  (:require [clojure.core.async :as a]
            [genomon-api.docker :as d]
            [genomon-api.docker.core :as core])
  (:import [genomon_api.docker.core Boundary]))

(defn wait-async [client container-id ch]
  (let [m {:container-id container-id}
        cbs {:on-start (fn [_]
                         (a/go (a/>! ch (assoc m :status :started)))),
             :on-next (fn [log]
                        (a/go (a/>! ch (assoc m :status :running :log log))))}]
    (a/thread
      (try
        (let [status (core/wait client container-id cbs)]
          (when-not (zero? status)
            (throw (ex-info "Exited with non-zero status" {:status status})))
          (a/>!! ch (assoc m :status :succeeded :code status))
          (a/close! ch))
        (catch Throwable e
          (a/>!! ch (assoc m :status :failed :error e))
          (a/close! ch))))
    m))

(defn run-async
  [client image ch opts]
  (let [container-id (core/create client image opts)]
    (core/start-container client container-id)
    (assoc (wait-async client container-id ch) :status :created)))

(extend-type Boundary
  d/IDockerAsync
  (run-async [this image ch opts]
    (run-async (:client this) image ch opts))
  (wait-async [this id ch]
    (wait-async (:client this) id ch)))
