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

    // Only the first creation (from the wizard, on a freshly deployed
    // instance) may set the tenant login password: every later
    // createDatabase on the same VM runs without this flag, so a database
    // added to an existing instance never rotates the OS password the tenant
    // may already be using -- the old value would otherwise be overwritten by
    // a generated one with no way to learn it.
    @Parameter(name = "resetvmpassword", type = CommandType.BOOLEAN, required = false,
            description = "set the instance login user's password as part of this call; "
                    + "only the initial wizard deployment should send true")
    private Boolean resetVmPassword;

    public Boolean isResetVmPassword() {
        return resetVmPassword;
    }

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
        DbaasResponse response = _dbaasManager.createDatabase(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
