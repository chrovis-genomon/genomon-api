-- :name _insert-run! :i! :raw
INSERT INTO `runs` (`run-id`, `status`, `output-dir`, `image-id`, `container-id`, `config`)
VALUES (:run-id, :status, :output-dir, :image-id, :container-id, :config);

-- :name _update-run-status! :! :n
UPDATE `runs`
   SET `status` = :status
 WHERE `run-id` = :run-id;

-- :name _delete-run! :! :n
DELETE FROM `runs` WHERE `run-id` = :run-id;
