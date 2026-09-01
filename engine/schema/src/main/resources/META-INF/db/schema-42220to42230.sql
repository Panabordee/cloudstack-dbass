-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

--;
-- Schema upgrade from 4.22.2.0 to 4.22.3.0
--;

DROP TABLE IF EXISTS `cloud`.`announcement`;

CREATE TABLE `cloud`.`announcement` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id of the announcement',
  `uuid` varchar(255) UNIQUE COMMENT 'uuid of the announcement',
  `title` varchar(255) NOT NULL COMMENT 'title of the announcement',
  `message` varchar(4095) NOT NULL COMMENT 'message displayed in the announcement banner (HTML allowed)',
  `type` varchar(32) NOT NULL DEFAULT 'info' COMMENT 'type of the announcement: info, success, warning or error',
  `enabled` int(1) unsigned NOT NULL DEFAULT 1 COMMENT 'whether the announcement is turned on',
  `closable` int(1) unsigned NOT NULL DEFAULT 1 COMMENT 'whether users can dismiss the announcement banner',
  `persist_dismissal` int(1) unsigned NOT NULL DEFAULT 1 COMMENT 'whether a dismissed announcement stays dismissed for the user',
  `start_date` datetime DEFAULT NULL COMMENT 'time the announcement becomes visible (null = immediately)',
  `end_date` datetime DEFAULT NULL COMMENT 'time the announcement is no longer visible (null = no end date)',
  `priority` int NOT NULL DEFAULT 0 COMMENT 'display order of simultaneous announcements; lower values are shown first',
  `created` datetime NOT NULL COMMENT 'date the announcement was created',
  `updated` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT 'date the announcement was last updated',
  `removed` datetime DEFAULT NULL COMMENT 'date the announcement was removed',
  PRIMARY KEY (`id`),
  INDEX `i_announcement__enabled`(`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
