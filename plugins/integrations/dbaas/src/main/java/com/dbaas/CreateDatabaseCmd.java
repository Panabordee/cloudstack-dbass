package com.dbaas;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiErrorCode;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.UserVmResponse;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.VirtualMachine;

@APICommand(authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User}, name = "createDatabase",
        description = "Provisions a database and user on the specified DBaaS VM",
        responseObject = DbaasResponse.class,
        // A caller may supply the database password, so the request itself
        // carries a secret and must not be logged verbatim.
        requestHasSensitiveInfo = true,
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

    // Optional: the UI lets the database user be omitted (its banner explains
    // the default), and DbaasManagerImpl.createDatabase() then defaults it to
    // the database name. This must stay required=false -- the UI strips
    // undefined params before sending, so an omitted dbusername never reaches
    // the API layer at all, and required=true would 431 before the defaulting
    // code ever ran.
    @Parameter(name = "dbusername", type = CommandType.STRING, required = false,
            description = "name of the database user to create; defaults to the database name when omitted")
    private String dbUsername;

    // Optional: an empty value means "generate one". A supplied password is
    // restricted to a conservative character set (see DbaasManagerImpl):
    // it is interpolated into SQL on the instance, and widening the set here
    // without fixing that quoting first would be an injection waiting to
    // happen.
    @Parameter(name = "dbpassword", type = CommandType.STRING, required = false,
            description = "password for the database user; generated when omitted")
    private String dbPassword;

    public String getDbPassword() {
        return dbPassword;
    }

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
        _dbaasManager.checkCallerOwnsVm(getVirtualMachineId());
        DbaasResponse response;
        try {
            response = _dbaasManager.createDatabase(this);
        } catch (CloudRuntimeException error) {
            if (causedByInsufficientCapacity(error)) {
                throw new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR,
                        "Not enough compute or network capacity to start this database. Free resources and retry.");
            }
            throw error;
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    static boolean causedByInsufficientCapacity(Throwable error) {
        // Wrapped VM-start errors otherwise become an unhelpful generic 500.
        for (int depth = 0; error != null && depth < 32; depth++, error = error.getCause()) {
            if (error instanceof InsufficientCapacityException) {
                return true;
            }
        }
        return false;
    }
}
