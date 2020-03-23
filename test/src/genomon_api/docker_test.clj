(ns genomon-api.docker-test
  (:require [clojure.test :refer :all]
            [genomon-api.docker.core :as d])
  (:import [java.time Instant Clock ZoneOffset]
           [com.github.dockerjava.api.model Frame StreamType]
           [com.github.dockerjava.api.exception NotFoundException]))

(deftest parse-image-str-test
  (are [?str] (nil? (@#'d/parse-image-str ?str))
    ":443/testing/test-image:latest"
    "testing/:latest")
  (are [?str ?map] (= ?map (@#'d/parse-image-str ?str))
    "myregistry.local:5000/testing/test-image:latest"
    {:registry "myregistry.local", :port 5000,
     :namespace "testing", :image "test-image", :tag "latest"}

    "myregistry.local:5000/testing/test-image"
    {:registry "myregistry.local", :port 5000,
     :namespace "testing", :image "test-image", :tag nil}

    "myregistry.local/testing/test-image"
    {:registry "myregistry.local", :port nil,
     :namespace "testing", :image "test-image", :tag nil}

    "myregistry.local/test-image:latest"
    {:registry "myregistry.local", :port nil,
     :namespace nil, :image "test-image", :tag "latest"}

    "myregistry.local/test-image"
    {:registry "myregistry.local", :port nil,
     :namespace nil, :image "test-image", :tag nil}

    "localhost/test-image"
    {:registry "localhost", :port nil,
     :namespace nil, :image "test-image", :tag nil}

    "testing/test-image"
    {:registry nil, :port nil,
     :namespace "testing", :image "test-image", :tag nil}

    "test-image:latest"
    {:registry nil, :port nil,
     :namespace nil, :image "test-image", :tag "latest"}))

(deftest image->map-test
  (are [?str ?map] (= ?map (@#'d/image->map ?str))
    "myregistry.local:5000/testing/test-image:latest"
    {:registry "myregistry.local", :port 5000,
     :namespace "testing", :image "test-image", :tag "latest"}

    "test-image"
    {:registry "index.docker.io", :port 443, :namespace "library",
     :image "test-image", :tag "latest"}

    ":443/testing/test-image" nil))

(deftest ->log-line-test
  (let [now (Instant/now)]
    (are [?timestamps? ?item ?expected]
         (= ?expected (binding [d/*clock* (Clock/fixed now ZoneOffset/UTC)]
                        (@#'d/->log-line ?timestamps? ?item)))
      true
      (Frame. StreamType/STDOUT
              (.getBytes "2020-01-15T06:47:52.137219800Z foobar\n"))
      {:type :stdout,
       :timestamp (Instant/parse "2020-01-15T06:47:52.137219800Z"),
       :body "foobar\n"}

      false
      (Frame. StreamType/STDERR (.getBytes "foobar\n"))
      {:type :stderr, :timestamp now, :body "foobar\n"})))

(deftest ^:integration smoke-test
  (with-open [c (d/build-client {:read-timeout 10000})]
    (is (map? (d/info c)))
    (is (seq? (d/list-containers c {:show-all? true,
                                    :label-filter {:requester "genomon-api"}})))
    (is (= 1 (count (d/list-images c {:image-name-filter "busybox:latest"}))))
    (is (map? (d/prep-image c "busybox" {:tag "latest"})))
    (let [id (d/create c "busybox" {:entrypoint ["/bin/sh" "-euCc"]
                                    :command ["echo foo; sleep 2; echo bar"]})
          logs (atom [])]
      (is (map? (d/inspect-container c id {})))
      (is (nil? (d/start-container c id)))
      (is (zero? (d/wait c id {:on-next
                               (fn [x]
                                 (swap! logs conj (dissoc x :timestamp)))})))
      (is (= [{:container-id id, :type :stdout, :body "foo\n"}
              {:container-id id, :type :stdout, :body "bar\n"}]
             @logs))
      (is (thrown? NotFoundException (d/inspect-container c id {}))
          "container not removed after exited"))))
