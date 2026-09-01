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
package org.apache.cloudstack.api.response;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.announcement.Announcement;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

import java.util.Date;

@EntityReference(value = {Announcement.class})
public class AnnouncementResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "ID of the announcement.")
    private String id;

    @SerializedName(ApiConstants.TITLE)
    @Param(description = "Title of the announcement.")
    private String title;

    @SerializedName(ApiConstants.MESSAGE)
    @Param(description = "Message displayed in the announcement banner (HTML allowed).")
    private String message;

    @SerializedName(ApiConstants.TYPE)
    @Param(description = "Type of the announcement: info, success, warning or error.")
    private String type;

    @SerializedName(ApiConstants.ENABLED)
    @Param(description = "Whether the announcement is turned on.")
    private Boolean enabled;

    @SerializedName(ApiConstants.CLOSABLE)
    @Param(description = "Whether users can dismiss the announcement banner.")
    private Boolean closable;

    @SerializedName(ApiConstants.PERSIST_DISMISSAL)
    @Param(description = "Whether a dismissed announcement stays dismissed for the user.")
    private Boolean persistDismissal;

    @SerializedName(ApiConstants.START_DATE)
    @Param(description = "Time the announcement becomes visible (null = immediately).")
    private Date startDate;

    @SerializedName(ApiConstants.END_DATE)
    @Param(description = "Time the announcement is no longer visible (null = no end date).")
    private Date endDate;

    @SerializedName(ApiConstants.PRIORITY)
    @Param(description = "Display order of simultaneous announcements; lower values are shown first.")
    private Integer priority;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "When the announcement was created.")
    private Date created;

    public AnnouncementResponse() {
        super("announcement");
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getClosable() {
        return closable;
    }

    public void setClosable(Boolean closable) {
        this.closable = closable;
    }

    public Boolean getPersistDismissal() {
        return persistDismissal;
    }

    public void setPersistDismissal(Boolean persistDismissal) {
        this.persistDismissal = persistDismissal;
    }

    public Date getStartDate() {
        return startDate;
    }

    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    public Date getEndDate() {
        return endDate;
    }

    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }
}
