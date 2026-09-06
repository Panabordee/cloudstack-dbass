package com.dbaas;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.UserVmResponse;
import com.google.gson.JsonObject;

@APICommand(name = "dropDbaasIndex",
        description = "Queues a DBaaS console job dropping one index from a table",
        responseObject = DbaasJobResponse.class,
        responseHasSensitiveInfo = false)
public class DropDbaasIndexCmd extends DbaasConsoleJobCmdBase {

    private static final String s_name = "dropdbaasindexresponse";

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the DBaaS instance the job targets")
    private Long virtualMachineId;

    @Parameter(name = "table", type = CommandType.STRING, required = true,
            description = "the table the index belongs to")
    private String table;

    @Parameter(name = "name", type = CommandType.STRING, required = true,
            description = "the index name")
    private String name;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    @Override
    protected String jobType() {
        return JOB_INDEX_DROP;
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
        JsonObject payload = new JsonObject();
        payload.addProperty("statement", "DROP INDEX " + quoteIdentifier(engineType, name)
                + " ON " + quoteIdentifier(engineType, table));
        return payload.toString();
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
