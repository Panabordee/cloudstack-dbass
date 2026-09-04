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
package org.apache.cloudstack.ldap;

/**
 * Striped locks to serialize LDAP-driven user/account creation per (domain, username),
 * preventing duplicate accounts when logins or imports race for the same user.
 */
public final class LdapUserCreationLock {

    private static final Object[] LOCKS = new Object[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) {
            LOCKS[i] = new Object();
        }
    }

    private LdapUserCreationLock() {
    }

    public static Object getLock(final String username, final Long domainId) {
        final String key = domainId + ":" + username;
        return LOCKS[Math.abs(key.hashCode()) % LOCKS.length];
    }
}
