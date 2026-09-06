package com.dbaas;

import java.util.List;

public interface DbaasManager {
    DbaasResponse createDatabase(CreateDatabaseCmd cmd);

    DbaasResponse resetDatabasePassword(ResetDatabasePasswordCmd cmd);

    DbaasResponse getDatabasePassword(GetDatabasePasswordCmd cmd);

    List<DbaasEngineResponse> listEngines();

    int deleteCredentialsForVm(String vmUuid);

    boolean applyProvisioningReport(String vmUuid, String token, String status, String message);

    // ===== console transport (PLAN-DBAAS-CONSOLE.md C1) =====

    boolean isAgentTokenValid(String vmUuid, String token);

    String agentPollJob(String vmUuid, int longPollSeconds);

    boolean agentReportResult(String vmUuid, String token, String jobUuid, String status,
            int rowCount, boolean truncated, String result, String error);

    String createConsoleJob(Long vmId, long accountId, String type, String payload, String dbRole);

    String getUserJobResult(String jobUuid, long accountId);

    String consoleEngineTypeForVm(Long vmId);

    java.util.List<String> consoleTypeAllowlist(String engineType);

    boolean isConsoleEnabled();

    boolean isConsoleWriteEnabled();

    boolean isConsoleDropEnabled();

    int consoleRowLimit();

    // The account a console job belongs to, or -1 when the job does not
    // exist. Drives the ACL check of getDbaasJobResult.
    long getJobAccountId(String jobUuid);
}
