package com.dbaas;


import java.util.ArrayList;
import java.util.List;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.APICommand;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.UserVmResponse;
import com.google.gson.JsonObject;

@APICommand(name = "createDbaasIndex",
        description = "Queues a DBaaS console job creating an index on one table",
        responseObject = DbaasJobResponse.class,
        responseHasSensitiveInfo = false)
public class CreateDbaasIndexCmd extends DbaasConsoleJobCmdBase {

    private static final String s_name = "createdbaasindexresponse";

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the DBaaS instance the job targets")
    private Long virtualMachineId;

    @Parameter(name = "table", type = CommandType.STRING, required = true,
            description = "the table to index")
    private String table;

    @Parameter(name = "columns", type = CommandType.STRING, required = true,
            description = "comma-separated column names for the index")
    private String columns;

    @Parameter(name = "name", type = CommandType.STRING, required = true,
            description = "the index name")
    private String name;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    @Override
    protected String jobType() {
        return JOB_INDEX_CREATE;
    }

    @Override
    protected String jobDbRole() {
        return DbaasManagerImpl.ROLE_OWNER;
    }

    @Override
    protected String jobPayload() {
        String engineType = requireEngineType();
        DbaasManagerImpl.validateIdentifier(table, "table");
        DbaasManagerImpl.validateIdentifier(name, "name");
        String[] columnNames = columns.split(",");
        if (columnNames.length == 0) {
            throw new InvalidParameterValueException("columns must name at least one column");
        }
        List<String> quoted = new ArrayList<>();
        for (String columnName : columnNames) {
            String trimmed = columnName.trim();
            DbaasManagerImpl.validateIdentifier(trimmed, "index column");
            quoted.add(quoteIdentifier(engineType, trimmed));
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("statement", "CREATE INDEX " + quoteIdentifier(engineType, name)
                + " ON " + quoteIdentifier(engineType, table)
                + " (" + String.join(", ", quoted) + ")");
        return payload.toString();
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
