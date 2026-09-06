package com.dbaas;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.UserVmResponse;
import com.google.gson.JsonObject;

@APICommand(name = "addDbaasColumn",
        description = "Queues a DBaaS console job adding one column to a table",
        responseObject = DbaasJobResponse.class,
        responseHasSensitiveInfo = false)
public class AddDbaasColumnCmd extends DbaasConsoleJobCmdBase {

    private static final String s_name = "adddbaascolumnresponse";

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
            description = "the column to add")
    private String column;

    @Parameter(name = "type", type = CommandType.STRING, required = true,
            description = "the column type, from the engine allowlist")
    private String type;

    @Parameter(name = "nullable", type = CommandType.BOOLEAN,
            description = "whether the column may be NULL (default true)")
    private Boolean nullable;

    @Parameter(name = "defaultvalue", type = CommandType.STRING,
            description = "an optional DEFAULT literal")
    private String defaultValue;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    @Override
    protected String jobType() {
        return JOB_COLUMN_ADD;
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
        validateColumnType(engineType, type, "column");
        StringBuilder statement = new StringBuilder("ALTER TABLE ")
                .append(quoteIdentifier(engineType, table))
                .append(" ADD COLUMN ")
                .append(quoteIdentifier(engineType, column))
                .append(" ").append(type);
        if (nullable == null || !nullable) {
            statement.append(" NOT NULL");
        }
        String literal = sqlDefaultLiteral(defaultValue);
        if (literal != null) {
            statement.append(" DEFAULT ").append(literal);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("statement", statement.toString());
        return payload.toString();
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
