-- :name _list-rna-runs :? :*
SELECT `r`.*,
       `rr`.`r1`, `rr`.`r2`, `rr`.`control-panel`,
       `rr`.`bam`, `rr`.`fusions`, `rr`.`expressions`, `rr`.`intron-retentions`
  FROM `rna-runs` AS `rr`
         INNER JOIN `runs` AS `r`
             ON `r`.`run-id` = `rr`.`run-id`
 ORDER BY `rr`.`rna-run-id` ASC
 LIMIT :limit
 OFFSET :offset;

-- :name _get-rna-run :? :1
SELECT `r`.*,
       `rr`.`r1`, `rr`.`r2`, `rr`.`control-panel`,
       `rr`.`bam`, `rr`.`fusions`, `rr`.`expressions`, `rr`.`intron-retentions`
  FROM `rna-runs` AS `rr`
         INNER JOIN `runs` AS `r`
             ON `r`.`run-id` = `rr`.`run-id`
 WHERE `rr`.`run-id` = :run-id;

-- :name _insert-rna-run! :i! :raw
INSERT INTO `rna-runs` (`run-id`, `r1`, `r2`, `control-panel`)
VALUES (:run-id, :r1, :r2, :control-panel);

-- :name _update-rna-results! :! :n
UPDATE `rna-runs`
   SET `bam` = :bam,
       `fusions` = :fusions,
       `expressions` = :expressions,
       `intron-retentions` = :intron-retentions
 WHERE `run-id` = :run-id;
