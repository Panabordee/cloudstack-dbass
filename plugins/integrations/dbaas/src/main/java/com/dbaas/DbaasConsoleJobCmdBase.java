package com.dbaas;

import com.cloud.vm.VirtualMachine;
import java.util.List;

import javax.inject.Inject;

import com.cloud.utils.db.EntityManager;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.UserVmResponse;


/**
 * Common ground for the console job commands: resolves and ACLs the target
 * instance, gates on the console master switch, submits the validated payload
 * as a job and answers with the job uuid. The subclasses own their parameters,
 * their validation and the role the job runs as.
 */
public abstract class DbaasConsoleJobCmdBase extends BaseCmd {

    // Job type constants -- the same strings the agent dispatches on.
    public static final String JOB_SQL = "sql";
    public static final String JOB_TABLE_LIST = "table_list";
    public static final String JOB_TABLE_DESCRIBE = "table_describe";
    public static final String JOB_TABLE_PREVIEW = "table_preview";
    public static final String JOB_TABLE_CREATE = "table_create";
    public static final String JOB_TABLE_DROP = "table_drop";
    public static final String JOB_COLUMN_ADD = "column_add";
    public static final String JOB_COLUMN_DROP = "column_drop";
    public static final String JOB_INDEX_CREATE = "index_create";
    public static final String JOB_INDEX_DROP = "index_drop";
    public static final String JOB_PASSWORD_RESET = "password_reset";

    @Inject
    protected transient DbaasManager _dbaasManager;

    @Inject
    protected transient EntityManager _entityMgr;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the DBaaS instance the job targets")
    private Long virtualMachineId;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    // ACL by the target instance's account, identical to the existing DBaaS
    // commands: a non-admin caller can only run jobs against an instance
    // their own account owns.
    @Override
    public long getEntityOwnerId() {
        VirtualMachine vm = _entityMgr.findById(VirtualMachine.class, getVirtualMachineId());
        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find a VM with id " + getVirtualMachineId());
        }
        return vm.getAccountId();
    }

    /** The job type constant, e.g. JOB_TABLE_LIST. */
    protected abstract String jobType();

    /** Which database role the agent must execute this job with. */
    protected abstract String jobDbRole();

    /** The payload, already validated and built server-side. Serialized into
     *  the job row and encrypted at rest. */
    protected abstract String jobPayload();

    /** Column-type validation against the per-engine allowlist from
     *  config.json: exact match, or prefix match for parameterised entries
     *  like VARCHAR(n). */
    protected void validateColumnType(String engineType, String type, String field) {
        List<String> allowlist = _dbaasManager.consoleTypeAllowlist(engineType);
        for (String allowed : allowlist) {
            if (allowed.equalsIgnoreCase(type)) {
                return;
            }
            if (allowed.endsWith("(n)")) {
                String prefix = allowed.substring(0, allowed.length() - 3);
                if (type.matches("(?i)" + java.util.regex.Pattern.quote(prefix) + "\\([0-9]+\\)")) {
                    return;
                }
            }
        }
        throw new InvalidParameterValueException(field + " type '" + type + "' is not in the allowlist for the"
                + " " + engineType + " engine: allowed are " + allowlist);
    }

    /** A DEFAULT literal for generated DDL: restricted to a charset that is
     *  safe inside single quotes (single quotes are doubled, nothing else
     *  passes). Numbers stay unquoted so they remain numbers. */
    protected String sqlDefaultLiteral(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (raw.matches("[0-9]+")) {
            return raw;
        }
        if (!raw.matches("[A-Za-z0-9_ .@+-]+")) {
            throw new InvalidParameterValueException("the column default may only contain letters, digits,"
                    + " spaces and _ . @ + -");
        }
        return "'" + raw.replace("'", "''") + "'";
    }

    /** Identifier quoting per engine: backticks for MySQL/MariaDB, double
     *  quotes for PostgreSQL. Mongo does not use generated DDL. */
    protected String quoteIdentifier(String engineType, String name) {
        if ("postgresql".equals(engineType)) {
            return "\"" + name + "\"";
        }
        return "`" + name + "`";
    }

    /** Resolves the engine type for the target instance or fails the command:
     *  no engine entry means the console cannot build statements for it. */
    protected String requireEngineType() {
        String engineType = _dbaasManager.consoleEngineTypeForVm(getVirtualMachineId());
        if (engineType == null) {
            throw new InvalidParameterValueException("the instance's template has no engine entry in the"
                    + " DBaaS config, so no console statement can be built for it");
        }
        return engineType;
    }

    @Override
    public void execute() throws ServerApiException {
        if (!_dbaasManager.isConsoleEnabled()) {
            throw new InvalidParameterValueException("the DBaaS console is disabled"
                    + " (dbaas.console.enabled=false)");
        }
        String jobUuid = _dbaasManager.createConsoleJob(getVirtualMachineId(), getEntityOwnerId(),
                jobType(), jobPayload(), jobDbRole());
        DbaasJobResponse response = new DbaasJobResponse();
        response.setJobId(jobUuid);
        response.setState(DbaasManagerImpl.STATUS_PENDING);
        response.setObjectName("dbaasjob");
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
