CREATE TABLE `dna-runs` (
  `dna-run-id` INT UNSIGNED AUTO_INCREMENT NOT NULL,
  `run-id` BINARY(16) NOT NULL,
  `normal-r1` VARCHAR(1024) NOT NULL,
  `normal-r2` VARCHAR(1024) NOT NULL,
  `tumor-r1` VARCHAR(1024) NOT NULL,
  `tumor-r2` VARCHAR(1024) NOT NULL,
  `normal-bam` VARCHAR(1024) DEFAULT NULL,
  `tumor-bam` VARCHAR(1024) DEFAULT NULL,
  `mutations` VARCHAR(1024) DEFAULT NULL,
  `svs` VARCHAR(1024) DEFAULT NULL,
  PRIMARY KEY (`dna-run-id`),
  CONSTRAINT `uniq-run-id-dna` UNIQUE INDEX (`run-id`),
  CONSTRAINT `fk-dna-runs` FOREIGN KEY (`run-id`) REFERENCES `runs` (`run-id`) ON DELETE CASCADE)
  ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4;
