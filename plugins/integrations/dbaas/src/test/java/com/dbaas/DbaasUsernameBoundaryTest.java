// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.dbaas;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class DbaasUsernameBoundaryTest {
    @Test public void keepsExistingShortReadOnlyNames() {
        assertEquals("owneruser_ro", DbaasManagerImpl.readOnlyUsername("owneruser"));
        String owner = "a".repeat(29);
        assertEquals(owner + "_ro", DbaasManagerImpl.readOnlyUsername(owner));
    }

    @Test public void acceptsMaximumLengthOwnersWithoutOverflowingReadOnlyName() {
        for (int length : new int[] {30, 31, 32}) {
            String owner = "a".repeat(length);
            String readonly = DbaasManagerImpl.readOnlyUsername(owner);
            assertEquals(32, readonly.length());
            DbaasManagerImpl.validateIdentifier(readonly, "dbusername");
            assertEquals(readonly, DbaasManagerImpl.readOnlyUsername(owner));
            assertTrue(readonly.endsWith("_ro"));
        }
    }

    @Test public void distinguishesOwnersSharingTheTruncatedPrefix() {
        String first = "a".repeat(31) + "b";
        String second = "a".repeat(31) + "c";
        assertNotEquals(DbaasManagerImpl.readOnlyUsername(first),
                DbaasManagerImpl.readOnlyUsername(second));
    }
}
