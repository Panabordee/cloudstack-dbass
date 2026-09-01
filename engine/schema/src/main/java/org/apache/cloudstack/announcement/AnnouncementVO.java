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
package org.apache.cloudstack.announcement;

import com.cloud.utils.db.GenericDao;
import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import java.util.Date;
import java.util.UUID;

@Entity
@Table(name = "announcement")
public class AnnouncementVO implements Announcement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "uuid", nullable = false)
    private String uuid = UUID.randomUUID().toString();

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "message", nullable = false, length = 4095)
    private String message;

    @Column(name = "type", nullable = false, length = 32)
    private String type = AnnouncementConstants.TYPE_INFO;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "closable", nullable = false)
    private boolean closable = true;

    @Column(name = "persist_dismissal", nullable = false)
    private boolean persistDismissal = true;

    @Column(name = "start_date")
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date startDate;

    @Column(name = "end_date")
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date endDate;

    @Column(name = "priority", nullable = false)
    private int priority = 0;

    @Column(name = GenericDao.CREATED_COLUMN, nullable = false)
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date created;

    @Column(name = "updated")
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date updated;

    @Column(name = GenericDao.REMOVED_COLUMN)
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date removed = null;

    public AnnouncementVO() {
        this.created = new Date();
    }

    public AnnouncementVO(String title, String message, String type, boolean enabled, boolean closable, boolean persistDismissal, Date startDate, Date endDate, int priority) {
        this();
        this.title = title;
        this.message = message;
        this.type = type;
        this.enabled = enabled;
        this.closable = closable;
        this.persistDismissal = persistDismissal;
        this.startDate = startDate;
        this.endDate = endDate;
        this.priority = priority;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public boolean getEnabled() {
        return enabled;
    }

    @Override
    public boolean getClosable() {
        return closable;
    }

    @Override
    public boolean getPersistDismissal() {
        return persistDismissal;
    }

    @Override
    public Date getStartDate() {
        return startDate;
    }

    @Override
    public Date getEndDate() {
        return endDate;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public Date getCreated() {
        return created;
    }

    @Override
    public Date getUpdated() {
        return updated;
    }

    @Override
    public Date getRemoved() {
        return removed;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    @Override
    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    @Override
    public void setTitle(String title) {
        this.title = title;
    }

    @Override
    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public void setType(String type) {
        this.type = type;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void setClosable(boolean closable) {
        this.closable = closable;
    }

    @Override
    public void setPersistDismissal(boolean persistDismissal) {
        this.persistDismissal = persistDismissal;
    }

    @Override
    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    @Override
    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    @Override
    public void setPriority(int priority) {
        this.priority = priority;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public void setUpdated(Date updated) {
        this.updated = updated;
    }

    public void setRemoved(Date removed) {
        this.removed = removed;
    }

    @Override
    public String toString() {
        return ReflectionToStringBuilderUtils.reflectOnlySelectedFields(this, "uuid", "title", "type", "enabled", "startDate", "endDate", "priority");
    }
}
