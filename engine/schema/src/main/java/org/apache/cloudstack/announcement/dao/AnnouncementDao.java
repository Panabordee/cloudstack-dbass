// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.announcement.dao;

import java.util.Date;
import java.util.List;

import org.apache.cloudstack.announcement.AnnouncementVO;

import com.cloud.utils.db.GenericDao;

public interface AnnouncementDao extends GenericDao<AnnouncementVO, Long> {

    /**
     * Lists all non-removed announcements (including disabled or out of schedule ones).
     */
    List<AnnouncementVO> listAllAnnouncements();

    /**
     * Lists non-removed announcements that are enabled and within their display window.
     */
    List<AnnouncementVO> listVisibleAnnouncements(Date now);

    /**
     * Returns a fingerprint of the current state of the visible announcements,
     * used by the SSE heartbeat to detect changes across management servers.
     */
    String getVisibleAnnouncementsFingerprint(Date now);
}
