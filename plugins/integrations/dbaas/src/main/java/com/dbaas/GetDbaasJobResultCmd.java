package com.dbaas;


import org.apache.cloudstack.api.APICommand;
import javax.inject.Inject;
import org.apache.cloudstack.api.BaseCmd;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;


// The result is delivered exactly once: this execute() reads and deletes the
// result row in the same transaction, so a second fetch answers "already
// collected" and the tenant data is gone from the management server.
@APICommand(name = "getDbaasJobResult",
        description = "Fetches the result of a DBaaS console job; delivered once, then removed",
        responseObject = DbaasJobResultResponse.class,
        responseHasSensitiveInfo = true)
public class GetDbaasJobResultCmd extends BaseCmd {

    private static final String s_name = "getdbaasjobresultresponse";

    @Inject
    private DbaasManager _dbaasManager;

    @Parameter(name = "jobid", type = CommandType.STRING, required = true,
            description = "the UUID of the console job")
    private String jobUuid;

    public String getJobUuid() {
        return jobUuid;
    }

    // ACL by the job's own account: the caller must control the account the
    // job was created under, or CloudStack refuses before execute() runs.
    @Override
    public long getEntityOwnerId() {
        long accountId = _dbaasManager.getJobAccountId(jobUuid);
        if (accountId < 0) {
            throw new InvalidParameterValueException("no such console job: " + jobUuid);
        }
        return accountId;
    }

    @Override
    public void execute() throws ServerApiException {
        String result = _dbaasManager.getUserJobResult(jobUuid, getEntityOwnerId());
        if (result == null) {
            throw new InvalidParameterValueException("no such console job: " + jobUuid);
        }
        com.google.gson.JsonObject parsed = com.google.gson.JsonParser.parseString(result).getAsJsonObject();
        DbaasJobResultResponse response = new DbaasJobResultResponse();
        response.setJobId(parsed.has("jobid") ? parsed.get("jobid").getAsString() : jobUuid);
        response.setType(parsed.has("type") ? parsed.get("type").getAsString() : null);
        response.setState(parsed.has("state") ? parsed.get("state").getAsString() : null);
        response.setRowCount(parsed.has("row_count") && !parsed.get("row_count").isJsonNull()
                ? parsed.get("row_count").getAsLong() : null);
        response.setTruncated(parsed.has("truncated") && !parsed.get("truncated").isJsonNull()
                ? parsed.get("truncated").getAsBoolean() : null);
        response.setResult(parsed.has("result") && !parsed.get("result").isJsonNull()
                ? parsed.get("result").getAsString() : null);
        response.setCollected(parsed.has("collected") && parsed.get("collected").getAsBoolean());
        response.setError(parsed.has("error") && !parsed.get("error").isJsonNull()
                ? parsed.get("error").getAsString() : null);
        response.setObjectName("dbaasjobresult");
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
