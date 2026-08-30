package com.dbaas;

public interface DbaasManager {
    DbaasResponse createDatabase(CreateDatabaseCmd cmd);
}
