package com.dbaas;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.APICommand;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.UserVmResponse;
import com.google.gson.JsonObject;

@APICommand(name = "dropDbaasTable",
        description = "Queues a DBaaS console job dropping one table. Requires the table name as confirm;"
                + " disabled unless dbaas.console.drop.enabled is true.",
        responseObject = DbaasJobResponse.class,
        responseHasSensitiveInfo = false)
public class DropDbaasTableCmd extends DbaasConsoleJobCmdBase {

    private static final String s_name = "dropdbaastableresponse";

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the DBaaS instance the job targets")
    private Long virtualMachineId;

    @Parameter(name = "table", type = CommandType.STRING, required = true,
            description = "the table to drop")
    private String table;

    @Parameter(name = "confirm", type = CommandType.STRING, required = true,
            description = "must repeat the table name exactly")
    private String confirm;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    @Override
    protected String jobType() {
        return JOB_TABLE_DROP;
    }

    @Override
    protected String jobDbRole() {
        return DbaasManagerImpl.ROLE_OWNER;
    }

    @Override
    protected String jobPayload() {
        if (!_dbaasManager.isConsoleDropEnabled()) {
            throw new InvalidParameterValueException("dropping tables is disabled"
                    + " (dbaas.console.drop.enabled=false) -- there is no backup or undo yet;"
                    + " use the SQL editor with write mode if you must");
        }
        DbaasManagerImpl.validateIdentifier(table, "table");
        if (!table.equals(confirm)) {
            throw new InvalidParameterValueException("confirm must repeat the table name exactly ('"
                    + table + "')");
        }
        String engineType = requireEngineType();
        JsonObject payload = new JsonObject();
        payload.addProperty("statement", "DROP TABLE " + quoteIdentifier(engineType, table));
        return payload.toString();
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
