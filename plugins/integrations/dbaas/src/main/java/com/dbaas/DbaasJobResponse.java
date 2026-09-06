package com.dbaas;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;



public class DbaasJobResponse extends BaseResponse {
    @SerializedName("jobid")
    @Param(description = "the UUID of the console job")
    private String jobId;

    @SerializedName("state")
    @Param(description = "the job state: pending until an agent dispatches it")
    private String state;

    public void setJobId(String jobId) { this.jobId = jobId; }
    public void setState(String state) { this.state = state; }
}
