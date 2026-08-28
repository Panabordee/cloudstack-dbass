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
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.InstanceProxyResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.reverseproxy.ReverseProxyHost;
import org.apache.cloudstack.reverseproxy.ReverseProxyService;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;

@APICommand(name = "addInstanceProxy",
        description = "Exposes an instance through the reverse proxy integration (Nginx Proxy Manager). The instance is exposed "
                + "as '<name>.<reverseproxy.domain>' and the selected protocol is used to forward requests to the instance.",
        responseObject = InstanceProxyResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.22.0.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class AddInstanceProxyCmd extends BaseCmd {

    @Inject
    public ReverseProxyService reverseProxyService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @ACL(accessType = SecurityChecker.AccessType.OperateEntry)
    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID, type = CommandType.UUID, required = true, entityType = UserVmResponse.class,
            description = "The ID of the instance to expose")
    private Long virtualMachineId;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true,
            description = "The desired name (prefix of the proxy host name), for example 'my-web' for 'my-web.cloud.company.com'")
    private String name;

    @Parameter(name = ApiConstants.PROTOCOL, type = CommandType.STRING, required = true,
            description = "The protocol used to forward requests to the instance: http or https")
    private String protocol;

    @Parameter(name = ApiConstants.PORT, type = CommandType.INTEGER, required = true,
            description = "The port to expose on the instance")
    private Integer port;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    public String getName() {
        return name;
    }

    public String getProtocol() {
        return protocol;
    }

    public Integer getPort() {
        return port;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute() {
        if (virtualMachineId == null) {
            throw new InvalidParameterValueException("Instance ID is required");
        }
        final ReverseProxyHost proxy = reverseProxyService.createInstanceProxy(virtualMachineId, name, protocol, port);
        if (proxy == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to create the instance proxy");
        }
        final InstanceProxyResponse response = reverseProxyService.createInstanceProxyResponse(proxy);
        response.setResponseName(getCommandName());
        response.setObjectName("instanceproxy");
        setResponseObject(response);
    }
}
