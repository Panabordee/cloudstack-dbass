package com.dbaas;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;



// jobId is inherited from BaseResponse -- redeclaring it made Gson refuse to
// serialise the response ("declares multiple JSON fields named 'jobid'").
public class DbaasJobResultResponse extends BaseResponse {

    @SerializedName("type")
    @Param(description = "the job type")
    private String type;

    @SerializedName("state")
    @Param(description = "pending, dispatched, done, failed or expired")
    private String state;

    @SerializedName("row_count")
    @Param(description = "rows returned, when the job produced a result")
    private Long rowCount;

    @SerializedName("truncated")
    @Param(description = "true when the result hit a cap")
    private Boolean truncated;

    // The decrypted result payload -- delivered exactly once: this fetch has
    // removed it from the management server.
    @SerializedName("result")
    @Param(description = "the job result as JSON, delivered once")
    private String result;

    @SerializedName("collected")
    @Param(description = "true when the result was already fetched before")
    private Boolean collected;

    @SerializedName("error")
    @Param(description = "why the job failed, when it failed")
    private String error;

    public void setJobId(String jobId) { this.jobId = jobId; }
    public void setType(String type) { this.type = type; }
    public void setState(String state) { this.state = state; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
    public void setTruncated(Boolean truncated) { this.truncated = truncated; }
    public void setResult(String result) { this.result = result; }
    public void setCollected(Boolean collected) { this.collected = collected; }
    public void setError(String error) { this.error = error; }
}
