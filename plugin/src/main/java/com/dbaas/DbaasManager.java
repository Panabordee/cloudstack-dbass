package com.dbaas;

public interface DbaasManager {
    DbaasResponse createDatabase(CreateDatabaseCmd cmd);

    DbaasResponse resetDatabasePassword(ResetDatabasePasswordCmd cmd);

    DbaasResponse getDatabasePassword(GetDatabasePasswordCmd cmd);
}
