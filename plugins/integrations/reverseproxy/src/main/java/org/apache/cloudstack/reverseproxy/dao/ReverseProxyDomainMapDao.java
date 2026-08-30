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

import com.cloud.utils.db.GenericDao;

public interface ReverseProxyDomainMapDao extends GenericDao<ReverseProxyDomainMapVO, Long> {

    /**
     * Lists all active grants for the given domain suffix
     */
    List<ReverseProxyDomainMapVO> listByDomainId(long domainId);

    /**
     * Finds the active grant of the given account on the given domain suffix
     */
    ReverseProxyDomainMapVO findByDomainAndAccount(long domainId, long accountId);

    /**
     * Finds the active grant of the given network on the given domain suffix
     */
    ReverseProxyDomainMapVO findByDomainAndNetwork(long domainId, long networkId);

    /**
     * Lists all active grants of the given account
     */
    List<ReverseProxyDomainMapVO> listByAccountId(long accountId);

    /**
     * Lists all active grants of the given network
     */
    List<ReverseProxyDomainMapVO> listByNetworkId(long networkId);
}
