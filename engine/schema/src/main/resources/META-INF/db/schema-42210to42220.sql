--;
-- Schema upgrade from 4.22.1.0 to 4.22.2.0
--;

-- Reverse Proxy integration: proxy host mappings created by the reverse proxy plugin
CREATE TABLE IF NOT EXISTS `cloud`.`reverse_proxy_host` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `uuid` varchar(40) DEFAULT NULL COMMENT 'uuid',
  `name` varchar(255) NOT NULL COMMENT 'user chosen name (prefix of the proxy host FQDN)',
  `fqdn` varchar(255) NOT NULL COMMENT 'fully qualified domain name of the proxy host',
  `vm_instance_id` bigint unsigned NOT NULL COMMENT 'id of the proxied instance',
  `network_id` bigint unsigned NOT NULL COMMENT 'id of the network the instance is proxied on',
  `ip_address` varchar(255) NOT NULL COMMENT 'instance IPv4 address the proxy forwards to',
  `forward_scheme` varchar(16) NOT NULL DEFAULT 'http' COMMENT 'scheme used to forward requests to the instance',
  `forward_port` int unsigned NOT NULL COMMENT 'port exposed on the instance',
  `npm_proxy_host_id` bigint unsigned NOT NULL COMMENT 'proxy host id on the Nginx Proxy Manager',
  `account_id` bigint unsigned NOT NULL COMMENT 'owner of the proxy host',
  `domain_id` bigint unsigned NOT NULL COMMENT 'domain of the owner',
  `state` varchar(32) NOT NULL DEFAULT 'Active' COMMENT 'state of the proxy host mapping',
  `created` datetime DEFAULT NULL COMMENT 'date the proxy host was created',
  `removed` datetime DEFAULT NULL COMMENT 'date the proxy host was removed',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reverse_proxy_host_uuid` (`uuid`),
  KEY `i_reverse_proxy_host_fqdn` (`fqdn`),
  KEY `i_reverse_proxy_host_vm_instance_id` (`vm_instance_id`),
  KEY `i_reverse_proxy_host_account_id` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Reverse Proxy integration: configurable domain suffixes and per-account/per-network grants
CREATE TABLE IF NOT EXISTS `cloud`.`reverse_proxy_domain` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `uuid` varchar(40) DEFAULT NULL COMMENT 'uuid',
  `domain` varchar(255) NOT NULL COMMENT 'the domain suffix exposed to users, for example cloud.company.com',
  `description` varchar(255) DEFAULT NULL COMMENT 'description of the domain suffix',
  `is_public` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'true if the domain suffix can be used by all accounts, false if only granted accounts or shared networks can use it',
  `npm_certificate_id` bigint unsigned DEFAULT NULL COMMENT 'id of the Nginx Proxy Manager certificate used for TLS termination of this domain suffix',
  `created` datetime DEFAULT NULL COMMENT 'date the domain suffix was created',
  `removed` datetime DEFAULT NULL COMMENT 'date the domain suffix was removed',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reverse_proxy_domain_uuid` (`uuid`),
  UNIQUE KEY `uk_reverse_proxy_domain_domain` (`domain`, `removed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `cloud`.`reverse_proxy_domain_map` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `domain_id` bigint unsigned NOT NULL COMMENT 'id of the reverse proxy domain suffix',
  `account_id` bigint unsigned DEFAULT NULL COMMENT 'id of the granted account, null if granted to a network',
  `network_id` bigint unsigned DEFAULT NULL COMMENT 'id of the granted shared network, null if granted to an account',
  `created` datetime DEFAULT NULL COMMENT 'date the grant was created',
  `removed` datetime DEFAULT NULL COMMENT 'date the grant was removed',
  PRIMARY KEY (`id`),
  KEY `i_reverse_proxy_domain_map_domain_id` (`domain_id`),
  KEY `i_reverse_proxy_domain_map_account_id` (`account_id`),
  KEY `i_reverse_proxy_domain_map_network_id` (`network_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed the domain suffix table from the legacy single-domain setting, the seeded domain stays public to all users
INSERT INTO `cloud`.`reverse_proxy_domain` (uuid, domain, is_public, created)
  SELECT UUID(), TRIM(`value`), 1, utc_timestamp()
  FROM `cloud`.`configuration`
  WHERE `name` = 'reverseproxy.domain' AND `value` IS NOT NULL AND TRIM(`value`) != '';

-- Track the domain suffix of existing proxy hosts
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.reverse_proxy_host', 'reverse_proxy_domain_id',
  'bigint unsigned DEFAULT NULL COMMENT ''id of the reverse proxy domain suffix''');
UPDATE `cloud`.`reverse_proxy_host` h
  JOIN `cloud`.`reverse_proxy_domain` d ON h.fqdn LIKE CONCAT('%.', d.domain)
  SET h.reverse_proxy_domain_id = d.id
  WHERE h.reverse_proxy_domain_id IS NULL;

-- Add URLs for OAuth provider
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.oauth_provider','authorize_url', 'VARCHAR(255) DEFAULT NULL COMMENT ''Authorize URL for OAuth initialization'' ');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.oauth_provider','token_url', 'VARCHAR(255) DEFAULT NULL COMMENT ''Token URL for OAuth finalization'' ');

-- Custom logo (data URI) shown on the OAuth login button
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.oauth_provider','logo', 'MEDIUMTEXT NULL COMMENT ''Custom logo (data URI) for the OAuth login button'' ');
