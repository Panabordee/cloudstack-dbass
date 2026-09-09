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
        // MASTER-PLAN item C2 (2026-09-09): the agent dumps this table to the
        // instance's own disk before running the DROP, and refuses the job if
        // the dump fails -- that is what finally allows
        // dbaas.console.drop.enabled to be turned on (PLAN-DBAAS-CONSOLE.md
        // section 8's requirement was full backup/PITR; this is the cheap
        // version that covers the actual risk, a console mis-click, without
        // months of engineering). The identifier is already validated above
        // (validateIdentifier), so it is safe to pass through unquoted for
        // the agent's own dump-command construction.
        payload.addProperty("table", table);
        return payload.toString();
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
