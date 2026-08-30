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

package org.apache.cloudstack.reverseproxy.dao;

import java.util.List;

import org.apache.cloudstack.reverseproxy.ReverseProxyDomainMapVO;
import org.springframework.stereotype.Component;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component
public class ReverseProxyDomainMapDaoImpl extends GenericDaoBase<ReverseProxyDomainMapVO, Long> implements ReverseProxyDomainMapDao {

    private final SearchBuilder<ReverseProxyDomainMapVO> DomainSearch;
    private final SearchBuilder<ReverseProxyDomainMapVO> DomainAccountSearch;
    private final SearchBuilder<ReverseProxyDomainMapVO> DomainNetworkSearch;
    private final SearchBuilder<ReverseProxyDomainMapVO> AccountSearch;
    private final SearchBuilder<ReverseProxyDomainMapVO> NetworkSearch;

    public ReverseProxyDomainMapDaoImpl() {
        DomainSearch = createSearchBuilder();
        DomainSearch.and("domainId", DomainSearch.entity().getDomainId(), SearchCriteria.Op.EQ);
        DomainSearch.done();

        DomainAccountSearch = createSearchBuilder();
        DomainAccountSearch.and("domainId", DomainAccountSearch.entity().getDomainId(), SearchCriteria.Op.EQ);
        DomainAccountSearch.and("accountId", DomainAccountSearch.entity().getAccountId(), SearchCriteria.Op.EQ);
        DomainAccountSearch.done();

        DomainNetworkSearch = createSearchBuilder();
        DomainNetworkSearch.and("domainId", DomainNetworkSearch.entity().getDomainId(), SearchCriteria.Op.EQ);
        DomainNetworkSearch.and("networkId", DomainNetworkSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
        DomainNetworkSearch.done();

        AccountSearch = createSearchBuilder();
        AccountSearch.and("accountId", AccountSearch.entity().getAccountId(), SearchCriteria.Op.EQ);
        AccountSearch.done();

        NetworkSearch = createSearchBuilder();
        NetworkSearch.and("networkId", NetworkSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
        NetworkSearch.done();
    }

    @Override
    public List<ReverseProxyDomainMapVO> listByDomainId(final long domainId) {
        final SearchCriteria<ReverseProxyDomainMapVO> sc = DomainSearch.create();
        sc.setParameters("domainId", domainId);
        return listBy(sc);
    }

    @Override
    public ReverseProxyDomainMapVO findByDomainAndAccount(final long domainId, final long accountId) {
        final SearchCriteria<ReverseProxyDomainMapVO> sc = DomainAccountSearch.create();
        sc.setParameters("domainId", domainId);
        sc.setParameters("accountId", accountId);
        return findOneBy(sc);
    }

    @Override
    public ReverseProxyDomainMapVO findByDomainAndNetwork(final long domainId, final long networkId) {
        final SearchCriteria<ReverseProxyDomainMapVO> sc = DomainNetworkSearch.create();
        sc.setParameters("domainId", domainId);
        sc.setParameters("networkId", networkId);
        return findOneBy(sc);
    }

    @Override
    public List<ReverseProxyDomainMapVO> listByAccountId(final long accountId) {
        final SearchCriteria<ReverseProxyDomainMapVO> sc = AccountSearch.create();
        sc.setParameters("accountId", accountId);
        return listBy(sc);
    }

    @Override
    public List<ReverseProxyDomainMapVO> listByNetworkId(final long networkId) {
        final SearchCriteria<ReverseProxyDomainMapVO> sc = NetworkSearch.create();
        sc.setParameters("networkId", networkId);
        return listBy(sc);
    }
}
