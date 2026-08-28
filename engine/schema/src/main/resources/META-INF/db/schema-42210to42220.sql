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
