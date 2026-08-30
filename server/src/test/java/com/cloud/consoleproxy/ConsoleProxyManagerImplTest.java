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
package com.cloud.consoleproxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.framework.security.keystore.KeystoreDao;
import org.apache.cloudstack.framework.security.keystore.KeystoreVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.hypervisor.Hypervisor;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.VMTemplateVO;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.vm.ConsoleProxyVO;
import com.cloud.vm.dao.ConsoleProxyDao;

@RunWith(MockitoJUnitRunner.class)
public class ConsoleProxyManagerImplTest {
    @InjectMocks
    private ConsoleProxyManagerImpl consoleProxyManager;

    @Mock
    private ConsoleProxyDao consoleProxyDao;

    @Mock
    private AccountManager accountManager;

    @Mock
    private KeystoreDao keystoreDao;

    @Mock
    private ServiceOffering serviceOffering;
    @Mock
    private VMTemplateVO template;
    @Mock
    private Account systemAccount;
    @Mock
    private User systemUser;

    private ConfigDepotImpl configDepot;
    private ConfigDepotImpl originalConfigDepot;

    @Before
    public void setUp() {
        when(accountManager.getSystemUser()).thenReturn(systemUser);
        configDepot = Mockito.mock(ConfigDepotImpl.class);
        originalConfigDepot = (ConfigDepotImpl)ReflectionTestUtils.getField(ConsoleProxyManager.ConsoleProxyWebSocketPort, "s_depot");
        ReflectionTestUtils.setField(ConsoleProxyManager.ConsoleProxyWebSocketPort, "s_depot", configDepot);
    }

    @After
    public void tearDown() {
        ReflectionTestUtils.setField(ConsoleProxyManager.ConsoleProxyWebSocketPort, "s_depot", originalConfigDepot);
    }

    private void mockConfigValue(ConfigKey<?> key, String value) {
        Mockito.when(configDepot.getConfigStringValue(Mockito.eq(key.key()), Mockito.eq(ConfigKey.Scope.Zone), Mockito.eq(1L))).thenReturn(value);
        Mockito.lenient().when(configDepot.getConfigStringValue(Mockito.eq(key.key()), Mockito.eq(ConfigKey.Scope.Global), Mockito.isNull())).thenReturn(value);
    }

