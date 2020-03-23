(ns genomon-api.docker)

(defprotocol IDocker
  (info [this])
  (prep-image [this image opts])
  (list-containers [this opts])
  (start-container [this id])
  (remove-container [this id opts])
  (kill-container [this id opts]))

(defprotocol IDockerAsync
  (run-async [this image ch opts])
  (wait-async [this id ch]))
