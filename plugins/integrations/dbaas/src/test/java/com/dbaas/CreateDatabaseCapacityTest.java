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

import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CreateDatabaseCapacityTest {
    @Test public void recognizesWrappedVmStartCapacityFailure() {
        Throwable capacity = new InsufficientServerCapacityException("no capacity", VirtualMachine.class, 1L);
        assertTrue(CreateDatabaseCmd.causedByInsufficientCapacity(new CloudRuntimeException("start failed", capacity)));
    }

    @Test public void leavesOtherFailuresUnchanged() {
        assertFalse(CreateDatabaseCmd.causedByInsufficientCapacity(new CloudRuntimeException("configuration error")));
    }

    @Test public void terminatesOnCyclicExceptionCauses() {
        Throwable first = new RuntimeException();
        Throwable second = new RuntimeException(first);
        first.initCause(second);
        assertFalse(CreateDatabaseCmd.causedByInsufficientCapacity(first));
    }
}
