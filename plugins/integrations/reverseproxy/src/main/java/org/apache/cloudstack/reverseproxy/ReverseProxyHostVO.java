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
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.cloud.utils.db.GenericDao;

@Entity
@Table(name = "reverse_proxy_host")
public class ReverseProxyHostVO implements ReverseProxyHost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "fqdn", nullable = false)
    private String fqdn;

    @Column(name = "vm_instance_id", nullable = false)
    private long vmInstanceId;

    @Column(name = "network_id", nullable = false)
    private long networkId;

    @Column(name = "ip_address", nullable = false)
    private String ipAddress;

    @Column(name = "forward_scheme", nullable = false)
    private String forwardScheme;

    @Column(name = "forward_port", nullable = false)
    private int forwardPort;

    @Column(name = "npm_proxy_host_id", nullable = false)
    private long npmProxyHostId;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "domain_id", nullable = false)
    private long domainId;

    @Column(name = "state", nullable = false)
    private State state;

    @Column(name = GenericDao.CREATED_COLUMN)
    private Date created;

    @Column(name = GenericDao.REMOVED_COLUMN)
    private Date removed;

    public ReverseProxyHostVO() {
        this.uuid = UUID.randomUUID().toString();
        this.state = State.Active;
        this.created = new Date();
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getFqdn() {
        return fqdn;
    }

    public void setFqdn(String fqdn) {
        this.fqdn = fqdn;
    }

    @Override
    public long getVmInstanceId() {
        return vmInstanceId;
    }

    public void setVmInstanceId(long vmInstanceId) {
        this.vmInstanceId = vmInstanceId;
    }

    @Override
    public long getNetworkId() {
        return networkId;
    }

    public void setNetworkId(long networkId) {
        this.networkId = networkId;
    }

    @Override
    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    @Override
    public String getForwardScheme() {
        return forwardScheme;
    }

    public void setForwardScheme(String forwardScheme) {
        this.forwardScheme = forwardScheme;
    }

    @Override
    public int getForwardPort() {
        return forwardPort;
    }

    public void setForwardPort(int forwardPort) {
        this.forwardPort = forwardPort;
    }

    @Override
    public long getNpmProxyHostId() {
        return npmProxyHostId;
    }

    public void setNpmProxyHostId(long npmProxyHostId) {
        this.npmProxyHostId = npmProxyHostId;
    }

    @Override
    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    @Override
    public long getDomainId() {
        return domainId;
    }

    public void setDomainId(long domainId) {
        this.domainId = domainId;
    }

    @Override
    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    @Override
    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    @Override
    public Class<?> getEntityType() {
        return ReverseProxyHost.class;
    }

    @Override
    public String toString() {
        return String.format("ReverseProxyHost{id=%d, uuid=%s, name=%s, fqdn=%s, vm=%d, ip=%s, scheme=%s, port=%d, npmHostId=%d}",
                id, uuid, name, fqdn, vmInstanceId, ipAddress, forwardScheme, forwardPort, npmProxyHostId);
    }
}
