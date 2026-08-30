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

@APICommand(name = "createDatabase",
        description = "Provisions a database and user on the specified DBaaS VM",
        responseObject = DbaasResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true)
public class CreateDatabaseCmd extends BaseCmd {

    private static final String s_name = "createdatabaseresponse";

    @Inject
    private EntityManager _entityMgr;

    @Inject
    private DbaasManager _dbaasManager;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the VM (deployed from a dbaas-* template) to provision the database on")
    private Long virtualMachineId;

    @Parameter(name = "dbname", type = CommandType.STRING, required = true,
            description = "name of the database to create")
    private String dbName;

    @Parameter(name = "dbusername", type = CommandType.STRING, required = true,
            description = "name of the database user to create")
    private String dbUsername;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    public String getDbName() {
        return dbName;
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
        // Real CloudStack ACL enforcement for free here — a non-admin caller
        // can only run this against a VM their own account owns. This is
        // something the Extensions Framework approach never gave us.
        return vm.getAccountId();
    }

    @Override
    public void execute() throws ServerApiException {
        DbaasResponse response = _dbaasManager.createDatabase(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
