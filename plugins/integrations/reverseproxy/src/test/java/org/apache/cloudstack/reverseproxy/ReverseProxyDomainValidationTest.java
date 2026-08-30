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

package org.apache.cloudstack.reverseproxy;

import java.lang.reflect.Field;
import java.util.List;

import org.apache.cloudstack.reverseproxy.dao.ReverseProxyDomainDao;
import org.apache.cloudstack.reverseproxy.dao.ReverseProxyDomainMapDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReverseProxyDomainValidationTest {

    private ReverseProxyManagerImpl manager;
    private ReverseProxyDomainDao domainDao;
    private ReverseProxyDomainMapDao domainMapDao;

    @Before
    public void setup() throws Exception {
        manager = new ReverseProxyManagerImpl();
        domainDao = mock(ReverseProxyDomainDao.class);
        domainMapDao = mock(ReverseProxyDomainMapDao.class);
        inject("reverseProxyDomainDao", domainDao);
        inject("reverseProxyDomainMapDao", domainMapDao);
    }

    private void inject(final String fieldName, final Object value) throws Exception {
        final Field field = ReverseProxyManagerImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(manager, value);
    }

    @Test
    public void testValidDomainNamesAreNormalizedToLowercase() {
        Assert.assertEquals("cloud.company.com", manager.validateDomainName("Cloud.Company.COM"));
        Assert.assertEquals("cloud.company.com", manager.validateDomainName(" cloud.company.com "));
        Assert.assertEquals("a.b", manager.validateDomainName("a.b"));
        Assert.assertEquals("sub.cloud.company.com", manager.validateDomainName("sub.cloud.company.com"));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testSingleLabelIsRejected() {
        manager.validateDomainName("company");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testBlankDomainIsRejected() {
        manager.validateDomainName("   ");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testNullDomainIsRejected() {
        manager.validateDomainName(null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDomainWithSpacesIsRejected() {
        manager.validateDomainName("cloud company.com");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDomainWithLeadingHyphenIsRejected() {
        manager.validateDomainName("-cloud.company.com");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDomainWithUnderscoreIsRejected() {
        manager.validateDomainName("cloud_company.com");
    }

    @Test
    public void testAdminIsRecognized() {
        final Account admin = new AccountVO("admin", 1L, "", Account.Type.ADMIN, "uuid");
        Assert.assertTrue(manager.isAdmin(admin));
        Assert.assertFalse(manager.isAdmin(new AccountVO("user", 1L, "", Account.Type.NORMAL, "uuid")));
        Assert.assertFalse(manager.isAdmin(null));
    }

    @Test
    public void testWildcardDomainContainsDomain() {
        final ReverseProxyDomainVO domain = new ReverseProxyDomainVO("cloud.company.com");
        Assert.assertEquals("*.cloud.company.com", manager.getWildcardDomain(domain));
    }

    @Test
    public void testPublicDomainIsAllowedForAnyUser() {
        final ReverseProxyDomainVO domain = new ReverseProxyDomainVO("cloud.company.com");
        domain.setPublic(true);
        when(domainDao.listAll()).thenReturn(List.of(domain));
        final Account user = new AccountVO("user", 1L, "", Account.Type.NORMAL, "uuid");
        final List<ReverseProxyDomainVO> allowed = manager.listAllowedDomains(user, null);
        Assert.assertEquals(1, allowed.size());
        Assert.assertSame(domain, allowed.get(0));
    }

    @Test
    public void testNonGrantedDomainIsDeniedByDefault() {
        final ReverseProxyDomainVO domain = new ReverseProxyDomainVO("cloud.company.com");
        when(domainDao.listAll()).thenReturn(List.of(domain));
        when(domainMapDao.findByDomainAndAccount(anyLong(), anyLong())).thenReturn(null);
        final Account user = new AccountVO("user", 1L, "", Account.Type.NORMAL, "uuid");
        final List<ReverseProxyDomainVO> allowed = manager.listAllowedDomains(user, null);
        Assert.assertTrue(allowed.isEmpty());
    }

    @Test
    public void testAdminSeesAllDomains() {
        final ReverseProxyDomainVO domain = new ReverseProxyDomainVO("cloud.company.com");
        when(domainDao.listAll()).thenReturn(List.of(domain));
        final Account admin = new AccountVO("admin", 1L, "", Account.Type.ADMIN, "uuid");
        final List<ReverseProxyDomainVO> allowed = manager.listAllowedDomains(admin, null);
        Assert.assertEquals(1, allowed.size());
        Assert.assertSame(domain, allowed.get(0));
    }
}
