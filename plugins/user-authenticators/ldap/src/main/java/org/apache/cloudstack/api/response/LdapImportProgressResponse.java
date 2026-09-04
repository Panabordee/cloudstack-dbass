// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.api.response;

import com.google.gson.annotations.SerializedName;

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;

public class LdapImportProgressResponse extends BaseResponse {

    @SerializedName("status")
    @Param(description = "The import status: Idle, Fetching, Importing or Finished")
    private String status;

    @SerializedName("total")
    @Param(description = "Total number of users being imported")
    private Long total;

    @SerializedName("processed")
    @Param(description = "Number of users processed so far")
    private Long processed;

    @SerializedName("imported")
    @Param(description = "Number of users imported so far")
    private Long imported;

    @SerializedName("skipped")
    @Param(description = "Number of users skipped so far")
    private Long skipped;

    @SerializedName("failed")
    @Param(description = "Number of users failed so far")
    private Long failed;

    @SerializedName("currentuser")
    @Param(description = "Username of the user currently being imported")
    private String currentUser;

    @SerializedName("percent")
    @Param(description = "Import progress percentage")
    private Long percent;

    @SerializedName("elapsedseconds")
    @Param(description = "Elapsed time in seconds since the import started")
    private Long elapsedSeconds;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public Long getProcessed() {
        return processed;
    }

    public void setProcessed(Long processed) {
        this.processed = processed;
    }

    public Long getImported() {
        return imported;
    }

    public void setImported(Long imported) {
        this.imported = imported;
    }

    public Long getSkipped() {
        return skipped;
    }

    public void setSkipped(Long skipped) {
        this.skipped = skipped;
    }

    public Long getFailed() {
        return failed;
    }

    public void setFailed(Long failed) {
        this.failed = failed;
    }

    public String getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(String currentUser) {
        this.currentUser = currentUser;
    }

    public Long getPercent() {
        return percent;
    }

    public void setPercent(Long percent) {
        this.percent = percent;
    }

    public Long getElapsedSeconds() {
        return elapsedSeconds;
    }

    public void setElapsedSeconds(Long elapsedSeconds) {
        this.elapsedSeconds = elapsedSeconds;
    }
}
