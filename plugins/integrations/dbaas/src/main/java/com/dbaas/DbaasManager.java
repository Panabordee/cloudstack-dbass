package com.dbaas;

import java.util.List;

public interface DbaasManager {
    DbaasResponse createDatabase(CreateDatabaseCmd cmd);

    DbaasResponse resetDatabasePassword(ResetDatabasePasswordCmd cmd);

    DbaasResponse getDatabasePassword(GetDatabasePasswordCmd cmd);

    List<DbaasEngineResponse> listEngines();

    int deleteCredentialsForVm(Long vmId);
}
