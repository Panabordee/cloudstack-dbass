package com.dbaas;

import java.util.List;

public interface DbaasManager {
    DbaasResponse createDatabase(CreateDatabaseCmd cmd);

    DbaasResponse resetDatabasePassword(ResetDatabasePasswordCmd cmd);

    DbaasResponse getDatabasePassword(GetDatabasePasswordCmd cmd);

    List<DbaasEngineResponse> listEngines();

    int deleteCredentialsForVm(String vmUuid);

    boolean applyProvisioningReport(String vmUuid, String token, String status, String message);
}
