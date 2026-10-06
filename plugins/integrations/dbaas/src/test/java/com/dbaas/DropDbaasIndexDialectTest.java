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

import java.lang.reflect.Field;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class DropDbaasIndexDialectTest {
    private static class Command extends DropDbaasIndexCmd {
        private final String engine;
        Command(String engine) { this.engine = engine; }
        @Override protected String requireEngineType() { return engine; }
    }

    private String statement(String engine, String table, String index) throws Exception {
        Command command = new Command(engine);
        for (String field : new String[] {"table", "name"}) {
            Field parameter = DropDbaasIndexCmd.class.getDeclaredField(field);
            parameter.setAccessible(true);
            parameter.set(command, field.equals("table") ? table : index);
        }
        return JsonParser.parseString(command.jobPayload()).getAsJsonObject().get("statement").getAsString();
    }

    @Test public void postgresDropsQuotedIndexWithoutTableClause() throws Exception {
        assertEquals("DROP INDEX \"owner_label_idx\"", statement("postgresql", "owner_acceptance", "owner_label_idx"));
    }

    @Test public void mysqlAndMariaKeepTheirTableClause() throws Exception {
        for (String engine : new String[] {"mysql", "mariadb"}) {
            assertEquals("DROP INDEX `owner_label_idx` ON `owner_acceptance`", statement(engine, "owner_acceptance", "owner_label_idx"));
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsIndexNameInjectionBeforeBuildingStatement() throws Exception {
        statement("postgresql", "owner_acceptance", "index;DROP_TABLE");
    }
}
