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

/** One database provisioned on an instance. An instance can hold several:
 *  createDatabase may be called on it more than once. */
public class DbaasDatabaseResponse extends BaseResponse {

    @SerializedName("database")
    @Param(description = "the database name")
    private String database;

    @SerializedName("username")
    @Param(description = "the owner credential's username for this database")
    private String username;

    @SerializedName("status")
    @Param(description = "provisioning status of this database's credential: pending, confirmed or failed")
    private String status;

    @SerializedName("engine")
    @Param(description = "the engine template this database lives on")
    private String engine;

    public void setDatabase(String database) { this.database = database; }
    public void setUsername(String username) { this.username = username; }
    public void setStatus(String status) { this.status = status; }
    public void setEngine(String engine) { this.engine = engine; }
}
