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
package org.apache.cloudstack.api.command.user.announcement;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.announcement.Announcement;
import org.apache.cloudstack.announcement.AnnouncementService;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.AnnouncementResponse;
import org.apache.cloudstack.api.response.ListResponse;

@APICommand(name = "listAnnouncements", description = "Lists announcements. Regular users and unauthenticated callers only see the announcements that are enabled and within "
        + "their schedule; root admins see all announcements.", responseObject = AnnouncementResponse.class, entityType = {Announcement.class},
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, since = "4.22.3.0",
        authorized = {RoleType.Admin, RoleType.User, RoleType.DomainAdmin, RoleType.ResourceAdmin})
public class ListAnnouncementsCmd extends BaseListCmd {

    @Inject
    AnnouncementService announcementService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = AnnouncementResponse.class, description = "The ID of the announcement.")
    private Long id;

    @Parameter(name = ApiConstants.TYPE, type = CommandType.STRING, description = "Lists announcements by type: info, success, warning or error.")
    private String type;

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    @Override
    public void execute() {
        ListResponse<AnnouncementResponse> response = announcementService.listAnnouncements(this);
        response.setResponseName(getCommandName());
        this.setResponseObject(response);
    }
}
