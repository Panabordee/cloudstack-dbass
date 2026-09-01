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

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.cloudstack.announcement.AnnouncementVO;
import org.springframework.stereotype.Component;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component
public class AnnouncementDaoImpl extends GenericDaoBase<AnnouncementVO, Long> implements AnnouncementDao {

    private final SearchBuilder<AnnouncementVO> visibleSearch;

    public AnnouncementDaoImpl() {
        super();
        visibleSearch = createSearchBuilder();
        visibleSearch.and("removed", visibleSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        visibleSearch.and("enabled", visibleSearch.entity().getEnabled(), SearchCriteria.Op.EQ);
        visibleSearch.and().op("startDateNull", visibleSearch.entity().getStartDate(), SearchCriteria.Op.NULL);
        visibleSearch.or("startDateLte", visibleSearch.entity().getStartDate(), SearchCriteria.Op.LTEQ);
        visibleSearch.cp();
        visibleSearch.and().op("endDateNull", visibleSearch.entity().getEndDate(), SearchCriteria.Op.NULL);
        visibleSearch.or("endDateGte", visibleSearch.entity().getEndDate(), SearchCriteria.Op.GTEQ);
        visibleSearch.cp();
        visibleSearch.done();
    }

    @Override
    public List<AnnouncementVO> listAllAnnouncements() {
        SearchBuilder<AnnouncementVO> sb = createSearchBuilder();
        sb.and("removed", sb.entity().getRemoved(), SearchCriteria.Op.NULL);
        sb.done();
        SearchCriteria<AnnouncementVO> sc = sb.create();
        return listBy(sc);
    }

    @Override
    public List<AnnouncementVO> listVisibleAnnouncements(Date now) {
        SearchCriteria<AnnouncementVO> sc = visibleSearch.create();
        sc.setParameters("enabled", true);
        sc.setParameters("startDateLte", now);
        sc.setParameters("endDateGte", now);
        return listBy(sc).stream()
                .sorted(Comparator.comparingInt(AnnouncementVO::getPriority)
                        .thenComparing(AnnouncementVO::getCreated, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    @Override
    public String getVisibleAnnouncementsFingerprint(Date now) {
        return listVisibleAnnouncements(now).stream()
                .map(vo -> String.format("%s:%s", vo.getUuid(), vo.getUpdated() == null ? 0L : vo.getUpdated().getTime()))
                .collect(Collectors.joining(","));
    }
}
