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

import javax.inject.Inject;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.ServerApiException;

// The engines map in the extension's config.json is the source of truth for
// which templates are DBaaS engines; this exposes it to the UI so the engine
// pickers and section filters stop guessing from the template name prefix.
@APICommand(name = "listDbaasEngines",
        description = "Lists the database engines configured for the DBaaS extension",
        responseObject = DbaasEngineResponse.class,
        responseHasSensitiveInfo = false)
public class ListDbaasEnginesCmd extends BaseListCmd {

    private static final String s_name = "listdbaasenginesresponse";

    @Inject
    private DbaasManager _dbaasManager;

    @Override
    public void execute() throws ServerApiException {
        ListResponse<DbaasEngineResponse> response = new ListResponse<>();
        response.setResponses(_dbaasManager.listEngines());
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public String getCommandName() {
        return s_name;
    }
}
