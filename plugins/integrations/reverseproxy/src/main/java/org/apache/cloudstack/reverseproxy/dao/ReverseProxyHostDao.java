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

import org.apache.cloudstack.reverseproxy.ReverseProxyHostVO;

import com.cloud.utils.db.GenericDao;

public interface ReverseProxyHostDao extends GenericDao<ReverseProxyHostVO, Long> {

    /**
     * Finds the active (not removed) proxy host with the given fully qualified domain name
     */
    ReverseProxyHostVO findByFqdn(String fqdn);

    /**
     * Lists all active (not removed) proxy hosts for the given VM
     */
    List<ReverseProxyHostVO> listByVmId(long vmInstanceId);

    /**
     * Counts the active (not removed) proxy hosts on the given reverse proxy domain suffix
     */
    long countByDomainId(long domainId);

    /**
     * Lists all active (not removed) proxy hosts on the given reverse proxy domain suffix
     */
    List<ReverseProxyHostVO> listByDomainId(long domainId);
}
