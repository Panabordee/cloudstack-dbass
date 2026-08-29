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

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.InstanceProxyResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.reverseproxy.ReverseProxyService;

@APICommand(name = "listReverseProxyHosts",
        description = "Lists all instance reverse proxy hosts (Nginx Proxy Manager proxy hosts created by CloudStack) of all accounts",
        responseObject = InstanceProxyResponse.class,
        responseView = ResponseView.Full,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.22.0.0",
        authorized = {RoleType.Admin})
public class ListReverseProxyHostsCmd extends BaseListCmd {

    @Inject
    public ReverseProxyService reverseProxyService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class,
            description = "List proxy hosts only for the specified domain")
    private Long domainId;

    @Parameter(name = ApiConstants.IS_RECURSIVE, type = CommandType.BOOLEAN,
            description = "Defaults to false, but if true, lists the proxy hosts of the specified domain and all its sub-domains")
    private Boolean isRecursive;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID, type = CommandType.UUID, entityType = UserVmResponse.class,
            description = "List proxy hosts only for the specified instance")
    private Long virtualMachineId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getDomainId() {
        return domainId;
    }

    public Boolean isRecursive() {
        return isRecursive;
    }

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() throws ServerApiException {
        final ListResponse<InstanceProxyResponse> response = reverseProxyService.listReverseProxyHosts(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
