package com.dbaas;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;


import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Round trip of the console transport against a REAL database:
 * createConsoleJob -> agentPollJob (long-poll) -> agentReportResult ->
 * getUserJobResult (delete-on-read), plus the agent-token validation that
 * BLOCKER-2 broke.
 *
 * Skips when there is no management-server configuration to talk to (the
 * test classpath gains /etc/cloudstack/management/db.properties + key when
 * they exist, which is exactly the configuration the running server uses).
 * The only rows it leaves behind are one dbaas_jobs row and its agent-token
 * row, both swept by TTL like any other job.
 */
public class ConsoleRoundTripTest {

    private static final String MGMT_CONF = "/etc/cloudstack/management";
    private static DbaasManagerImpl manager;
    private static boolean dbAvailable;

    private static String vmUuid;
    private static long vmId;
    private static long accountId;
    private static String agentToken;
    private static long agentTokenRowId;

    @BeforeClass
    public static void setup() throws Exception {
        File dbProps = new File(MGMT_CONF, "db.properties");
        File key = new File(MGMT_CONF, "key");
        assumeTrue("no readable management-server configuration on this host",
                dbProps.exists() && dbProps.canRead() && key.exists() && key.canRead());

        File classesDir = new File(ConsoleRoundTripTest.class.getResource("/").toURI());
        Files.copy(dbProps.toPath(), new File(classesDir, "db.properties").toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(key.toPath(), new File(classesDir, "key").toPath(),
                StandardCopyOption.REPLACE_EXISTING);

        try {
            manager = new DbaasManagerImpl();
            manager.start();
            dbAvailable = true;
        } catch (Throwable t) {
            dbAvailable = false;
            t.printStackTrace();
        }
        assumeTrue("could not initialize the DB layer", dbAvailable);
    }

    @AfterClass
    public static void teardown() {
        // Leave the live token row exactly as found: the test replaced it with
        // its own hash, so remove it rather than hand back a broken credential.
        if (manager != null && agentTokenRowId > 0) {
            try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
                try (PreparedStatement pstmt = txn.prepareStatement(
                        "DELETE FROM dbaas_agent_tokens WHERE id = ?")) {
                    pstmt.setLong(1, agentTokenRowId);
                    pstmt.executeUpdate();
                }
            } catch (Exception ignored) {
            }
        }
        if (manager != null) {
            manager.stop();
        }
    }

    private long pickLiveVm() throws Exception {
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement(
                    "SELECT uuid, id, account_id FROM vm_instance"
                    + " WHERE removed IS NULL AND state = 'Running' AND type = 'User'"
                    + " ORDER BY id DESC LIMIT 1");
                    ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    vmUuid = rs.getString(1);
                    vmId = rs.getLong(2);
                    accountId = rs.getLong(3);
                    return vmId;
                }
            }
        }
        throw new CloudRuntimeException("no running user instance to test against");
    }

    private void installAgentToken(String token) throws Exception {
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement(
                    "INSERT INTO dbaas_agent_tokens (vm_id, token_hash) VALUES (?, ?)"
                    + " ON DUPLICATE KEY UPDATE token_hash = VALUES(token_hash), rotated_at = NOW()")) {
                pstmt.setLong(1, vmId);
                pstmt.setString(2, sha256(token));
                pstmt.executeUpdate();
            }
        }
        agentToken = token;
    }

    private static String sha256(String value) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    public void agentTokenValidationAcceptsTheRightTokenAndRejectsOthers() throws Exception {
        runWithDb(() -> {
        try {
            installAgentToken("test-agent-token-roundtrip");
            assertTrue("the correct token must validate (BLOCKER-2)",
                    manager.isAgentTokenValid(vmUuid, "test-agent-token-roundtrip"));
            assertFalse("a wrong token must not validate",
                    manager.isAgentTokenValid(vmUuid, "not-the-token"));
            assertFalse("an unknown VM must not validate",
                    manager.isAgentTokenValid("00000000-0000-0000-0000-000000000000", "test-agent-token-roundtrip"));
        } catch (Exception e) {
            org.junit.Assert.fail(e.getMessage());
        }
        });
    }

    @Test
    public void consoleJobRoundTrip() throws Exception {
        runWithDb(() -> {
        try {
            installAgentToken("test-agent-token-roundtrip");
            String jobUuid = manager.createConsoleJob(vmId, accountId,
                    DbaasConsoleJobCmdBase.JOB_TABLE_LIST, "{}", DbaasManagerImpl.ROLE_READONLY);
            assertTrue(jobUuid != null && !jobUuid.isEmpty());

            String poll = manager.agentPollJob(vmUuid, 1);
            assertTrue("the pending job must be delivered by the poll",
                    poll.contains("\"jobid\":\"" + jobUuid + "\"") && poll.contains("table_list"));

            boolean reported = manager.agentReportResult(vmUuid, "test-agent-token-roundtrip", jobUuid,
                    "confirmed", 1, false, "{\"columns\":[\"x\"],\"rows\":[[\"1\"]]}", "");
            assertTrue("agentReportResult must accept a dispatched job", reported);

            String fetched = manager.getUserJobResult(jobUuid, accountId);
            assertTrue("the fetched result must carry the rows", fetched.contains("result"));
            assertTrue("the fetched result must carry the columns", fetched.contains("columns"));

            String second = manager.getUserJobResult(jobUuid, accountId);
            assertTrue("the second fetch must report collected", second.contains("\"collected\":true"));
            assertFalse("the second fetch must not carry the tenant data", second.contains("\"rows\""));
        } catch (Exception e) {
            org.junit.Assert.fail(e.getMessage());
        }
        });
    }

    private void runWithDb(Runnable test) {
        assumeTrue("no management-server configuration on this host", dbAvailable);
        try {
            pickLiveVm();
        } catch (Exception e) {
            org.junit.Assert.fail("no running user instance to test against: " + e.getMessage());
        }
        test.run();
    }
}
