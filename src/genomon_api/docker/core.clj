(ns genomon-api.docker.core
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [camel-snake-kebab.core :as csk]
            [integrant.core :as ig]
            [genomon-api.docker :as d]
            [genomon-api.util.tar :as tar])
  (:import [java.util List Map]
           [java.io ByteArrayInputStream]
           [java.time Instant Clock]
           [com.github.dockerjava.core
            DefaultDockerClientConfig
            DockerClientBuilder]
           [com.github.dockerjava.core.command
            PullImageResultCallback
            WaitContainerResultCallback
            LogContainerResultCallback]
           [com.github.dockerjava.api DockerClient]
           [com.github.dockerjava.api.command
            CreateContainerCmd]
           [com.github.dockerjava.api.model
            Volume Bind AccessMode Frame AuthConfig
            ExposedPort Ports Ports$Binding WaitResponse]
           [com.github.dockerjava.jaxrs JerseyDockerCmdExecFactory]
           [com.fasterxml.jackson.databind ObjectMapper SerializationFeature]))

(defmacro typed-proxy-super [cls method & args]
  (let [thissym (with-meta (gensym) {:tag cls})]
    `(let [~thissym ~'this]
       (proxy-call-with-super
        (fn [] (. ~thissym ~method ~@args)) ~thissym ~(name method)))))

(defn- ->map [model]
  (->> java.util.Map
       (.convertValue
        (doto (ObjectMapper.)
          (.configure SerializationFeature/FAIL_ON_EMPTY_BEANS false))
        model)
       (walk/prewalk
        (fn [x]
          (cond->> x
            (instance? java.util.Map x)
            (into
             {}
             (map (fn [[k v]]
                    [(cond-> k
                       (string? k)
                       (#(keyword nil (csk/->kebab-case-string %)))) v])))

            (instance? java.util.List x)
            (into []))))))

(defn- ->str-map [m]
  (into {} (map (fn [[k v]] [(name k) (str v)])) m))

(defn build-client
  (^DockerClient [] (build-client {}))
  (^DockerClient
   [{:keys [docker-host read-timeout connect-timeout
            max-total-connections max-per-route-connections]
     :or {docker-host "unix:///var/run/docker.sock",
          read-timeout 1000, connect-timeout 1000,
          max-total-connections 100, max-per-route-connections 10}}]
   (let [cfg (.. (DefaultDockerClientConfig/createDefaultConfigBuilder)
                 (withDockerHost docker-host)
                 (build))
         f (.. (JerseyDockerCmdExecFactory.)
               (withReadTimeout (some-> read-timeout int))
               (withConnectTimeout (some-> connect-timeout int))
               (withMaxTotalConnections (some-> max-total-connections int))
               (withMaxPerRouteConnections
                (some-> max-per-route-connections int)))]
     (.. (DockerClientBuilder/getInstance cfg)
         (withDockerCmdExecFactory f)
         build))))

(defn close [^DockerClient client] (.close client))

(defn info [^DockerClient client]
  (->map (.exec (.infoCmd client))))

(def ^:private ^:const image-regexp
  #"(?:([^:]+?\.[^:]+?|localhost)(?::(\d+))?/)?(?:([^/]+)/)?([^:/]+)(?::(.+))?")

(defn- parse-image-str [s]
  (let [m (some->> s
                   (re-matches image-regexp)
                   rest
                   (zipmap [:registry :port :namespace :image :tag]))]
    (cond-> m
      (:port m) (update :port #(Long/parseLong %)))))

(def ^:private ^:const default-image-namespace "library")
(def ^:private ^:const default-registry "index.docker.io")
(def ^:private ^:const default-registry-port 443)
(def ^:private ^:const default-tag "latest")

(defn- image->map [s]
  (some->> s
           parse-image-str
           (reduce-kv
            (fn [r k v] (cond-> r v (assoc k v)))
            {:namespace default-image-namespace,
             :registry default-registry
             :port default-registry-port
             :tag default-tag})))

(defn- ->auth-config [{:keys [auth email password registry-address
                              username identity-token registry-token]}]
  (cond-> (AuthConfig.)
    auth (.withAuth auth)
    email (.withEmail email)
    password (.withPassword password)
    registry-address (.withRegistryAddress registry-address)
    username (.withUsername username)
    identity-token (.withIdentityToken identity-token)
    registry-token (.withRegistrytoken registry-token)))

(defn pull-image
  ([^DockerClient client image]
   (if-let [{:keys [registry port namespace image tag]} (image->map image)]
     (pull-image client (str namespace \/ image)
                 {:tag tag, :registry (str registry \: port)})
     (throw (ex-info "Failed to parse docker image" {:image image}))))
  ([^DockerClient client image {:keys [tag platform registry auth-config]
                                :or {tag default-tag}}]
   (cond-> (.pullImageCmd client image)
     tag (.withTag tag)
     platform (.withPlatform platform)
     registry (.withRegistry registry)
     auth-config (.withAuthConfig (->auth-config auth-config))
     true (-> ^PullImageResultCallback (.exec (PullImageResultCallback.))
              (.awaitSuccess)))))

(defn list-images
  [^DockerClient client
   {:keys [show-all? dangling? image-name-filter label-filter]}]
  (cond-> (.listImagesCmd client)
    image-name-filter (.withImageNameFilter image-name-filter)
    show-all? (.withShowAll show-all?)
    dangling? (.withDanglingFilter dangling?)
    label-filter (.withLabelFilter ^Map label-filter)
    true (->> .exec (map ->map))))

(defn list-containers
  [^DockerClient client
   {:keys [show-all? show-size? limit since before name-filter id-filter
           ancestor-filter volume-filter network-filter label-filter
           exited-filter status-filter]}]
  (cond-> (.listContainersCmd client)
    show-all? (.withShowAll show-all?)
    show-size? (.withShowSize show-size?)
    limit (.withLimit (int limit))
    since (.withSince since)
    before (.withBefore before)
    name-filter (.withNameFilter (mapv str name-filter))
    id-filter (.withIdFilter (mapv str id-filter))
    ancestor-filter (.withAncestorFilter (mapv str ancestor-filter))
    volume-filter (.withVolumeFilter (mapv str volume-filter))
    network-filter (.withNetworkFilter (mapv str network-filter))
    label-filter (.withLabelFilter ^Map (->str-map label-filter))
    exited-filter (.withExitedFilter exited-filter)
    status-filter (.withStatusFilter status-filter)
    true (->> (.exec) (map ->map))))

(defn inspect-container
  [^DockerClient client id {:keys [size?]}]
  (cond-> (.inspectContainerCmd client id)
    size? (.withSize size?)
    true (->> (.exec) ->map)))

(defn- with-volumes ^CreateContainerCmd [^CreateContainerCmd cmd volumes]
  (let [[vs bs] (apply map vector
                       (for [[src dst] volumes]
                         (let [v (Volume. dst)
                               b (Bind. ^String src v AccessMode/ro)]
                           [v b])))]
    (.. cmd
        (withVolumes ^List vs)
        (withBinds ^List bs))))

(defn- with-ports ^CreateContainerCmd [^CreateContainerCmd cmd ports]
  (let [p (Ports.)
        eps (for [[exposed-port bind] ports]
              (let [ep (ExposedPort/tcp exposed-port)]
                (.bind p ep (Ports$Binding/bindPort bind))
                ep))]
    (.. cmd
        (withExposedPorts ^List (vec eps))
        (withPortBindings p))))

(defn create-container
  [^DockerClient client image
   {:keys [entrypoint command container-name env labels
           volumes ports working-dir user network-mode]}]
  (cond-> (.createContainerCmd client image)
    command (.withCmd ^List command)
    entrypoint (.withEntrypoint ^List entrypoint)
    container-name (.withName (str container-name))
    labels (.withLabels ^Map (->str-map labels))
    env (.withEnv ^List (mapv #(str (key %) \= (val %)) env))
    volumes (with-volumes volumes)
    ports (with-ports ports)
    working-dir (.withWorkingDir working-dir)
    user (.withUser user)
    network-mode (.withNetworkMode network-mode)
    true (-> (.withAttachStdout true) (.withAttachStderr true) (.exec) ->map)))

(defn copy-to-container
  [^DockerClient client id src
   {:keys [remote-path dir-children-only? no-overwrite-dir-non-dir?]}]
  (with-open [bais (ByteArrayInputStream. (tar/tar src))]
    (cond-> (.withTarInputStream (.copyArchiveToContainerCmd client id) bais)
      remote-path (.withRemotePath (str remote-path))
      dir-children-only? (.withDirChildrenOnly dir-children-only?)
      no-overwrite-dir-non-dir? (.withNoOverwriteDirNonDir
                                 no-overwrite-dir-non-dir?)
      true (.exec))))

(defn copy-from-container
  [^DockerClient client id src {:keys [host-path]}]
  (with-open [is (.exec (cond-> (.copyArchiveFromContainerCmd client id src)
                          host-path (.withHostPath host-path)))]
    (tar/untar is)))

(defn start-container [^DockerClient client id]
  (.exec (.startContainerCmd client id)))

(defn stop-container [^DockerClient client id
                      {:keys [timeout] :or {timeout 30}}] ;; seconds
  (.exec (.withTimeout (.stopContainerCmd client id) (some-> timeout int))))

(defn kill-container [^DockerClient client id
                      {:keys [signal] :or {signal "KILL"}}]
  (.exec (.withSignal (.killContainerCmd client id) signal)))

(defn rm-container [^DockerClient client id
                    {:keys [force? remove-volumes?] :or {remove-volumes? true}}]
  (cond-> (.removeContainerCmd client id)
    force? (.withForce force?)
    remove-volumes? (.withRemoveVolumes remove-volumes?)
    true (.exec)))

(def ^:dynamic *clock* (Clock/systemUTC))

(defn- ->log-line [timestamps? ^Frame item]
  (let [stream-type (.getStreamType item)
        payload (String. (.getPayload item))
        [ts body] (if timestamps?
                    (update (str/split payload #"\s" 2)
                            0 #(Instant/parse %))
                    [(Instant/now *clock*) payload])
        t (case (.ordinal stream-type)
            1 :stdout
            2 :stderr)]
    {:type t, :timestamp ts, :body body}))

(defn log-container
  ([^DockerClient client id]
   (log-container client id {}))
  ([^DockerClient client id {:keys [stdout? stderr? timestamps? follow-stream?
                                    tail since on-start on-next on-complete]
                             :or {stdout? true, stderr? true, timestamps? true,
                                  follow-stream? true, tail -1}}]
   (cond-> (.logContainerCmd client id)
     stdout? (.withStdOut stdout?)
     stderr? (.withStdErr stderr?)
     tail (.withTail (int tail))
     since (.withSince since)
     timestamps? (.withTimestamps timestamps?)
     follow-stream? (.withFollowStream follow-stream?)
     true (-> ^LogContainerResultCallback
           (.exec
            (proxy [LogContainerResultCallback] []
              (onStart [stream]
                (typed-proxy-super LogContainerResultCallback onStart stream)
                (when on-start (on-start {:container-id id})))
              (onNext [item]
                (typed-proxy-super LogContainerResultCallback onNext item)
                (when on-next
                  (on-next (into {:container-id id}
                                 (->log-line timestamps? item)))))
              (onComplete []
                (typed-proxy-super LogContainerResultCallback onComplete)
                (when on-complete
                  (on-complete {:container-id id})))))
              (.awaitStarted)))))

(defn wait-container [^DockerClient client id]
  (-> client
      (.waitContainerCmd id)
      ^WaitContainerResultCallback
      (.exec
       (proxy [WaitContainerResultCallback] []
         (onNext [^WaitResponse wait-response]
           (typed-proxy-super WaitContainerResultCallback onNext wait-response)
           (let [status-code (.getStatusCode wait-response)]
             status-code
             nil))))
      (.awaitStatusCode)))

;; wrappers

(defn- find-image
  [client image {:keys [tag] :or {tag default-tag} :as opts}]
  (some
   (fn [{:keys [repo-tags] :as i}]
     (when (or (not tag) (some #{(str image \: tag)} repo-tags)) i))
   (list-images client (assoc opts :image-name-filter image))))

(defn prep-image [client image opts]
  (or (find-image client image opts)
      (do (pull-image client image opts)
          (find-image client image opts))))

(defn- prep-container [client image opts]
  (let [{:keys [id warnings]} (create-container client image opts)]
    (when-let [_ (seq warnings)]
      (rm-container client id opts)
      (throw (ex-info "Failed to create-container" {:warnings warnings})))
    id))

(defn create [client image opts]
  (let [{image-id :id} (prep-image client image opts)
        id (prep-container client image-id opts)]
    (try
      (when-let [cp (not-empty (:cp opts))]
        (copy-to-container client id cp opts))
      id
      (catch Throwable e
        (when id
          (rm-container client id opts))
        (throw e)))))

(defn wait [client id opts]
  (try
    (when ((some-fn  :on-start :on-next :on-complete) opts)
      (log-container client id opts))
    (wait-container client id)
    (catch Throwable e
      (prn e)
      (throw e))
    (finally
      (try
        (rm-container client id opts)
        (catch Throwable e
          (prn e))))))

;; components

(defrecord Boundary [client info]
  d/IDocker
  (info [_]
    (info client))
  (prep-image [_ image opts]
    (prep-image client image opts))
  (list-containers [_ opts]
    (for [{:keys [id] :as c} (list-containers client opts)]
      (assoc c :inspection (inspect-container client id opts))))
  (start-container [_ id]
    (start-container client id))
  (remove-container [_ id opts]
    (rm-container client id opts))
  (kill-container [_ id opts]
    (kill-container client id opts)))

(defmethod ig/init-key ::client [_ opts]
  (let [client (build-client opts)]
    (->Boundary client (info client))))

(defmethod ig/halt-key! ::client [_ {:keys [client]}]
  (close client))