    @Test
    public void testCreateConsoleProxy_New() {
        long dataCenterId = 1L;
        long id = 10L;
        String name = "console1";
        // When creating a new proxy, persist should be called.
        when(consoleProxyDao.persist(any(ConsoleProxyVO.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ConsoleProxyVO result = consoleProxyManager.createOrUpdateConsoleProxy(null, dataCenterId, id, name, serviceOffering, template, systemAccount);
        assertNotNull(result);
        assertEquals(id, result.getId());
        assertEquals(serviceOffering.getId(), result.getServiceOfferingId());
        assertEquals(name, result.getName());
        assertEquals(template.getId(), result.getTemplateId());
        assertEquals(template.getHypervisorType(), result.getHypervisorType());
        assertEquals(template.getGuestOSId(), result.getGuestOSId());
        assertEquals(dataCenterId, result.getDataCenterId());
        assertEquals(systemAccount.getDomainId(), result.getDomainId());
        assertEquals(systemAccount.getId(), result.getAccountId());
        assertEquals(serviceOffering.isOfferHA(), result.isHaEnabled());
        assertEquals(template.isDynamicallyScalable(), result.isDynamicallyScalable());
        assertEquals(serviceOffering.getLimitCpuUse(), result.limitCpuUse());
        verify(consoleProxyDao).persist(any(ConsoleProxyVO.class));
    }

    @Test
    public void testUpdateConsoleProxy() {
        long dataCenterId = 1L;
        long id = 10L;
        String name = "console1";
        ConsoleProxyVO existing = new ConsoleProxyVO(id, serviceOffering.getId(), name, 999L, Hypervisor.HypervisorType.KVM, 111L,
                dataCenterId, systemAccount.getDomainId(), systemAccount.getId(),
                systemUser.getId(), 0, serviceOffering.isOfferHA());
        existing.setDynamicallyScalable(false);
        ConsoleProxyVO result = consoleProxyManager.createOrUpdateConsoleProxy(existing, dataCenterId, id, name, serviceOffering, template, systemAccount);
        verify(consoleProxyDao).update(existing.getId(), existing);
        assertEquals(template.getId(), result.getTemplateId());
        assertEquals(template.getHypervisorType(), result.getHypervisorType());
        assertEquals(template.getGuestOSId(), result.getGuestOSId());
        assertEquals(template.isDynamicallyScalable(), result.isDynamicallyScalable());
    }

    @Test
    public void testGetVncPort_CustomPort() {
        mockConfigValue(ConsoleProxyManager.ConsoleProxyWebSocketPort, "9000");
        assertEquals(9000, consoleProxyManager.getVncPort(1L));
    }

    @Test
    public void testGetVncPort_CustomPortWithSsl() {
        mockConfigValue(ConsoleProxyManager.ConsoleProxyWebSocketPort, "9000");
        assertEquals(9000, consoleProxyManager.getVncPort(1L));
    }

    @Test
    public void testGetVncPort_InvalidCustomPortFallsBackToDefault() {
        mockConfigValue(ConsoleProxyManager.ConsoleProxyWebSocketPort, "80");
        assertEquals(8080, consoleProxyManager.getVncPort(1L));
    }

    @Test
    public void testGetVncPort_DefaultWithoutSsl() {
        mockConfigValue(ConsoleProxyManager.ConsoleProxyWebSocketPort, null);
        assertEquals(8080, consoleProxyManager.getVncPort(1L));
    }

    @Test
    public void testGetVncPort_DefaultWithSsl() {
        mockConfigValue(ConsoleProxyManager.ConsoleProxyWebSocketPort, null);
        mockConfigValue(ConsoleProxyManager.ConsoleProxySslEnabled, "true");
        mockConfigValue(ConsoleProxyManager.ConsoleProxyUrlDomain, "example.com");
        Mockito.when(keystoreDao.findByName(ConsoleProxyManager.CERTIFICATE_NAME)).thenReturn(Mockito.mock(KeystoreVO.class));
        assertEquals(8443, consoleProxyManager.getVncPort(1L));
    }

    @Test
    public void testGetVncPort_SslWithoutCertificate() {
        mockConfigValue(ConsoleProxyManager.ConsoleProxyWebSocketPort, null);
        mockConfigValue(ConsoleProxyManager.ConsoleProxySslEnabled, "true");
        mockConfigValue(ConsoleProxyManager.ConsoleProxyUrlDomain, "example.com");
        Mockito.when(keystoreDao.findByName(ConsoleProxyManager.CERTIFICATE_NAME)).thenReturn(null);
        assertEquals(8080, consoleProxyManager.getVncPort(1L));
    }

    @Test
    public void testIsValidWebSocketPort() {
        assertTrue(consoleProxyManager.isValidWebSocketPort(8080));
        assertTrue(consoleProxyManager.isValidWebSocketPort(8443));
        assertTrue(consoleProxyManager.isValidWebSocketPort(9000));
        assertTrue(consoleProxyManager.isValidWebSocketPort(1024));
        assertTrue(consoleProxyManager.isValidWebSocketPort(65535));
        assertFalse(consoleProxyManager.isValidWebSocketPort(0));
        assertFalse(consoleProxyManager.isValidWebSocketPort(80));
        assertFalse(consoleProxyManager.isValidWebSocketPort(443));
        assertFalse(consoleProxyManager.isValidWebSocketPort(1023));
        assertFalse(consoleProxyManager.isValidWebSocketPort(65536));
    }
}
