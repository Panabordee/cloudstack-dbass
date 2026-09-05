-- Reference copy of the table DbaasManagerImpl.ensureCredentialsTableExists()
-- creates itself on every management server start (CREATE TABLE IF NOT
-- EXISTS, so it costs nothing to repeat and needs no manual DBA step or
-- DatabaseUpgradeChecker hook). Kept here so the schema is readable without
-- digging through Java string concatenation, and as a fallback if you'd
-- rather provision it by hand ahead of time:
--
--   mysql -u cloud -p cloud < schema-dbaas-credentials.sql
--
-- This file is the single definition of the table:
-- DbaasManagerImpl.ensureCredentialsTableExists() reads it from the
-- classpath (it is packaged into the plugin jar) rather than repeating the
-- DDL in Java, so there is nothing to keep in sync. The reader strips
-- `--` comment lines and one trailing semicolon, so keep the file to a
-- single statement.
--
-- LIFECYCLE / CLEANUP:
-- The plugin's sweeper (DbaasManagerImpl, interval dbaas.credentials.cleanup.interval,
-- default 3600s, started with the management server) deletes rows whose
-- instance has been expunged on schedule; orphaned DATADISK volumes are only
-- counted and logged there, never deleted. The statement it runs is exactly
-- the one below, so manual execution is only needed on deployments running
-- older plugin builds:
--
--   DELETE c FROM dbaas_credentials c
--     LEFT JOIN vm_instance v ON v.uuid = c.vm_id
--     WHERE v.id IS NULL OR v.removed IS NOT NULL;
CREATE TABLE IF NOT EXISTS `dbaas_credentials` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  -- vm_template.uuid / vm_instance.uuid are CHAR(40) elsewhere in the cloud
  -- schema; matched here rather than picking an arbitrary shorter length.
  `vm_id` char(40) NOT NULL,
  `db_username` varchar(255) NOT NULL,
  -- DBEncryptionUtil's ciphertext is base64 with no fixed length guarantee
  -- across key/algorithm choices, so this is sized generously rather than
  -- exactly.
  `db_password_encrypted` varchar(512) NOT NULL,
  `engine` varchar(255) NOT NULL,
  -- Provisioning outcome, so Show Password can state what happened instead of
  -- inferring it from the absence of a row: a row exists from the moment the
  -- credential is generated, and the instance moves it to confirmed/failed.
  -- 'confirmed' is the only state in which the credential is known to work.
  `status` varchar(32) NOT NULL DEFAULT 'pending',
  `status_message` varchar(1024) DEFAULT NULL,
  -- Lets the instance report its own provisioning outcome through
  -- reportDbaasProvisioningResult without holding any CloudStack credential:
  -- the SHA-256 hash of a token generated at create time and handed to the
  -- instance over the config drive (the raw token is never stored). Cleared
  -- on first use or expiry, whichever comes first -- a cleared hash can never
  -- match anything, so the token is single-use by construction.
  `report_token_hash` char(64) DEFAULT NULL,
  `report_token_expires_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `i_dbaas_credentials__vm_id` (`vm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
