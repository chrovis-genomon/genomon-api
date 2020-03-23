CREATE TABLE `runs` (
  `run-id` BINARY(16) NOT NULL,
  `created-at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated-at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `status` ENUM('created', 'started', 'interrupted', 'succeeded', 'failed') NOT NULL,
  `output-dir` VARCHAR(1024) NOT NULL,
  `image-id` VARCHAR(71) NOT NULL, /* sha256:(64) */
  `container-id` VARCHAR(255) NOT NULL,
  `config` LONGTEXT NOT NULL,
  PRIMARY KEY (`run-id`))
  ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4;
