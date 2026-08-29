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
package com.cloud.api.auth;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.auth.APIAuthenticationType;
import org.apache.cloudstack.api.auth.APIAuthenticator;
import org.apache.cloudstack.api.auth.PluggableAPIAuthenticator;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.LoginDomainResponse;

import com.cloud.api.response.ApiResponseSerializer;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.user.Account;
import com.cloud.utils.db.SearchCriteria;

@APICommand(name = ListLoginDomainsCmd.APINAME,
        description = "Lists the domains available on the login page (active domains flagged to show on login), ordered by their sort key. This API can be called without authentication.",
        responseObject = LoginDomainResponse.class,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, since = "4.22.2.0")
public class ListLoginDomainsCmd extends BaseListCmd implements APIAuthenticator {

    public static final String APINAME = "listLoginDomains";

    @Inject
    private DomainDao domainDao;

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + BaseCmd.RESPONSE_SUFFIX;
    }

    @Override
    public void execute() throws ServerApiException {
        // We should never reach here, the command is handled by the authenticator
        throw new ServerApiException(ApiErrorCode.METHOD_NOT_ALLOWED, "This is an authentication api, cannot be used directly");
    }

    @Override
    public String authenticate(String command, Map<String, Object[]> params, HttpSession session, InetAddress remoteAddress, String responseType,
        StringBuilder auditTrailSb, HttpServletRequest req, HttpServletResponse resp) throws ServerApiException {
        SearchCriteria<DomainVO> sc = domainDao.createSearchCriteria();
        sc.addAnd("state", SearchCriteria.Op.EQ, Domain.State.Active);
        sc.addAnd("showOnLogin", SearchCriteria.Op.EQ, true);
        List<DomainVO> domains = domainDao.search(sc, null);
        domains.sort(Comparator.comparingLong(DomainVO::getSortKey)
                .thenComparing(DomainVO::getPath)
                .thenComparing(DomainVO::getName));

        List<LoginDomainResponse> responses = new ArrayList<>();
        for (DomainVO domain : domains) {
            LoginDomainResponse response = new LoginDomainResponse();
            response.setId(domain.getUuid());
            response.setName(domain.getName());
            response.setDisplayName(domain.getDisplayName() != null ? domain.getDisplayName() : domain.getName());
            response.setPath(domain.getPath());
            response.setLevel(domain.getLevel());
            response.setObjectName("logindomain");
            responses.add(response);
        }

        ListResponse<LoginDomainResponse> listResponse = new ListResponse<>();
        listResponse.setResponses(responses, responses.size());
        listResponse.setResponseName(getCommandName());
        return ApiResponseSerializer.toSerializedString(listResponse, responseType);
    }

    @Override
    public APIAuthenticationType getAPIType() {
        return APIAuthenticationType.READONLY_API;
    }

    @Override
    public void setAuthenticators(List<PluggableAPIAuthenticator> authenticators) {
    }
}
