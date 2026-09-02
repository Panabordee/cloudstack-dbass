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
--
-- LIFECYCLE / CLEANUP:
-- Rows are never deleted automatically: the extension has no expunge hook in
-- 4.22 (the Extensions Framework exposes no VM-lifecycle event to plugins), so
-- credentials of destroyed VMs accumulate. They are encrypted at rest and
-- harmless beyond table growth, but a periodic cleanup is recommended on
-- long-lived deployments. The safe form joins against vm_instance so live VMs
-- are never touched (uuid keeps matching; removed rows have removed!=NULL):
--
--   DELETE c FROM dbaas_credentials c
--     LEFT JOIN vm_instance v ON v.uuid = c.vm_id
--     WHERE v.id IS NULL OR v.removed IS NOT NULL;
--
-- Run it by hand, or wire it into whatever maintenance scheduling the
-- deployment already has. An automatic expunge hook is worth adding if the
-- Extensions Framework grows a VM-lifecycle event.
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
