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

package org.apache.cloudstack.api.command.admin.reverseproxy;

import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.ReverseProxyDomainResponse;
import org.apache.cloudstack.reverseproxy.ReverseProxyDomainVO;
import org.apache.cloudstack.reverseproxy.ReverseProxyService;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.user.Account;

@APICommand(name = "addReverseProxyDomain",
        description = "Adds a reverse proxy domain suffix that users can expose their instances on. Access can be granted "
                + "to accounts and shared networks, when the suffix is public all accounts can use it.",
        responseObject = ReverseProxyDomainResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.22.2.0",
        authorized = {RoleType.Admin})
public class AddReverseProxyDomainCmd extends BaseCmd {

    @Inject
    public ReverseProxyService reverseProxyService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.DOMAIN, type = CommandType.STRING, required = true,
            description = "The domain suffix, for example 'cloud.company.com'. Instances are exposed as '<name>.<domain>'")
    private String domain;

    @Parameter(name = ApiConstants.DESCRIPTION, type = CommandType.STRING, required = false,
            description = "An optional description of the domain suffix")
    private String description;

    @Parameter(name = ApiConstants.IS_PUBLIC, type = CommandType.BOOLEAN, required = false,
            description = "When true, the domain suffix can be used by all accounts (default false, only granted accounts "
                    + "and shared networks can use it)")
    private Boolean isPublic;

    @Parameter(name = "npmcertificateid", type = CommandType.LONG, required = false,
            description = "The id of the Nginx Proxy Manager certificate used for TLS termination of this domain suffix. "
                    + "When not set, the certificate is auto-discovered by looking for a certificate covering '*.<domain>'")
    private Long npmCertificateId;

    @Parameter(name = ApiConstants.ACCOUNT_IDS, type = CommandType.LIST, collectionType = CommandType.UUID,
            entityType = AccountResponse.class, required = false,
            description = "The list of accounts granted access to the domain suffix")
    private List<Long> accountIds;

    @Parameter(name = ApiConstants.NETWORK_IDS, type = CommandType.LIST, collectionType = CommandType.UUID,
            entityType = NetworkResponse.class, required = false,
            description = "The list of shared networks granted access to the domain suffix")
    private List<Long> networkIds;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public String getDomain() {
        return domain;
    }

    public String getDescription() {
        return description;
    }

    public Boolean getIsPublic() {
        return isPublic;
    }

    public Long getNpmCertificateId() {
        return npmCertificateId;
    }

    public List<Long> getAccountIds() {
        return accountIds;
    }

    public List<Long> getNetworkIds() {
        return networkIds;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute() throws ServerApiException {
        try {
            final ReverseProxyDomainVO result = reverseProxyService.addReverseProxyDomain(domain, description, isPublic,
                    npmCertificateId, accountIds, networkIds);
            final ReverseProxyDomainResponse response =
                    reverseProxyService.createReverseProxyDomainResponse(result, true);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (final CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }
}
