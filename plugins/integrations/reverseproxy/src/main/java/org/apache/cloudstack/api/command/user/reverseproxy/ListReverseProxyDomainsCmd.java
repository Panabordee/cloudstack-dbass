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

package org.apache.cloudstack.api.command.user.reverseproxy;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ReverseProxyDomainResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.reverseproxy.ReverseProxyService;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.user.Account;

@APICommand(name = "listReverseProxyDomains",
        description = "Lists the reverse proxy domain suffixes that can be used to expose instances. Users only get the "
                + "domain suffixes they are allowed to use, admins get all domain suffixes with their grants.",
        responseObject = ReverseProxyDomainResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.22.2.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ListReverseProxyDomainsCmd extends BaseCmd {

    @Inject
    public ReverseProxyService reverseProxyService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = ReverseProxyDomainResponse.class,
            required = false, description = "The ID of the reverse proxy domain suffix")
    private Long id;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID, type = CommandType.UUID, entityType = UserVmResponse.class,
            required = false, description = "The ID of the instance that will be exposed, when given the domain suffixes "
                    + "granted to the shared networks of the instance are included as well")
    private Long virtualMachineId;

    @Parameter(name = ApiConstants.KEYWORD, type = CommandType.STRING, required = false,
            description = "Filter the domain suffixes by keyword")
    private String keyword;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    public String getKeyword() {
        return keyword;
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
            final ListResponse<ReverseProxyDomainResponse> response = reverseProxyService.listReverseProxyDomains(this);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (final CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }
}
