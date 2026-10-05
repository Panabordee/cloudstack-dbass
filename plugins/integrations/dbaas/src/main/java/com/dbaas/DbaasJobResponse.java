package com.dbaas;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;



// jobId is inherited from BaseResponse -- redeclaring it here made Gson refuse
// to serialise the response ("declares multiple JSON fields named 'jobid'"),
// which broke every command that returns a job response.
public class DbaasJobResponse extends BaseResponse {

    @SerializedName("state")
    @Param(description = "the job state: pending until an agent dispatches it")
    private String state;

    public void setState(String state) { this.state = state; }
}
