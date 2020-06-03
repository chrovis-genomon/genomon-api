-- :name _list-dna-runs :? :*
SELECT `r`.*,
       `d`.`normal-r1`, `d`.`normal-r2`, `d`.`tumor-r1`, `d`.`tumor-r2`, `d`.`control-panel`,
       `d`.`normal-bam`, `d`.`tumor-bam`, `d`.`mutations`, `d`.svs
  FROM `dna-runs` AS `d`
         INNER JOIN `runs` AS `r`
             ON `d`.`run-id` = `r`.`run-id`
 ORDER BY `d`.`dna-run-id` ASC
 LIMIT :limit
 OFFSET :offset;

-- :name _get-dna-run :? :1
SELECT `r`.*,
       `d`.`normal-r1`, `d`.`normal-r2`, `d`.`tumor-r1`, `d`.`tumor-r2`, `d`.`control-panel`,
       `d`.`normal-bam`, `d`.`tumor-bam`, `d`.`mutations`, `d`.svs
  FROM `dna-runs` AS `d`
         INNER JOIN `runs` AS `r`
             ON `d`.`run-id` = `r`.`run-id`
 WHERE `d`.`run-id` = :run-id;

-- :name _insert-dna-run! :i! :raw
INSERT INTO `dna-runs` (`run-id`, `normal-r1`, `normal-r2`, `tumor-r1`, `tumor-r2`, `control-panel`)
VALUES (:run-id, :normal-r1, :normal-r2, :tumor-r1, :tumor-r2, :control-panel);

-- :name _update-dna-results! :! :n
UPDATE `dna-runs`
   SET `normal-bam` = :normal-bam,
       `tumor-bam` = :tumor-bam,
       `mutations` = :mutations,
       `svs` = :svs
 WHERE `run-id` = :run-id;
