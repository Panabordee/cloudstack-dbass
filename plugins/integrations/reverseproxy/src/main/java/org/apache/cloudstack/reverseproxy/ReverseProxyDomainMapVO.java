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

package org.apache.cloudstack.reverseproxy;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.cloud.utils.db.GenericDao;

/**
 * Grants access to a reverse proxy domain suffix: either to an account or to a shared network.
 * Exactly one of accountId and networkId is set.
 */
@Entity
@Table(name = "reverse_proxy_domain_map")
public class ReverseProxyDomainMapVO {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "domain_id", nullable = false)
    private long domainId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "network_id")
    private Long networkId;

    @Column(name = GenericDao.CREATED_COLUMN)
    private Date created;

    @Column(name = GenericDao.REMOVED_COLUMN)
    private Date removed;

    public ReverseProxyDomainMapVO() {
        this.created = new Date();
    }

    public ReverseProxyDomainMapVO(final long domainId, final Long accountId, final Long networkId) {
        this();
        this.domainId = domainId;
        this.accountId = accountId;
        this.networkId = networkId;
    }

    public long getId() {
        return id;
    }

    public long getDomainId() {
        return domainId;
    }

    public void setDomainId(long domainId) {
        this.domainId = domainId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getNetworkId() {
        return networkId;
    }

    public void setNetworkId(Long networkId) {
        this.networkId = networkId;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public Date getRemoved() {
        return removed;
    }

    public void setRemoved(Date removed) {
        this.removed = removed;
    }

    @Override
    public String toString() {
        return String.format("ReverseProxyDomainMap{id=%d, domain=%d, account=%s, network=%s}",
                id, domainId, accountId, networkId);
    }
}
