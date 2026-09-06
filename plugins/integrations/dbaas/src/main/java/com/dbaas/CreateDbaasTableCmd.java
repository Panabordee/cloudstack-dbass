package com.dbaas;


import java.util.ArrayList;
import java.util.List;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.APICommand;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.UserVmResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@APICommand(name = "createDbaasTable",
        description = "Queues a DBaaS console job creating a table from a validated column list",
        responseObject = DbaasJobResponse.class,
        responseHasSensitiveInfo = false)
public class CreateDbaasTableCmd extends DbaasConsoleJobCmdBase {

    private static final String s_name = "createdbaastableresponse";

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the DBaaS instance the job targets")
    private Long virtualMachineId;

    @Parameter(name = "table", type = CommandType.STRING, required = true,
            description = "the table to create")
    private String table;

    // JSON: [{"name":"id","type":"BIGINT","nullable":false,"primary":true,"default":"1"}]
    // Every identifier and every type is validated server-side; the statement
    // is built here, so no client SQL ever reaches the database.
    @Parameter(name = "columns", type = CommandType.STRING, required = true,
            description = "the column list as JSON: name, type (from the engine allowlist),"
                    + " nullable, default, primary")
    private String columns;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    @Override
    protected String jobType() {
        return JOB_TABLE_CREATE;
    }

    @Override
    protected String jobDbRole() {
        return DbaasManagerImpl.ROLE_OWNER;
    }

    @Override
    protected String jobPayload() {
        String engineType = requireEngineType();
        DbaasManagerImpl.validateIdentifier(table, "table");
        JsonArray columnArray;
        try {
            columnArray = JsonParser.parseString(columns).getAsJsonArray();
        } catch (Exception e) {
            throw new InvalidParameterValueException("columns is not valid JSON: " + e.getMessage());
        }
        if (columnArray.size() == 0) {
            throw new InvalidParameterValueException("columns must contain at least one column");
        }
        List<String> columnDefs = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();
        for (JsonElement element : columnArray) {
            JsonObject column = element.getAsJsonObject();
            String columnName = column.has("name") ? column.get("name").getAsString() : null;
            DbaasManagerImpl.validateIdentifier(columnName, "column name");
            String columnType = column.has("type") ? column.get("type").getAsString() : null;
            validateColumnType(engineType, columnType, "column '" + columnName + "'");

            boolean isPrimary = column.has("primary") && column.get("primary").getAsBoolean();
            boolean isNullable = !column.has("nullable") || column.get("nullable").isJsonNull()
                    || column.get("nullable").getAsBoolean();
            StringBuilder columnDef = new StringBuilder(quoteIdentifier(engineType, columnName));
            columnDef.append(" ").append(columnType);
            if (!isNullable) {
                columnDef.append(" NOT NULL");
            }
            if (column.has("default") && !column.get("default").isJsonNull()) {
                String literal = sqlDefaultLiteral(column.get("default").getAsString());
                if (literal != null) {
                    columnDef.append(" DEFAULT ").append(literal);
                }
            }
            if (isPrimary) {
                primaryKeys.add(quoteIdentifier(engineType, columnName));
            }
            columnDefs.add(columnDef.toString());
        }
        if (!primaryKeys.isEmpty()) {
            columnDefs.add("PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("statement", "CREATE TABLE " + quoteIdentifier(engineType, table)
                + " (" + String.join(", ", columnDefs) + ")");
        return payload.toString();
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
