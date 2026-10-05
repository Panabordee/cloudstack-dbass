package com.dbaas;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.APICommand;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.UserVmResponse;
import com.google.gson.JsonObject;

// The SQL text can carry literals as sensitive as the data: the request is
// flagged sensitive and the payload is encrypted at rest, and the SQL text
// never reaches management-server.log (the job log line carries the uuid,
// type, account and row count only).
@APICommand(name = "runDbaasQuery",
        description = "Queues a DBaaS console job running one SQL statement against the instance's database."
                + " Read-only by default; write mode needs dbaas.console.write.enabled.",
        responseObject = DbaasJobResponse.class,
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false)
public class RunDbaasQueryCmd extends DbaasConsoleJobCmdBase {

    private static final String s_name = "rundbaasqueryresponse";

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the DBaaS instance the job targets")
    private Long virtualMachineId;

    @Parameter(name = "sql", type = CommandType.STRING, required = true,
            description = "the SQL statement to run")
    private String sql;

    @Parameter(name = "write", type = CommandType.BOOLEAN,
            description = "run as the owner credential instead of the read-only one"
                    + " (requires dbaas.console.write.enabled)")
    private Boolean write;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    public String getSql() {
        return sql;
    }

    public boolean isWrite() {
        return write != null && write;
    }

    @Override
    protected String jobType() {
        return JOB_SQL;
    }

    @Override
    protected String jobDbRole() {
        return isWrite() ? DbaasManagerImpl.ROLE_OWNER : DbaasManagerImpl.ROLE_READONLY;
    }

    @Override
    protected String jobPayload() {
        if (isWrite() && !_dbaasManager.isConsoleWriteEnabled()) {
            throw new InvalidParameterValueException("write queries are disabled"
                    + " (dbaas.console.write.enabled=false)");
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new InvalidParameterValueException("sql must not be empty");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("sql", sql);
        payload.addProperty("write", isWrite());
        return payload.toString();
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
