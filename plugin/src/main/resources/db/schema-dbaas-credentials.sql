-- Reference copy of the table DbaasManagerImpl.ensureCredentialsTableExists()
-- creates itself on every management server start (CREATE TABLE IF NOT
-- EXISTS, so it costs nothing to repeat and needs no manual DBA step or
-- DatabaseUpgradeChecker hook). Kept here so the schema is readable without
-- digging through Java string concatenation, and as a fallback if you'd
-- rather provision it by hand ahead of time:
--
--   mysql -u cloud -p cloud < schema-dbaas-credentials.sql
--
-- Keep this in sync with the CREATE TABLE in DbaasManagerImpl.java if either
-- one changes.
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
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `i_dbaas_credentials__vm_id` (`vm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
