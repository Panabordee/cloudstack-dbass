package com.dbaas;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.UserVmResponse;
import com.google.gson.JsonObject;

@APICommand(name = "previewDbaasTable",
        description = "Queues a DBaaS console job previewing rows of one table, capped and paged",
        responseObject = DbaasJobResponse.class,
        responseHasSensitiveInfo = false)
public class PreviewDbaasTableCmd extends DbaasConsoleJobCmdBase {

    private static final String s_name = "previewdbaastableresponse";

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "the ID of the DBaaS instance the job targets")
    private Long virtualMachineId;

    @Parameter(name = "table", type = CommandType.STRING, required = true,
            description = "the table to preview")
    private String table;

    @Parameter(name = "limit", type = CommandType.INTEGER,
            description = "maximum rows to return; clamped to dbaas.console.row.limit")
    private Integer limit;

    @Parameter(name = "offset", type = CommandType.INTEGER,
            description = "rows to skip before the preview starts")
    private Integer offset;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    @Override
    protected String jobType() {
        return JOB_TABLE_PREVIEW;
    }

    @Override
    protected String jobDbRole() {
        return DbaasManagerImpl.ROLE_READONLY;
    }

    @Override
    protected String jobPayload() {
        DbaasManagerImpl.validateIdentifier(table, "table");
        int cappedLimit = limit == null || limit < 1 ? 100 : Math.min(limit, DbaasManagerImpl.DbaasConsoleRowLimit.value());
        int safeOffset = offset == null || offset < 0 ? 0 : offset;
        JsonObject payload = new JsonObject();
        payload.addProperty("table", table);
        payload.addProperty("limit", cappedLimit);
        payload.addProperty("offset", safeOffset);
        return payload.toString();
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
