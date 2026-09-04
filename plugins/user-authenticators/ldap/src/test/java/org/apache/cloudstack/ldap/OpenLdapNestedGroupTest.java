/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The
 * ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.ldap;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.naming.NamingException;
import javax.naming.ldap.LdapContext;

import org.apache.cloudstack.ldap.dao.LdapConfigurationDao;
import org.apache.cloudstack.ldap.dao.LdapTrustMapDao;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.server.core.api.CoreSession;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.cloud.utils.Pair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OpenLdapNestedGroupTest {

    private static final String BASE_DN = "dc=mydomain,dc=org";
    private static final String USERS_OU = "ou=users," + BASE_DN;
    private static final String GROUPS_OU = "ou=groups," + BASE_DN;

    static EmbeddedLdapServer embeddedLdapServer;
    static LdapConfiguration configuration;
    static OpenLdapUserManagerImpl userManager;
    static LdapContextFactory contextFactory;
    static LdapTrustMapDao trustMapDao;

    final LdapTestConfigTool ldapTestConfigTool = new LdapTestConfigTool();

    private static void addEntry(final CoreSession session, final String dn, final String[] objectClasses, final String... nameValuePairs) throws Exception {
        final Entry entry = embeddedLdapServer.getDirectoryService().newEntry(
                embeddedLdapServer.getDirectoryService().getDnFactory().create(dn));
        for (final String objectClass : objectClasses) {
            entry.add("objectClass", objectClass);
        }
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            entry.add(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        session.add(entry);
    }

    private static String userDn(final String uid) {
        return "uid=" + uid + "," + USERS_OU;
    }

    private static String groupDn(final String cn) {
        return "cn=" + cn + "," + GROUPS_OU;
    }

    @BeforeClass
    public static void start() throws Exception {
        embeddedLdapServer = new EmbeddedLdapServer();
        embeddedLdapServer.init();

        final CoreSession session = embeddedLdapServer.getDirectoryService().getAdminSession();
        addEntry(session, USERS_OU, new String[] {"top", "organizationalUnit"}, "ou", "users");
        addEntry(session, GROUPS_OU, new String[] {"top", "organizationalUnit"}, "ou", "groups");

        for (final String uid : Arrays.asList("u1", "u2", "u3", "u4", "u5")) {
            addEntry(session, userDn(uid), new String[] {"top", "person", "inetOrgPerson"},
                    "uid", uid, "cn", uid, "sn", "User" + uid, "givenName", "Given" + uid, "mail", uid + "@example.org");
        }

        addEntry(session, groupDn("nacl-66"), new String[] {"top", "groupOfNames"}, "cn", "nacl-66", "member", userDn("u1"));
        addEntry(session, groupDn("nacl-67"), new String[] {"top", "groupOfNames"}, "cn", "nacl-67", "member", userDn("u2"), "member", groupDn("nacl-66"));
        addEntry(session, groupDn("nacl-member"), new String[] {"top", "groupOfNames"}, "cn", "nacl-member", "member", userDn("u3"), "member", groupDn("nacl-66"), "member", groupDn("nacl-67"));
        addEntry(session, groupDn("gA"), new String[] {"top", "groupOfNames"}, "cn", "gA", "member", userDn("u4"), "member", groupDn("gB"));
        addEntry(session, groupDn("gB"), new String[] {"top", "groupOfNames"}, "cn", "gB", "member", groupDn("gA"), "member", userDn("u5"));
    }

    @AfterClass
    public static void stop() throws Exception {
        embeddedLdapServer.destroy();
    }

    private void overrideConfig(final String key, final Object value) throws Exception {
        ldapTestConfigTool.overrideConfigValue(configuration, key, value);
    }

    private void setNestedGroupsEnabled(final boolean enabled) throws Exception {
        overrideConfig("ldapEnableNestedGroups", enabled);
    }

    private void injectTrustMapDao(final LdapTrustMapDao dao) throws Exception {
        final Field field = OpenLdapUserManagerImpl.class.getDeclaredField("_ldapTrustMapDao");
        field.setAccessible(true);
        field.set(userManager, dao);
    }

    private Set<String> getUsernames(final List<LdapUser> users) {
        return users.stream().map(LdapUser::getUsername).collect(Collectors.toSet());
    }

    @Before
    public void setup() throws Exception {
        final LdapConfigurationDao configurationDao = mock(LdapConfigurationDao.class);
        final LdapConfigurationVO configurationVO = new LdapConfigurationVO("localhost", 10389, null);
        lenient().when(configurationDao.find("localhost", 10389, null)).thenReturn(configurationVO);
        final Pair<List<LdapConfigurationVO>, Integer> vos =
                new Pair<List<LdapConfigurationVO>, Integer>(Collections.singletonList(configurationVO), 1);
        lenient().when(configurationDao.searchConfigurations(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(vos);

        configuration = new LdapConfiguration(configurationDao);
        overrideConfig("ldapBaseDn", BASE_DN);
        overrideConfig("ldapBindPrincipal", "uid=admin,ou=system");
        overrideConfig("ldapBindPassword", "secret");
        overrideConfig("ldapUsernameAttribute", "uid");
        overrideConfig("ldapUserObject", "inetOrgPerson");
        overrideConfig("ldapGroupObject", "groupOfNames");
        overrideConfig("ldapGroupUniqueMemberAttribute", "member");
        overrideConfig("ldapMemberOfAttribute", "memberOf");
        overrideConfig("ldapTrustStore", null);
        overrideConfig("ldapTrustStorePassword", null);

        userManager = new OpenLdapUserManagerImpl(configuration);
        trustMapDao = mock(LdapTrustMapDao.class);
        injectTrustMapDao(trustMapDao);

        contextFactory = new LdapContextFactory(configuration);
    }

    @Test
    public void testNestedGroupExpansionReturnsAllIndirectMembers() throws Exception {
        setNestedGroupsEnabled(true);
        final LdapContext context = contextFactory.createBindContext(null);
        final List<LdapUser> users = userManager.getUsersInGroup("nacl-member", context, 1L);
        assertEquals(new HashSet<>(Arrays.asList("u1", "u2", "u3")), getUsernames(users));
    }

    @Test
    public void testNestedGroupDisabledReturnsDirectMembersOnly() throws Exception {
        setNestedGroupsEnabled(false);
        final LdapContext context = contextFactory.createBindContext(null);
        final List<LdapUser> users = userManager.getUsersInGroup("nacl-member", context, 1L);
        assertEquals(new HashSet<>(Collections.singletonList("u3")), getUsernames(users));
    }

    @Test
    public void testNestedGroupExpansionHandlesCycles() throws Exception {
        setNestedGroupsEnabled(true);
        final LdapContext context = contextFactory.createBindContext(null);
        final List<LdapUser> users = userManager.getUsersInGroup("gA", context, 1L);
        assertEquals(new HashSet<>(Arrays.asList("u4", "u5")), getUsernames(users));
    }

    @Test
    public void testResolveGroupDn() throws Exception {
        final LdapContext context = contextFactory.createBindContext(null);
        assertEquals(groupDn("nacl-66"), userManager.resolveGroupDn("nacl-66", context, 1L));
        assertEquals(null, userManager.resolveGroupDn("does-not-exist", context, 1L));
    }

    @Test
    public void testGroupLoginFilterUsesResolvedDn() throws Exception {
        setNestedGroupsEnabled(true);
        final LdapContext context = contextFactory.createBindContext(null);
        try {
            userManager.getUser("u1", "GROUP", "nacl-66", context, 1L);
            throw new AssertionError("expected NamingException because memberOf is not maintained by the embedded server");
        } catch (final NamingException e) {
            assertTrue("constructed filter should use the resolved group DN, message: " + e.getMessage(),
                    e.getMessage().contains("memberOf=cn=nacl-66,ou=groups,dc=mydomain,dc=org"));
        }
    }

    @Test
    public void testGroupLoginFilterFallsBackToRawNameWhenUnresolved() throws Exception {
        setNestedGroupsEnabled(true);
        final LdapContext context = contextFactory.createBindContext(null);
        try {
            userManager.getUser("u1", "GROUP", "unknown-group", context, 1L);
            throw new AssertionError("expected NamingException because memberOf is not maintained by the embedded server");
        } catch (final NamingException e) {
            assertTrue("constructed filter should fall back to the raw name, message: " + e.getMessage(),
                    e.getMessage().contains("memberOf=unknown-group"));
        }
    }

    @Test
    public void testTrustMapGroupFilterUsesResolvedDn() throws Exception {
        setNestedGroupsEnabled(false);
        final LdapTrustMapVO trustMapVO =
                new LdapTrustMapVO(1L, LdapManager.LinkType.GROUP, "nacl-66", com.cloud.user.Account.Type.NORMAL, 0);
        when(trustMapDao.searchByDomainId(anyLong())).thenReturn(Collections.singletonList(trustMapVO));
        final LdapContext context = contextFactory.createBindContext(null);
        final Set<String> dns = userManager.expandNestedGroupMembers(new HashSet<>(Arrays.asList(groupDn("nacl-66"))), context, 1L);
        assertEquals(new HashSet<>(Collections.singletonList(userDn("u1"))), dns);
        final String filter = userManager.generateSearchFilter(null, context, 1L);
        assertTrue("trust map name should be resolved to the group DN in the memberOf filter, filter: " + filter,
                filter.contains("(memberOf=cn=nacl-66,ou=groups,dc=mydomain,dc=org)"));
    }
}
