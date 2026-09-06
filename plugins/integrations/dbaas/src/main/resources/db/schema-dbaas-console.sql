-- Console transport tables (C1 of PLAN-DBAAS-CONSOLE.md). Created with
-- CREATE TABLE IF NOT EXISTS by DbaasManagerImpl.ensureConsoleTablesExists()
-- on every management server start, the same pattern as
-- dbaas_credentials. No manual DBA step, no DatabaseUpgradeChecker hook.

-- One live agent token per instance. The raw token never lands here: only
-- its SHA-256 hash, minted at createDatabase and rotated by the agent.
CREATE TABLE IF NOT EXISTS `dbaas_agent_tokens` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `vm_id` bigint unsigned NOT NULL,
  `token_hash` char(64) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `rotated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_seen_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `u_dbaas_agent_tokens__vm_id` (`vm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per console job. The payload is encrypted at rest
-- (DBEncryptionUtil) and never logged. expires_at kills a job nobody
-- dispatched; there is no silent retry that could run a statement twice.
CREATE TABLE IF NOT EXISTS `dbaas_jobs` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `vm_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `type` varchar(32) NOT NULL,
  `db_role` varchar(16) NOT NULL DEFAULT 'readonly',
  `payload` text NOT NULL,
  `state` varchar(16) NOT NULL DEFAULT 'pending',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `dispatched_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `expires_at` datetime NOT NULL,
  `row_count` int DEFAULT NULL,
  `truncated` tinyint DEFAULT NULL,
  `error` varchar(1024) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `u_dbaas_jobs__uuid` (`uuid`),
  KEY `i_dbaas_jobs__vm_state` (`vm_id`, `state`),
  KEY `i_dbaas_jobs__account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The tenant data of a finished job, split from dbaas_jobs so delete-on-read
-- is one statement on one row and the job's audit trail survives after the
-- result is gone. Encrypted at rest like the credentials.
CREATE TABLE IF NOT EXISTS `dbaas_job_results` (
  `job_id` bigint unsigned NOT NULL,
  `result` mediumtext NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
