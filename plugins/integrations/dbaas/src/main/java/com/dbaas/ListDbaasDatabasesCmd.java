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
package com.dbaas;

import javax.inject.Inject;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.UserVmResponse;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.VirtualMachine;

/** Which databases exist on an instance. The console needs this to offer a
 *  choice: createDatabase can be called on the same instance repeatedly, so
 *  one instance commonly holds more than one database, and until this existed
 *  the console silently acted on whichever one the guest had configured last. */
@APICommand(name = "listDbaasDatabases",
        description = "Lists the databases provisioned on a DBaaS instance",
        responseObject = DbaasDatabaseResponse.class,
        responseHasSensitiveInfo = false)
public class ListDbaasDatabasesCmd extends BaseListCmd {

    private static final String s_name = "listdbaasdatabasesresponse";

    @Inject
    private EntityManager _entityMgr;

    @Inject
    private DbaasManager _dbaasManager;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the DBaaS instance")
    private Long virtualMachineId;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        VirtualMachine vm = _entityMgr.findById(VirtualMachine.class, getVirtualMachineId());
        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find a VM with id " + getVirtualMachineId());
        }
        return vm.getAccountId();
    }

    @Override
    public void execute() throws ServerApiException {
        // getEntityOwnerId returns the target's owner, which makes the
        // framework's entity check pass for any caller -- the same hole
        // 8f2ad48295 closed elsewhere. Check the caller explicitly.
        _dbaasManager.checkCallerOwnsVm(getVirtualMachineId());
        ListResponse<DbaasDatabaseResponse> response = new ListResponse<>();
        response.setResponses(_dbaasManager.listDatabases(getVirtualMachineId()));
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
