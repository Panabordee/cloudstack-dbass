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
package org.apache.cloudstack.api.command;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.LdapImportProgressResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.ldap.LdapImportProgress;

@APICommand(name = "listLdapImportProgress", description = "Shows the progress of the LDAP user import of the calling account",
        responseObject = LdapImportProgressResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class LdapListImportProgressCmd extends BaseCmd {

    private static final String s_name = "ldapimportprogressresponse";

    public LdapListImportProgressCmd() {
        super();
    }

    @Override
    public void execute() throws ServerApiException {
        final LdapImportProgressResponse response = new LdapImportProgressResponse();
        response.setObjectName("LdapImportProgress");
        response.setResponseName(getCommandName());

        final CallContext callContext = CallContext.current();
        if (callContext != null && callContext.getCallingAccountId() > 0) {
            final LdapImportProgress progress = LdapImportProgress.get(callContext.getCallingAccountId());
            if (progress != null) {
                response.setStatus(progress.getStatus());
                response.setTotal(progress.getTotal());
                response.setProcessed(progress.getProcessed());
                response.setImported(progress.getImported());
                response.setSkipped(progress.getSkipped());
                response.setFailed(progress.getFailed());
                response.setCurrentUser(progress.getCurrentUser());
                final long total = progress.getTotal();
                if (total > 0) {
                    response.setPercent(Math.min(100L, Math.round(progress.getProcessed() * 100.0 / total)));
                } else {
                    response.setPercent(0L);
                }
                final long endTime = LdapImportProgress.STATUS_FINISHED.equals(progress.getStatus()) ? progress.getEndTime() : System.currentTimeMillis();
                response.setElapsedSeconds(Math.max(0, (endTime - progress.getStartTime()) / 1000L));
            } else {
                response.setStatus(LdapImportProgress.STATUS_IDLE);
                response.setTotal(0L);
                response.setProcessed(0L);
                response.setImported(0L);
                response.setSkipped(0L);
                response.setFailed(0L);
                response.setPercent(0L);
            }
        } else {
            response.setStatus(LdapImportProgress.STATUS_IDLE);
            response.setTotal(0L);
            response.setProcessed(0L);
            response.setImported(0L);
            response.setSkipped(0L);
            response.setFailed(0L);
            response.setPercent(0L);
        }
        setResponseObject(response);
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        final CallContext callContext = CallContext.current();
        if (callContext != null && callContext.getCallingAccountId() > 0) {
            return callContext.getCallingAccountId();
        }
        return com.cloud.user.Account.ACCOUNT_ID_SYSTEM;
    }
}
