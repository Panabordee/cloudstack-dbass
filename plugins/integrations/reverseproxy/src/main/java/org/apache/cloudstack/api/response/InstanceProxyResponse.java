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

import java.util.Date;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.reverseproxy.ReverseProxyHost;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@EntityReference(value = {ReverseProxyHost.class})
public class InstanceProxyResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "the ID of the instance proxy")
    private String id;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "the name (prefix) of the instance proxy host")
    private String name;

    @SerializedName("fqdn")
    @Param(description = "the fully qualified domain name of the instance proxy host")
    private String fqdn;

    @SerializedName("url")
    @Param(description = "the public URL at which the instance is exposed")
    private String url;

    @SerializedName(ApiConstants.VIRTUAL_MACHINE_ID)
    @Param(description = "the ID of the proxied instance")
    private String virtualMachineId;

    @SerializedName(ApiConstants.VIRTUAL_MACHINE_NAME)
    @Param(description = "the name of the proxied instance")
    private String virtualMachineName;

    @SerializedName(ApiConstants.IP_ADDRESS)
    @Param(description = "the instance IP address the proxy forwards to")
    private String ipAddress;

    @SerializedName(ApiConstants.PROTOCOL)
    @Param(description = "the protocol (scheme) used to forward requests to the instance")
    private String protocol;

    @SerializedName(ApiConstants.PORT)
    @Param(description = "the port exposed on the instance")
    private Integer port;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "the state of the instance proxy")
    private String state;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "the date the instance proxy was created")
    private Date created;

    @SerializedName(ApiConstants.ACCOUNT)
    @Param(description = "the account owning the instance proxy")
    private String account;

    @SerializedName(ApiConstants.DOMAIN_ID)
    @Param(description = "the domain ID of the instance proxy")
    private String domainId;

    @SerializedName(ApiConstants.DOMAIN)
    @Param(description = "the domain of the instance proxy")
    private String domain;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFqdn() {
        return fqdn;
    }

    public void setFqdn(String fqdn) {
        this.fqdn = fqdn;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getVirtualMachineId() {
        return virtualMachineId;
    }

    public void setVirtualMachineId(String virtualMachineId) {
        this.virtualMachineId = virtualMachineId;
    }

    public String getVirtualMachineName() {
        return virtualMachineName;
    }

    public void setVirtualMachineName(String virtualMachineName) {
        this.virtualMachineName = virtualMachineName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }
}
