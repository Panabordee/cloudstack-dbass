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

import org.apache.cloudstack.api.Identity;
import org.apache.cloudstack.api.InternalIdentity;

import java.util.Date;

public interface Announcement extends InternalIdentity, Identity {

    String getTitle();

    String getMessage();

    String getType();

    boolean getEnabled();

    boolean getClosable();

    boolean getPersistDismissal();

    Date getStartDate();

    Date getEndDate();

    int getPriority();

    Date getCreated();

    Date getUpdated();

    Date getRemoved();

    void setId(Long id);

    void setUuid(String uuid);

    void setTitle(String title);

    void setMessage(String message);

    void setType(String type);

    void setEnabled(boolean enabled);

    void setClosable(boolean closable);

    void setPersistDismissal(boolean persistDismissal);

    void setStartDate(Date startDate);

    void setEndDate(Date endDate);

    void setPriority(int priority);
}
