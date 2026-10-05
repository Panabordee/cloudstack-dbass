package com.dbaas;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.UserVmResponse;
import com.google.gson.JsonObject;

@APICommand(name = "describeDbaasTable",
        description = "Queues a DBaaS console job describing one table (columns, keys, indexes)",
        responseObject = DbaasJobResponse.class,
        responseHasSensitiveInfo = false)
public class DescribeDbaasTableCmd extends DbaasConsoleJobCmdBase {

    private static final String s_name = "describedbaastableresponse";

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the DBaaS instance the job targets")
    private Long virtualMachineId;

    @Parameter(name = "table", type = CommandType.STRING, required = true,
            description = "the table to describe")
    private String table;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    @Override
    protected String jobType() {
        return JOB_TABLE_DESCRIBE;
    }

    @Override
    protected String jobDbRole() {
        return DbaasManagerImpl.ROLE_READONLY;
    }

    @Override
    protected String jobPayload() {
        DbaasManagerImpl.validateIdentifier(table, "table");
        JsonObject payload = new JsonObject();
        payload.addProperty("table", table);
        return payload.toString();
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
