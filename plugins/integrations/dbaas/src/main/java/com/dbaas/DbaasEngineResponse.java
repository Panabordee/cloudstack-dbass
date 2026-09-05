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
package com.dbaas;

import com.google.gson.annotations.SerializedName;

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;

public class DbaasEngineResponse extends BaseResponse {

    @SerializedName("template")
    @Param(description = "the template name this engine provisions on")
    private String template;

    @SerializedName("port")
    @Param(description = "the port the database engine listens on")
    private Integer port;

    @SerializedName("provisionmode")
    @Param(description = "how the engine is configured after deployment: 'configdrive' when the"
            + " instance configures itself at first boot, 'ssh' when the management server connects"
            + " to it and runs the provisioning script", since = "4.23.0.0")
    private String provisionMode;

    public void setTemplate(String template) { this.template = template; }
    public void setPort(Integer port) { this.port = port; }
    public void setProvisionMode(String provisionMode) { this.provisionMode = provisionMode; }
}
