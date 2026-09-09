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

@APICommand(name = "resetDatabasePassword",
        description = "Resets the password of an existing database user on the specified DBaaS VM",
        responseObject = DbaasResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true)
public class ResetDatabasePasswordCmd extends BaseCmd {

    private static final String s_name = "resetdatabasepasswordresponse";

    @Inject
    private EntityManager _entityMgr;

    @Inject
    private DbaasManager _dbaasManager;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the VM (deployed from a dbaas-* template) the database user lives on")
    private Long virtualMachineId;

    @Parameter(name = "dbusername", type = CommandType.STRING, required = true,
            description = "name of the existing database user whose password is being reset")
    private String dbUsername;

    public Long getVirtualMachineId() {
        return virtualMachineId;
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
        // Owner comes from the VM, so the same account-level ACL that guards
        // createDatabase guards rotating a password on it.
        return vm.getAccountId();
    }

    @Override
    public void execute() throws ServerApiException {
        // Same hole 8f2ad48295 closed elsewhere tonight: getEntityOwnerId
        // returns the TARGET VM's owner, which makes the framework's entity
        // access check pass for any caller. This command was implemented
        // after that fix and was missed -- caught while implementing item C
        // (MASTER-PLAN, 2026-09-09), not by re-running the matrix, so treat
        // this as newly-discovered rather than re-verified.
        _dbaasManager.checkCallerOwnsVm(getVirtualMachineId());
        DbaasResponse response = _dbaasManager.resetDatabasePassword(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
