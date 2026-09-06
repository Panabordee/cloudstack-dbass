package com.dbaas;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.UserVmResponse;
import com.google.gson.JsonObject;

@APICommand(name = "dropDbaasColumn",
        description = "Queues a DBaaS console job dropping one column from a table",
        responseObject = DbaasJobResponse.class,
        responseHasSensitiveInfo = false)
public class DropDbaasColumnCmd extends DbaasConsoleJobCmdBase {

    private static final String s_name = "dropdbaascolumnresponse";

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the DBaaS instance the job targets")
    private Long virtualMachineId;

    @Parameter(name = "table", type = CommandType.STRING, required = true,
            description = "the table to alter")
    private String table;

    @Parameter(name = "column", type = CommandType.STRING, required = true,
            description = "the column to drop")
    private String column;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    @Override
    protected String jobType() {
        return JOB_COLUMN_DROP;
    }

    @Override
    protected String jobDbRole() {
        return DbaasManagerImpl.ROLE_OWNER;
    }

    @Override
    protected String jobPayload() {
        String engineType = requireEngineType();
        DbaasManagerImpl.validateIdentifier(table, "table");
        DbaasManagerImpl.validateIdentifier(column, "column");
        JsonObject payload = new JsonObject();
        payload.addProperty("statement", "ALTER TABLE " + quoteIdentifier(engineType, table)
                + " DROP COLUMN " + quoteIdentifier(engineType, column));
        return payload.toString();
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
