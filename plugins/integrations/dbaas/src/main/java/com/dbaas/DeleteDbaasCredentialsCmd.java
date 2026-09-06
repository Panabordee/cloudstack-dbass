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

import org.apache.cloudstack.context.CallContext;
import javax.inject.Inject;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.SuccessResponse;

import com.cloud.utils.db.EntityManager;
import com.cloud.vm.VirtualMachine;
// (VirtualMachine used below for the expunge-time credential cleanup)

// The plugin has no expunge hook, so the Database page (which destroys its
// instances itself) calls this once the destroy job succeeds. Wiping the
// stored credentials server-side keeps the retention promise the cleanup SQL
// in the schema resource documents, without touching the database by hand.
@APICommand(name = "deleteDbaasCredentials",
        description = "Deletes the stored database credentials of a DBaaS instance",
        responseObject = SuccessResponse.class,
        responseHasSensitiveInfo = false)
public class DeleteDbaasCredentialsCmd extends BaseCmd {

    private static final String s_name = "deletedbaascredentialsresponse";

    @Inject
    private EntityManager _entityMgr;

    @Inject
    private DbaasManager _dbaasManager;

    // Deliberately a STRING, not CommandType.UUID: the dispatcher resolves
    // UUID params through the entity lookup, which fails outright once the
    // instance row has been expunged out of vm_instance -- exactly the case
    // this cleanup call covers. The raw uuid is matched directly against
    // dbaas_credentials.vm_id instead.
    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.STRING,
            required = true,
            description = "the UUID of the DBaaS instance whose stored credentials should be deleted")
    private String virtualMachineId;

    public String getVirtualMachineId() {
        return virtualMachineId;
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    // ACL is best-effort by design: the target is usually a destroyed or
    // already-expunged instance whose row may be gone, in which case the
    // caller's own account is the strongest available check -- deleting
    // stale rows for an instance that no longer exists is exactly the point.
    @Override
    public long getEntityOwnerId() {
        final VirtualMachine vm = _entityMgr.findByUuidIncludingRemoved(VirtualMachine.class, getVirtualMachineId());
        if (vm != null) {
            return vm.getAccountId();
        }
        return CallContext.current().getCallingAccountId();
    }

    @Override
    public void execute() throws ServerApiException {
        final int deleted = _dbaasManager.deleteCredentialsForVm(getVirtualMachineId());
        logger.info("deleteDbaasCredentials removed {} stored credential row(s)", deleted);
        SuccessResponse response = new SuccessResponse();
        response.setSuccess(true);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
