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

@APICommand(name = "updateReverseProxyDomain",
        description = "Updates a reverse proxy domain suffix. When accounts or shared networks are given, the grants of "
                + "the domain suffix are replaced with the given lists.",
        responseObject = ReverseProxyDomainResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.22.2.0",
        authorized = {RoleType.Admin})
public class UpdateReverseProxyDomainCmd extends BaseCmd {

    @Inject
    public ReverseProxyService reverseProxyService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, required = true, entityType = ReverseProxyDomainResponse.class,
            description = "The ID of the reverse proxy domain suffix to update")
    private Long id;

    @Parameter(name = ApiConstants.DESCRIPTION, type = CommandType.STRING, required = false,
            description = "An optional description of the domain suffix")
    private String description;

    @Parameter(name = ApiConstants.IS_PUBLIC, type = CommandType.BOOLEAN, required = false,
            description = "When true, the domain suffix can be used by all accounts, otherwise only by granted accounts "
                    + "and shared networks")
    private Boolean isPublic;

    @Parameter(name = "npmcertificateid", type = CommandType.LONG, required = false,
            description = "The id of the Nginx Proxy Manager certificate used for TLS termination of this domain suffix, "
                    + "0 to remove the certificate and auto-discover the certificate covering '*.<domain>'")
    private Long npmCertificateId;

    @Parameter(name = ApiConstants.ACCOUNT_IDS, type = CommandType.LIST, collectionType = CommandType.UUID,
            entityType = AccountResponse.class, required = false,
            description = "The list of accounts granted access to the domain suffix, replaces the current grants")
    private List<Long> accountIds;

    @Parameter(name = ApiConstants.NETWORK_IDS, type = CommandType.LIST, collectionType = CommandType.UUID,
            entityType = NetworkResponse.class, required = false,
            description = "The list of shared networks granted access to the domain suffix, replaces the current grants")
    private List<Long> networkIds;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
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
            final ReverseProxyDomainVO result = reverseProxyService.updateReverseProxyDomain(id, description, isPublic,
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
