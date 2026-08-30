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
import java.util.List;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.reverseproxy.ReverseProxyDomain;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
@EntityReference(value = {ReverseProxyDomain.class})
public class ReverseProxyDomainResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "the ID of the reverse proxy domain suffix")
    private String id;

    @SerializedName(ApiConstants.DOMAIN)
    @Param(description = "the domain suffix exposed to users")
    private String domain;

    @SerializedName(ApiConstants.DESCRIPTION)
    @Param(description = "the description of the domain suffix")
    private String description;

    @SerializedName(ApiConstants.IS_PUBLIC)
    @Param(description = "true if the domain suffix can be used by all accounts, false if only granted accounts and "
            + "shared networks can use it")
    private Boolean isPublic;

    @SerializedName("npmcertificateid")
    @Param(description = "the id of the Nginx Proxy Manager certificate used for TLS termination of this domain suffix")
    private Long npmCertificateId;

    @SerializedName(ApiConstants.ACCOUNTS)
    @Param(description = "the list of accounts granted access to the domain suffix", since = "4.22.2.0")
    private List<String> accounts;

    @SerializedName("accountids")
    @Param(description = "the list of account ids granted access to the domain suffix", since = "4.22.2.0")
    private List<String> accountIds;

    @SerializedName("networks")
    @Param(description = "the list of shared networks granted access to the domain suffix", since = "4.22.2.0")
    private List<String> networks;

    @SerializedName("networkids")
    @Param(description = "the list of shared network ids granted access to the domain suffix", since = "4.22.2.0")
    private List<String> networkIds;

    @SerializedName("proxycount")
    @Param(description = "the number of proxy hosts on the domain suffix", since = "4.22.2.0")
    private Long proxyCount;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "the date the domain suffix was created")
    private Date created;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getPublic() {
        return isPublic;
    }

    public void setPublic(Boolean isPublic) {
        this.isPublic = isPublic;
    }

    public Long getNpmCertificateId() {
        return npmCertificateId;
    }

    public void setNpmCertificateId(Long npmCertificateId) {
        this.npmCertificateId = npmCertificateId;
    }

    public List<String> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<String> accounts) {
        this.accounts = accounts;
    }

    public List<String> getAccountIds() {
        return accountIds;
    }

    public void setAccountIds(List<String> accountIds) {
        this.accountIds = accountIds;
    }

    public List<String> getNetworks() {
        return networks;
    }

    public void setNetworks(List<String> networks) {
        this.networks = networks;
    }

    public List<String> getNetworkIds() {
        return networkIds;
    }

    public void setNetworkIds(List<String> networkIds) {
        this.networkIds = networkIds;
    }

    public Long getProxyCount() {
        return proxyCount;
    }

    public void setProxyCount(Long proxyCount) {
        this.proxyCount = proxyCount;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }
}
