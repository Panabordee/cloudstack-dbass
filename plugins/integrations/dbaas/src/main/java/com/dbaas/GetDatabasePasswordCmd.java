package com.dbaas;

import javax.inject.Inject;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.UserVmResponse;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.VirtualMachine;

@APICommand(name = "getDatabasePassword",
        description = "Retrieves the stored password for a database user created on the specified DBaaS VM",
        responseObject = DbaasResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true)
public class GetDatabasePasswordCmd extends BaseCmd {

    private static final String s_name = "getdatabasepasswordresponse";

    @Inject
    private EntityManager _entityMgr;

    @Inject
    private DbaasManager _dbaasManager;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the VM (deployed from a dbaas-* template) the credential was stored against")
    private Long virtualMachineId;

    @Parameter(name = "dbusername", type = CommandType.STRING, required = false,
            description = "which stored credential to retrieve, if the VM has more than one; " +
                    "defaults to the most recently created one")
    private String dbUsername;

    // Credentials are per (instance, role): 'owner' for DDL and writes,
    // 'readonly' for browse and query. Defaults to 'owner' -- Show Password
    // keeps its current behaviour unless the caller asks for the readonly one.
    @Parameter(name = "dbrole", type = CommandType.STRING, required = false,
            description = "which role's credential to retrieve: owner (default) or readonly")
    private String dbRole;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    public String getDbRole() {
        return dbRole;
    }

    public String getDbUsername() {
        return dbUsername;
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
        // Same account-level ACL as createDatabase/resetDatabasePassword: only
        // the VM's own account (or an admin) can decrypt its credentials.
        return vm.getAccountId();
    }

    @Override
    public void execute() throws ServerApiException {
        DbaasResponse response = _dbaasManager.getDatabasePassword(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
