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

import java.lang.reflect.Proxy;
import java.util.Arrays;

import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.Account;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class DbaasAccountAccessTest {
    private Account caller(long id, Account.Type type) {
        return (Account) Proxy.newProxyInstance(Account.class.getClassLoader(),
                new Class<?>[]{Account.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAccountId")) return id;
                    if (method.getName().equals("getType")) return type;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test public void ownerCanAccess() {
        DbaasAccountAccess.requireOwner(caller(10, Account.Type.NORMAL), 10);
    }

    @Test public void rootCanCleanUpMissingVm() {
        DbaasAccountAccess.requireOwner(caller(1, Account.Type.ADMIN), -1);
    }

    @Test(expected = PermissionDeniedException.class)
    public void anotherTenantCannotAccess() {
        DbaasAccountAccess.requireOwner(caller(10, Account.Type.NORMAL), 11);
    }

    @Test(expected = PermissionDeniedException.class)
    public void tenantCannotCleanUpUnknownOwner() {
        DbaasAccountAccess.requireOwner(caller(10, Account.Type.NORMAL), -1);
    }

    @Test(expected = PermissionDeniedException.class)
    public void missingCallerCannotAccess() {
        DbaasAccountAccess.requireOwner(null, 10);
    }

    @Test public void tenantCommandsAreAvailableToNormalUsers() {
        for (Class<?> command : new DbaasManagerImpl().getCommands()) {
            assertTrue(command.getSimpleName(), Arrays.asList(command.getAnnotation(APICommand.class)
                    .authorized()).contains(RoleType.User));
        }
    }

    @Test public void tokenCallbacksDoNotGainAccountRoles() {
        for (Class<?> command : new DbaasManagerImpl().getAuthCommands()) {
            assertTrue(command.getSimpleName(), command.getAnnotation(APICommand.class).authorized().length == 0);
        }
    }
}
