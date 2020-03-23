CREATE TABLE `rna-runs` (
  `rna-run-id` INT UNSIGNED AUTO_INCREMENT NOT NULL,
  `run-id` BINARY(16) NOT NULL,
  `r1` VARCHAR(1024) NOT NULL,
  `r2` VARCHAR(1024) NOT NULL,
  `bam` VARCHAR(1024) DEFAULT NULL,
  `fusions` VARCHAR(1024) DEFAULT NULL,
  `expressions` VARCHAR(1024) DEFAULT NULL,
  `intron-retentions` VARCHAR(1024) DEFAULT NULL,
  PRIMARY KEY (`rna-run-id`),
  CONSTRAINT `uniq-run-id-rna` UNIQUE INDEX (`run-id`),
  CONSTRAINT `fk-rna-runs` FOREIGN KEY (`run-id`) REFERENCES `runs` (`run-id`) ON DELETE CASCADE)
  ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4;
