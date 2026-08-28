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

package org.apache.cloudstack.reverseproxy.client;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NpmProxyHost {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("domain_names")
    private List<String> domainNames;

    @JsonProperty("forward_scheme")
    private String forwardScheme;

    @JsonProperty("forward_host")
    private String forwardHost;

    @JsonProperty("forward_port")
    private Integer forwardPort;

    @JsonProperty("certificate_id")
    private Integer certificateId;

    @JsonProperty("ssl_forced")
    private Boolean sslForced;

    @JsonProperty("http2_support")
    private Boolean http2Support;

    @JsonProperty("hsts_enabled")
    private Boolean hstsEnabled;

    @JsonProperty("block_exploits")
    private Boolean blockExploits;

    @JsonProperty("caching_enabled")
    private Boolean cachingEnabled;

    @JsonProperty("allow_websocket_upgrade")
    private Boolean allowWebsocketUpgrade;

    @JsonProperty("enabled")
    private Boolean enabled;

    @JsonProperty("meta")
    private Map<String, Object> meta;

    @JsonProperty("owner_user_id")
    private Long ownerUserId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public List<String> getDomainNames() {
        return domainNames;
    }

    public void setDomainNames(List<String> domainNames) {
        this.domainNames = domainNames;
    }

    public String getForwardScheme() {
        return forwardScheme;
    }

    public void setForwardScheme(String forwardScheme) {
        this.forwardScheme = forwardScheme;
    }

    public String getForwardHost() {
        return forwardHost;
    }

    public void setForwardHost(String forwardHost) {
        this.forwardHost = forwardHost;
    }

    public Integer getForwardPort() {
        return forwardPort;
    }

    public void setForwardPort(Integer forwardPort) {
        this.forwardPort = forwardPort;
    }

    public Integer getCertificateId() {
        return certificateId;
    }

    public void setCertificateId(Integer certificateId) {
        this.certificateId = certificateId;
    }

    public Boolean getSslForced() {
        return sslForced;
    }

    public void setSslForced(Boolean sslForced) {
        this.sslForced = sslForced;
    }

    public Boolean getHttp2Support() {
        return http2Support;
    }

    public void setHttp2Support(Boolean http2Support) {
        this.http2Support = http2Support;
    }

    public Boolean getHstsEnabled() {
        return hstsEnabled;
    }

    public void setHstsEnabled(Boolean hstsEnabled) {
        this.hstsEnabled = hstsEnabled;
    }

    public Boolean getBlockExploits() {
        return blockExploits;
    }

    public void setBlockExploits(Boolean blockExploits) {
        this.blockExploits = blockExploits;
    }

    public Boolean getCachingEnabled() {
        return cachingEnabled;
    }

    public void setCachingEnabled(Boolean cachingEnabled) {
        this.cachingEnabled = cachingEnabled;
    }

    public Boolean getAllowWebsocketUpgrade() {
        return allowWebsocketUpgrade;
    }

    public void setAllowWebsocketUpgrade(Boolean allowWebsocketUpgrade) {
        this.allowWebsocketUpgrade = allowWebsocketUpgrade;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public void setMeta(Map<String, Object> meta) {
        this.meta = meta;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    @Override
    public String toString() {
        return String.format("NpmProxyHost{id=%s, domainNames=%s, forwardScheme=%s, forwardHost=%s, forwardPort=%s, certificateId=%s, enabled=%s}",
                id, domainNames, forwardScheme, forwardHost, forwardPort, certificateId, enabled);
    }
}
