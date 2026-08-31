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

import java.util.List;

import org.apache.cloudstack.api.command.admin.reverseproxy.ListReverseProxyHostsCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.CheckInstanceProxyNameCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.ListInstanceProxiesCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.ListReverseProxyDomainsCmd;
import org.apache.cloudstack.api.response.InstanceProxyResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ReverseProxyDomainResponse;
import org.apache.cloudstack.framework.config.ConfigKey;

import com.cloud.utils.component.PluggableService;

public interface ReverseProxyService extends PluggableService {
    ConfigKey<Boolean> ReverseProxyEnabled = new ConfigKey<>("Advanced", Boolean.class, "reverseproxy.enabled", "false",
            "When set to true, enables the Reverse Proxy integration that allows users to expose instances on the shared network "
                    + "through an external Nginx Proxy Manager server.", true);

    ConfigKey<String> ReverseProxyDomain = new ConfigKey<>("Advanced", String.class, "reverseproxy.domain", "",
            "Deprecated, the configured value is migrated to a public reverse proxy domain suffix on upgrade. Use the "
                    + "reverse proxy domain suffix management APIs instead. The domain suffix exposed by the reverse proxy "
                    + "integration, for example 'cloud.company.com'. A wildcard DNS record for '*.<domain>' pointing to the "
                    + "Nginx Proxy Manager server is expected to be configured.", true);

    ConfigKey<String> ReverseProxyNetworkId = new ConfigKey<>("Advanced", String.class, "reverseproxy.network.id", "",
            "The UUID of the shared network that instances must be attached to in order to be proxied and that the Nginx Proxy "
                    + "Manager server can reach. If not set, the default NIC of the instance is used provided it is on a shared network.", true);

    ConfigKey<String> ReverseProxyNpmUrl = new ConfigKey<>("Advanced", String.class, "reverseproxy.npm.url", "",
            "The base URL of the Nginx Proxy Manager admin API, for example 'http://npm.company.com:81'.", true);

    ConfigKey<String> ReverseProxyNpmUser = new ConfigKey<>("Advanced", String.class, "reverseproxy.npm.user", "",
            "The user (email) of a dedicated Nginx Proxy Manager account used by CloudStack to manage proxy hosts. "
                    + "The account must have permissions to manage proxy hosts.", true);

    ConfigKey<String> ReverseProxyNpmPassword = new ConfigKey<>("Advanced", String.class, "reverseproxy.npm.password", "",
            "The password of the dedicated Nginx Proxy Manager account used by CloudStack to manage proxy hosts.", true);

    ConfigKey<Boolean> ReverseProxyNpmValidateSsl = new ConfigKey<>("Advanced", Boolean.class, "reverseproxy.npm.validate.ssl", "true",
            "When set to true, validates the SSL certificate of the Nginx Proxy Manager admin API when connecting over https.", true);

    ConfigKey<Integer> ReverseProxyNpmRequestTimeout = new ConfigKey<>("Advanced", Integer.class, "reverseproxy.npm.api.request.timeout", "10",
            "The Nginx Proxy Manager API request timeout in seconds.", true);

    ConfigKey<Integer> ReverseProxyNpmCertificateId = new ConfigKey<>("Advanced", Integer.class, "reverseproxy.npm.certificate.id", "0",
            "Deprecated, configure the certificate on the reverse proxy domain suffixes instead.", true);

    ConfigKey<Boolean> ReverseProxyForceHttps = new ConfigKey<>("Advanced", Boolean.class, "reverseproxy.npm.force.https", "false",
            "When set to true, proxy hosts created in Nginx Proxy Manager redirect HTTP traffic to HTTPS.", true);

    ConfigKey<String> ReverseProxyReservedNames = new ConfigKey<>("Advanced", String.class, "reverseproxy.reserved.names", "",
            "A comma separated list of reserved names that users are not allowed to use as reverse proxy host names.", true);

    /**
     * Checks if the reverse proxy integration is enabled
     * @return true if enabled
     */
    boolean isEnabled();

