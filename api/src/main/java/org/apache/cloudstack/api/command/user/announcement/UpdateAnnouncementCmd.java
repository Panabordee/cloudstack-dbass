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

import java.util.Date;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.announcement.Announcement;
import org.apache.cloudstack.announcement.AnnouncementService;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.AnnouncementResponse;
import org.apache.cloudstack.context.CallContext;

@APICommand(name = "updateAnnouncement", description = "Updates an announcement. All parameters are optional; only the provided ones are updated.", responseObject = AnnouncementResponse.class,
        entityType = {Announcement.class}, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, since = "4.22.3.0", authorized = {RoleType.Admin})
public class UpdateAnnouncementCmd extends BaseCmd {

    @Inject
    AnnouncementService announcementService;

    @Parameter(name = ApiConstants.ID, required = true, type = CommandType.UUID, entityType = AnnouncementResponse.class, description = "The ID of the announcement to be updated.")
    private Long id;

    @Parameter(name = ApiConstants.TITLE, type = CommandType.STRING, length = 255, description = "Title of the announcement.")
    private String title;

    @Parameter(name = ApiConstants.MESSAGE, type = CommandType.STRING, length = 4095, description = "Message displayed in the announcement banner (HTML allowed).")
    private String message;

    @Parameter(name = ApiConstants.TYPE, type = CommandType.STRING, description = "Type of the announcement: info, success, warning or error (danger is accepted as an alias of error).")
    private String type;

    @Parameter(name = ApiConstants.ENABLED, type = CommandType.BOOLEAN, description = "Whether the announcement is turned on.")
    private Boolean enabled;

    @Parameter(name = ApiConstants.CLOSABLE, type = CommandType.BOOLEAN, description = "Whether users can dismiss the announcement banner.")
    private Boolean closable;

    @Parameter(name = ApiConstants.PERSIST_DISMISSAL, type = CommandType.BOOLEAN, description = "Whether a dismissed announcement stays dismissed for the user.")
    private Boolean persistDismissal;

    @Parameter(name = ApiConstants.START_DATE, type = CommandType.DATE, description = "Time the announcement becomes visible.")
    private Date startDate;

    @Parameter(name = ApiConstants.END_DATE, type = CommandType.DATE, description = "Time the announcement is no longer visible.")
    private Date endDate;

    @Parameter(name = ApiConstants.PRIORITY, type = CommandType.INTEGER, description = "Display order of simultaneous announcements; lower values are shown first.")
    private Integer priority;

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getType() {
        return type;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public Boolean getClosable() {
        return closable;
    }

    public Boolean getPersistDismissal() {
        return persistDismissal;
    }

    public Date getStartDate() {
        return startDate;
    }

    public Date getEndDate() {
        return endDate;
    }

    public Integer getPriority() {
        return priority;
    }

    @Override
    public void execute() {
        Announcement announcement = announcementService.updateAnnouncement(this);

        if (announcement == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update the announcement.");
        }

        AnnouncementResponse response = _responseGenerator.createAnnouncementResponse(announcement);
        response.setResponseName(getCommandName());
        this.setResponseObject(response);
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }
}
