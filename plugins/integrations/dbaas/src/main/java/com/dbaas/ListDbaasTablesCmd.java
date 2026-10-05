package com.dbaas;

import org.apache.cloudstack.api.APICommand;

@APICommand(name = "listDbaasTables",
        description = "Queues a DBaaS console job listing the tables of the instance's database",
        responseObject = DbaasJobResponse.class,
        responseHasSensitiveInfo = false)
public class ListDbaasTablesCmd extends DbaasConsoleJobCmdBase {

    private static final String s_name = "listdbaastablesresponse";

    @Override
    protected String jobType() {
        return JOB_TABLE_LIST;
    }

    @Override
    protected String jobDbRole() {
        return DbaasManagerImpl.ROLE_READONLY;
    }

    @Override
    protected String jobPayload() {
        return "{}";
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