    /**
     * Creates a reverse proxy host in the Nginx Proxy Manager server for the given instance and persists the mapping.
     * The public side serves the instance's FQDN through Nginx Proxy Manager; the selected protocol is the scheme
     * used to forward requests to the instance.
     *
     * @param vmId the id of the instance
     * @param name the user chosen name (prefix of the FQDN)
     * @param protocol the backend protocol (http or https) used by Nginx Proxy Manager to forward to the instance
     * @param port the port to expose on the instance
     * @param domainId the id of the reverse proxy domain suffix to expose the instance on, null when only one suffix exists
     * @return the created proxy host
     */
    ReverseProxyHost createInstanceProxy(Long vmId, String name, String protocol, Integer port, Long domainId);

    /**
     * Removes the reverse proxy host from the Nginx Proxy Manager server and deletes the mapping
     * @param proxyId the id of the proxy host mapping
     */
    void deleteInstanceProxy(Long proxyId);

    /**
     * Lists proxy hosts according to the given list command (with ACL scoping)
     * @param cmd the list command
     * @return list of proxy host responses
     */
    ListResponse<InstanceProxyResponse> listInstanceProxies(ListInstanceProxiesCmd cmd);

    /**
     * Lists all proxy hosts of all accounts according to the given list command (admin only)
     * @param cmd the list command
     * @return list of proxy host responses
     */
    ListResponse<InstanceProxyResponse> listReverseProxyHosts(ListReverseProxyHostsCmd cmd);

    /**
     * Checks whether the given name is available for use as a reverse proxy host on the given domain suffix
     * @param name the user chosen name (prefix of the FQDN)
     * @param domainId the id of the reverse proxy domain suffix, null when only one suffix exists
     * @param cmd the check command
     * @return availability information
     */
    org.apache.cloudstack.api.response.CheckInstanceProxyNameResponse checkInstanceProxyName(String name, Long domainId, CheckInstanceProxyNameCmd cmd);

    /**
     * Adds a reverse proxy domain suffix with the given grants
     * @param domain the domain suffix, for example 'cloud.company.com'
     * @param description an optional description
     * @param isPublic when true the suffix can be used by all accounts, otherwise only by granted accounts and networks
     * @param npmCertificateId the optional Nginx Proxy Manager certificate id used for TLS termination
     * @param accountIds the accounts granted access to the suffix
     * @param networkIds the shared networks granted access to the suffix
     * @return the created domain suffix
     */
    ReverseProxyDomainVO addReverseProxyDomain(String domain, String description, Boolean isPublic, Long npmCertificateId,
            List<Long> accountIds, List<Long> networkIds);

    /**
     * Updates a reverse proxy domain suffix. Null parameters leave the values unchanged; the grants are replaced
     * when accounts or networks are given.
     */
    ReverseProxyDomainVO updateReverseProxyDomain(Long id, String description, Boolean isPublic, Long npmCertificateId,
            List<Long> accountIds, List<Long> networkIds);

    /**
     * Deletes a reverse proxy domain suffix and all proxy hosts exposed on it
     * @param id the id of the domain suffix
     */
    void deleteReverseProxyDomain(Long id);

    /**
     * Lists the reverse proxy domain suffixes available to the caller (with ACL scoping), all suffixes for admins
     * @param cmd the list command
     * @return list of domain suffix responses
     */
    ListResponse<ReverseProxyDomainResponse> listReverseProxyDomains(ListReverseProxyDomainsCmd cmd);

    /**
     * Builds the API response for a reverse proxy domain suffix
     * @param domain the domain suffix
     * @param showDetails when true grants and proxy counts are included
     * @return the response object
     */
    ReverseProxyDomainResponse createReverseProxyDomainResponse(ReverseProxyDomainVO domain, boolean showDetails);

    /**
     * Deletes proxy hosts mapped to the given VM (called when the VM is expunged)
     * @param vmId the id of the expunged instance
     */
    void cleanupProxiesForVm(Long vmId);

    /**
     * Builds the API response for a proxy host
     * @param proxy the proxy host
     * @return the response object
     */
    InstanceProxyResponse createInstanceProxyResponse(ReverseProxyHost proxy);
}
