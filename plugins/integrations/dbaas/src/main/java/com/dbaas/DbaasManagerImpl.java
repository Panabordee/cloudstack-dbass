package com.dbaas;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import javax.inject.Inject;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.cloud.utils.concurrency.NamedThreadFactory;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.auth.PluggableAPIAuthenticator;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDetailsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicVO;
import com.cloud.uservm.UserVm;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmService;
import com.cloud.vm.VirtualMachine;
import com.cloud.user.Account;
import com.cloud.user.dao.AccountDao;
import com.cloud.vm.dao.NicDao;

public class DbaasManagerImpl extends ManagerBase implements DbaasManager, PluggableService, Configurable,
        PluggableAPIAuthenticator {

    // config.json (the engines map: template -> script/reset_script/port) is
    // the only file this plugin still reads off disk -- there is no
    // extension.py anymore to derive its directory from, so the path is
    // configured directly.
    public static final ConfigKey<String> DbaasConfigPath = new ConfigKey<>(
            "Advanced", String.class, "dbaas.config.path",
            "/usr/share/cloudstack-management/extensions/dbaas/config.json",
            "Filesystem path to the DBaaS engines config.json.", true);

    public static final ConfigKey<Integer> DbaasCredentialsCleanupInterval = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.credentials.cleanup.interval", "3600",
            // Seconds between orphaned-credential sweeps: rows whose instance
            // has been expunged (removed from vm_instance) are deleted, and
            // orphaned DATADISK volumes are counted and logged for the admin.
            "Interval in seconds between sweeps that delete stored credentials"
                    + " of expunged instances and report orphaned data disks.", true);

    @Inject
    private EntityManager _entityMgr;

    @Inject
    private NicDao _nicDao;

    // Template details are not populated by EntityManager.findById (the VO it
    // hands back carries no details) -- read them through the details dao.
    @Inject
    private VMTemplateDetailsDao _templateDetailsDao;

    // Orphaned data-disk cleanup goes through the volume service so the
    // storage file, capacity accounting, resource counts and usage events are
    // handled together -- a bare UPDATE on volumes would leave the qcow2 on
    // primary storage and take the admin's only handle to it away.
    @Inject
    private VolumeApiService volumeApiService;

    @Inject
    private VolumeDao volumeDao;

    @Inject
    private AccountDao accountDao;

    @Inject
    private UserVmManager userVmManager;

    @Inject
    private UserVmService userVmService;

    // Created when the sweep is scheduled at start() and shut down in
    // stop(); null when the interval is configured to 0 (sweeping off).
    private ScheduledExecutorService credentialsCleanupExecutor;

    // Static holder for the report command: APIAuthenticationManagerImpl
    // constructs ReportProvisioningResultCmd with newInstance() and injects
    // it from a context that cannot resolve com.dbaas.DbaasManager, so the
    // command reads the running manager from here instead. start() publishes
    // this and stop() clears it; a null holder means the plugin is down and
    // the command must answer with a real error instead of an empty 200.
    private static volatile DbaasManager s_runningManager;

    public static DbaasManager getRunningManager() {
        return s_runningManager;
    }

    // A credential is 'pending' from the moment it is generated until the
    // instance reports back through reportDbaasProvisioningResult that it
    // configured the engine with it ('confirmed') or could not ('failed').
    static final String STATUS_PENDING = "pending";
    static final String STATUS_CONFIRMED = "confirmed";
    static final String STATUS_FAILED = "failed";
    static final String ROLE_OWNER = "owner";
    static final String ROLE_READONLY = "readonly";

    // The instance has no CloudStack credential of its own, so
    // reportDbaasProvisioningResult is registered as an unauthenticated
    // PluggableAPIAuthenticator command (the same mechanism the SAML/OAuth
    // login callbacks use) and this token stands in for a signature: random,
    // single use, short-lived, and only ever compared against its stored hash
    // -- the raw value exists only in the user data and in the request that
    // redeems it.
    public static final ConfigKey<Integer> DbaasReportTokenTtl = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.report.token.ttl", "3600",
            "Seconds a config-drive instance's provisioning report token stays valid."
                    + " The token is single-use regardless of this value.", true);

    public static final ConfigKey<String> DbaasReportApiUrl = new ConfigKey<>(
            "Advanced", String.class, "dbaas.report.api.url", "",
            "Base API URL (e.g. http://10.0.0.1:8080/client/api) that config-drive instances use to call"
                    + " reportDbaasProvisioningResult. Must be reachable from every network instances deploy"
                    + " onto; leaving it empty disables the callback and provisioning stays 'pending' forever.",
            true);

    public static final ConfigKey<Integer> DbaasReportRateLimit = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.report.rate.limit", "60",
            "Maximum reportDbaasProvisioningResult calls accepted per source IP per minute. The endpoint is"
                    + " unauthenticated, so this bounds request flooding; 0 disables the limit.", true);

    // Data-disk cleanup is opt-in: deleting tenant data without a human saying
    // so was rejected once already (PLAN.md Phase A) and stays rejected as a
    // default. When true, the sweeper marks removed the DATADISK volumes that
    // carry the dbaas.instance marker, are unattached, belong to expunged
    // instances, and are older than the grace period below.
    public static final ConfigKey<Boolean> DbaasDataDiskCleanupEnabled = new ConfigKey<>(
            "Advanced", Boolean.class, "dbaas.datadisk.cleanup.enabled", "false",
            "When true, the sweeper marks removed the unattached data disks marked dbaas.instance whose"
                    + " instance is expunged and that are older than 24 hours. Default false -- deletion of"
                    + " tenant data must be switched on by an admin.", true);

    public static final ConfigKey<Boolean> DbaasConsoleEnabled = new ConfigKey<>(
            "Advanced", Boolean.class, "dbaas.console.enabled", "false",
            "Master switch for the DBaaS console (query, browse tables, schema management)."
                    + " Ships off.", true);

    public static final ConfigKey<Integer> DbaasConsoleRowLimit = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.console.row.limit", "1000",
            "Maximum rows a console job may return.", true);

    public static final ConfigKey<Integer> DbaasConsoleBytesLimit = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.console.bytes.limit", "1048576",
            "Maximum serialized result size in bytes before a console job is truncated.", true);

    public static final ConfigKey<Integer> DbaasConsoleStatementTimeout = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.console.statement.timeout", "30",
            "Statement timeout in seconds, enforced by the agent against the database engine.", true);

    public static final ConfigKey<Boolean> DbaasConsoleWriteEnabled = new ConfigKey<>(
            "Advanced", Boolean.class, "dbaas.console.write.enabled", "false",
            "Allows runDbaasQuery with write=true (the owner credential). Ships off.", true);

    public static final ConfigKey<Boolean> DbaasConsoleDropEnabled = new ConfigKey<>(
            "Advanced", Boolean.class, "dbaas.console.drop.enabled", "false",
            "Allows dropDbaasTable. Ships off and stays off until per-database backup exists"
                    + " (PLAN-DBAAS-CONSOLE.md section 8).", true);

    public static final ConfigKey<Integer> DbaasAgentLongPollMaxWaiters = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.agent.longpoll.maxwaiters", "100",
            "How many agents may hold a long-poll open at the same time. Each waiting agent parks one"
                    + " API worker thread for up to dbaas.agent.longpoll.seconds.", true);

    private static final java.util.concurrent.atomic.AtomicInteger LONG_POLL_WAITERS =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static boolean tryAcquireLongPollSlot() {
        int waiters = LONG_POLL_WAITERS.incrementAndGet();
        return waiters <= DbaasAgentLongPollMaxWaiters.value();
    }

    public static void releaseLongPollSlot() {
        LONG_POLL_WAITERS.decrementAndGet();
    }

    public static final ConfigKey<Integer> DbaasAgentLongPollSeconds = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.agent.longpoll.seconds", "25",
            "How long getDbaasAgentJob holds the request open waiting for a job.", true);

    public static final ConfigKey<Integer> DbaasAgentTokenRotateDays = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.agent.token.rotate.days", "7",
            "Days after which a successful poll returns a fresh agent token.", true);

    public static final ConfigKey<Integer> DbaasJobTtl = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.job.ttl", "120",
            "Seconds before an undispatched console job expires.", true);

    public static final ConfigKey<Integer> DbaasJobResultTtl = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.job.result.ttl", "300",
            "Seconds before an uncollected console job result is swept.", true);

    // Grace period before the opt-in sweeper may remove an orphaned data disk.
    private static final long DATA_DISK_GRACE_SECONDS = 24 * 3600;

    private static final int REPORT_TOKEN_BYTES = 32;

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-guaranteed algorithm; this cannot happen.
            throw new IllegalStateException(e);
        }
    }

    private static String generateReportToken() {
        byte[] raw = new byte[REPORT_TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    // Alphanumeric only: the generated value travels through shell and SQL on
    // the instance, and quoting bugs there are worse than the entropy lost by
    // dropping symbols. 24 characters of [A-Za-z0-9] is ~143 bits.
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int PASSWORD_LENGTH = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    // A supplied password ends up inside the engine's own SQL on the
    // instance, where the scripts interpolate it into a quoted literal. Until
    // that quoting is parameterised, the accepted set stays narrow enough that
    // no value can terminate the literal. Callers who want symbols can leave
    // the field empty and take a generated one.
    private static final java.util.regex.Pattern PASSWORD_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]{8,64}$");

    // Same identifier shape the engine scripts enforce at the point of
    // interpolation (mysql.sh and friends). Checked here as well so a bad
    // name fails before the instance is stopped and restarted for nothing,
    // not only once it is already booting.
    private static final java.util.regex.Pattern IDENTIFIER_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,31}$");

    // Template detail that marks an image as built for config-drive
    // provisioning (it carries /opt/dbaas/firstboot.sh and /opt/dbaas/engine).
    // Without this check a v1 SSH-era template could be picked, would boot,
    // would read nothing, and would leave its credential 'pending' forever
    // with no error anywhere.
    public static final String CONFIGDRIVE_DETAIL_KEY = "dbaas.configdrive";

    // Cap on the status_message column (varchar(1024)) minus headroom: a
    // report message longer than this is truncated server-side, because an
    // UPDATE that fails on data truncation would lose the report exactly when
    // it mattered most.
    static final int STATUS_MESSAGE_MAX = 1000;

    static String validateOrGeneratePassword(String supplied) {
        if (supplied == null || supplied.trim().isEmpty()) {
            return generatePassword();
        }
        if (!PASSWORD_PATTERN.matcher(supplied).matches()) {
            throw new InvalidParameterValueException("dbpassword must be 8-64 characters long and may contain"
                    + " letters, digits, underscore, dot and hyphen only; leave it empty to have one generated");
        }
        return supplied;
    }

    static void validateIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new InvalidParameterValueException(field + " must start with a letter and may contain only"
                    + " letters, digits and underscores, up to 32 characters total");
        }
    }

    static String generatePassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * The provisioning request the instance reads at first boot. cloud-init
     * writes the JSON document out and runs the engine's first-boot script
     * against it; the script lives in the image, so nothing about which engine
     * this is needs to be encoded here.
     * <p>
     * report_url/report_token/vm_id are only present when the report API is
     * configured (see {@link #DbaasReportApiUrl}) -- firstboot.sh skips the
     * callback when they are absent rather than fail on it, since an instance
     * that could not report back has still provisioned correctly.
     * <p>
     * Base64 because that is what the user data API takes.
     */
    static String buildUserData(String dbName, String dbUsername, String dbPassword,
            String dbUserRo, String dbPasswordRo, String vmUuid, String reportUrl,
            String reportToken, String agentToken) {
        JsonObject request = new JsonObject();
        request.addProperty("db_name", dbName);
        request.addProperty("db_user", dbUsername);
        request.addProperty("db_password", dbPassword);
        if (dbUserRo != null && !dbUserRo.isEmpty()) {
            request.addProperty("db_user_ro", dbUserRo);
            request.addProperty("db_password_ro", dbPasswordRo);
        }
        if (reportUrl != null && !reportUrl.isEmpty()) {
            request.addProperty("vm_id", vmUuid);
            request.addProperty("report_url", reportUrl);
            request.addProperty("api_url", reportUrl);
            request.addProperty("report_token", reportToken);
            if (agentToken != null && !agentToken.isEmpty()) {
                request.addProperty("agent_token", agentToken);
            }
        }

        String cloudConfig = "#cloud-config\n"
                + "write_files:\n"
                + "  - path: /var/lib/dbaas/request.json\n"
                + "    permissions: '0600'\n"
                + "    owner: root:root\n"
                + "    content: |\n"
                + "      " + request.toString() + "\n"
                + "runcmd:\n"
                + "  - [ /opt/dbaas/firstboot.sh ]\n";
        return Base64.getEncoder().encodeToString(cloudConfig.getBytes(StandardCharsets.UTF_8));
    }

    // The instance's first IPv4 address, or null while it has none yet (a
    // stopped instance that has never started has no NIC address).
    private String primaryIpAddress(VirtualMachine vm) {
        if (vm == null) {
            return null;
        }
        for (NicVO nic : _nicDao.listByVmIdOrderByDeviceId(vm.getId())) {
            if (nic.getIPv4Address() != null) {
                return nic.getIPv4Address();
            }
        }
        return null;
    }

    private Integer enginePort(JsonObject engineConfig) {
        return engineConfig != null && engineConfig.has("port") ? engineConfig.get("port").getAsInt() : null;
    }

    @Override
    public boolean start() {
        s_runningManager = this;
        ensureCredentialsTableExists();
        ensureConsoleTablesExists();
        cleanupOrphanedCredentials();
        cleanupOrphanedDataDisks();
        scheduleCredentialsCleanup();
        return true;
    }

    @Override
    public boolean stop() {
        if (credentialsCleanupExecutor != null) {
            credentialsCleanupExecutor.shutdown();
        }
        s_runningManager = null;
        return true;
    }

    // The interval is configurable (dbaas.credentials.cleanup.interval, in
    // seconds); the executor is created per start and shut down on stop, the
    // same lifecycle StorageManagerImpl uses for its scavenger.
    private void scheduleCredentialsCleanup() {
        final long intervalSeconds = DbaasCredentialsCleanupInterval.value();
        if (intervalSeconds <= 0) {
            logger.info("dbaas.credentials.cleanup.interval is {} -- credential sweeping disabled", intervalSeconds);
            return;
        }
        credentialsCleanupExecutor = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("Dbaas-Credentials-Cleanup"));
        credentialsCleanupExecutor.scheduleWithFixedDelay(() -> {
            try {
                cleanupOrphanedCredentials();
                cleanupOrphanedDataDisks();
                sweepConsole();
            } catch (Throwable t) {
                // The sweeper must never bring its thread down: a failed sweep
                // simply retries on the next interval.
                logger.warn("credentials cleanup sweep failed", t);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        logger.info("credentials cleanup sweep scheduled every {} s", intervalSeconds);
    }

    // Deletes stored credentials whose instance has been expunged: the row is
    // keyed on the instance uuid and vm_instance rows that were expunged carry
    // a removal timestamp, while rows for live, destroyed (recoverable) or
    // missing-from-vm_instance edge cases are handled by the join criterion.
    // Never throws: a failed sweep is logged and retried on the next interval.
    private void cleanupOrphanedCredentials() {
        final String sql = "DELETE c FROM dbaas_credentials c "
                + "LEFT JOIN vm_instance v ON v.uuid = c.vm_id "
                + "WHERE v.id IS NULL OR v.removed IS NOT NULL";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement(sql)) {
                final int deleted = pstmt.executeUpdate();
                if (deleted > 0) {
                    logger.info("credentials cleanup sweep deleted {} row(s) for expunged instances", deleted);
                }
            }
        } catch (Exception e) {
            logger.warn("credentials cleanup sweep failed", e);
        }
    }

    // Log-only on purpose: orphaned DATADISK volumes hold tenant data that
    // cannot be recovered once deleted, and no human confirmed the removal --
    // the sweeper only reports the count and total size so an admin can act.
    private void reportOrphanedDataDisks() {
        // Same orphan criterion as the credential sweep, restricted to data
        // disks that are still attached to something that was expunged.
        final String sql = "SELECT COUNT(*), COALESCE(SUM(v.size), 0) FROM volumes v "
                + "LEFT JOIN vm_instance i ON i.id = v.instance_id "
                + "WHERE v.volume_type = 'DATADISK' AND v.removed IS NULL "
                + "AND v.instance_id IS NOT NULL AND v.instance_id > 0 "
                + "AND (i.id IS NULL OR i.removed IS NOT NULL)";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement(sql); ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    final long count = rs.getLong(1);
                    final long totalSize = rs.getLong(2);
                    if (count > 0) {
                        logger.warn("found {} orphaned DATADISK volume(s) ({} bytes total) belonging to "
                                + "expunged instances -- admin decision required before deleting them", count, totalSize);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("orphaned data disk report failed", e);
        }
    }

    // Opt-in data-disk cleanup (dbaas.datadisk.cleanup.enabled, default
    // false): a DATADISK is marked removed only when EVERY condition holds --
    // unattached, its instance is expunged or purged, it carries the
    // dbaas.instance marker written at create time, and it is older than the
    // grace period. A volume without the marker -- anything created before
    // markers existed, DATA-73 included -- can never match. Only the database
    // row is removed; the primary storage file is logged for the admin.
    private void cleanupOrphanedDataDisks() {
        if (!DbaasDataDiskCleanupEnabled.value()) {
            reportOrphanedDataDisks();
            return;
        }
        final String find = "SELECT v.id FROM volumes v"
                + " JOIN volume_details vd ON vd.volume_id = v.id AND vd.name = 'dbaas.instance'"
                + " LEFT JOIN vm_instance i ON i.uuid = vd.value"
                + " WHERE v.volume_type = 'DATADISK' AND v.removed IS NULL"
                + " AND (v.instance_id IS NULL OR v.instance_id = 0)"
                + " AND (i.id IS NULL OR i.removed IS NOT NULL)"
                + " AND v.created < DATE_SUB(NOW(), INTERVAL " + DATA_DISK_GRACE_SECONDS + " SECOND)";
        int deleted = 0;
        int failed = 0;
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            final List<Long> candidates = new ArrayList<>();
            try (PreparedStatement pstmt = txn.prepareStatement(find); ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(rs.getLong(1));
                }
            }
            for (final Long volumeId : candidates) {
                // Deletion goes through the volume service so the storage
                // file, capacity accounting, resource counts and usage events
                // are handled together. A bare UPDATE on volumes would hide
                // the row and leave the qcow2 on primary storage.
                final VolumeVO volume = volumeDao.findById(volumeId);
                if (volume == null) {
                    continue;
                }
                final Account caller = accountDao.findById(volume.getAccountId());
                if (caller == null) {
                    logger.warn("dbaas sweeper could not resolve the owner account of orphaned data disk {}"
                            + " ({}), leaving it in place", volume.getName(), volume.getUuid());
                    failed++;
                    continue;
                }
                try {
                    if (volumeApiService.deleteVolume(volumeId, caller)) {
                        deleted++;
                        logger.warn("dbaas sweeper deleted orphaned data disk {} ({}, {} bytes) past the"
                                + " {}s grace period", volume.getName(), volume.getUuid(), volume.getSize(),
                                DATA_DISK_GRACE_SECONDS);
                    } else {
                        failed++;
                        logger.warn("dbaas sweeper could not delete orphaned data disk {} ({}), the row stays"
                                + " visible for the admin", volume.getName(), volume.getUuid());
                    }
                } catch (Exception e) {
                    failed++;
                    logger.warn("dbaas sweeper failed to delete orphaned data disk {} ({}), the row stays"
                            + " visible for the admin", volume.getName(), volume.getUuid(), e);
                }
            }
        } catch (Exception e) {
            logger.warn("orphaned data disk cleanup failed", e);
            return;
        }
        if (deleted > 0 || failed > 0) {
            logger.warn("dbaas data-disk cleanup pass: {} deleted through the volume service, {} left in place",
                    deleted, failed);
        }
    }

    // ===== console transport (PLAN-DBAAS-CONSOLE.md C1) =====

    @Override
    public boolean isConsoleEnabled() {
        return DbaasConsoleEnabled.value();
    }

    @Override
    public boolean isConsoleWriteEnabled() {
        return DbaasConsoleWriteEnabled.value();
    }

    @Override
    public boolean isConsoleDropEnabled() {
        return DbaasConsoleDropEnabled.value();
    }

    @Override
    public int consoleRowLimit() {
        return DbaasConsoleRowLimit.value();
    }

    @Override
    public long getJobAccountId(String jobUuid) {
        String sql = "SELECT account_id FROM dbaas_jobs WHERE uuid = ?";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement(sql)) {
                pstmt.setString(1, jobUuid);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                    return -1;
                }
            }
        } catch (Exception e) {
            logger.warn("failed to look up the console job {}", jobUuid, e);
            return -1;
        }
    }


    static final String CONSOLE_SCHEMA_RESOURCE = "db/schema-dbaas-console.sql";

    private void ensureConsoleTablesExists() {
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB);
                InputStream in = DbaasManagerImpl.class.getClassLoader()
                        .getResourceAsStream(CONSOLE_SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IOException("console schema resource not found on the classpath: " + CONSOLE_SCHEMA_RESOURCE);
            }
            String contents = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String cleaned = Arrays.stream(contents.split("\n"))
                    .filter(line -> !line.trim().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            for (String stmt : cleaned.split(";")) {
                String sql = stmt.trim();
                if (sql.isEmpty()) {
                    continue;
                }
                try (PreparedStatement pstmt = txn.prepareStatement(sql)) {
                    pstmt.executeUpdate();
                }
            }
            logger.info("console tables ensured");
        } catch (Exception e) {
            logger.error("failed to ensure the console tables exist -- the console will not work", e);
        }
    }

    // Instances provisioned before the db_role column existed get 'owner':
    // their one credential was the owner credential.
    private void ensureDbRoleColumn(TransactionLegacy txn) throws SQLException {
        String check = "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = 'dbaas_credentials' AND COLUMN_NAME = 'db_role'";
        try (PreparedStatement pstmt = txn.prepareStatement(check); ResultSet rs = pstmt.executeQuery()) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }
        try (PreparedStatement pstmt = txn.prepareStatement(
                "ALTER TABLE `dbaas_credentials` ADD COLUMN `db_role` varchar(16) NOT NULL DEFAULT 'owner'")) {
            pstmt.executeUpdate();
            logger.info("added db_role column to dbaas_credentials (existing rows default to owner)");
        }
    }

    // One live agent token per instance: minted at createDatabase, rotated by
    // the agent itself, revoked with the instance by the sweeper.
    private void recordAgentToken(Long vmId, String tokenHash) {
        String sql = "INSERT INTO dbaas_agent_tokens (vm_id, token_hash) VALUES (?, ?)"
                + " ON DUPLICATE KEY UPDATE token_hash = VALUES(token_hash), rotated_at = NOW(),"
                + " last_seen_at = NULL";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement(sql)) {
                pstmt.setLong(1, vmId);
                pstmt.setString(2, tokenHash);
                pstmt.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("failed to record the agent token for VM {}", vmId, e);
        }
    }

    // Validates vmid + token against dbaas_agent_tokens (hash only; the raw
    // token is never stored) and touches last_seen_at for the online flag.
    public boolean isAgentTokenValid(String vmUuid, String token) {
        String sql = "SELECT t.id, t.token_hash FROM dbaas_agent_tokens t"
                + " JOIN vm_instance v ON v.id = t.vm_id WHERE v.uuid = ? AND v.removed IS NULL";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement(sql)) {
                pstmt.setString(1, vmUuid);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    long tokenId = rs.getLong(1);
                    boolean matches = sha256Hex(token).equals(rs.getString(2));
                    if (matches) {
                        try (PreparedStatement touch = txn.prepareStatement(
                                "UPDATE dbaas_agent_tokens SET last_seen_at = NOW() WHERE id = ?")) {
                            touch.setLong(1, tokenId);
                            touch.executeUpdate();
                        }
                    }
                    return matches;
                }
            }
        } catch (Exception e) {
            logger.warn("agent token validation failed for VM {}", vmUuid, e);
            return false;
        }
    }

    // Creates a console job. The caller has validated every parameter and
    // resolved the target role; this only persists and timestamps.
    public String createConsoleJob(Long vmId, long accountId, String type, String payload, String dbRole) {
        String uuid = UUID.randomUUID().toString();
        String sql = "INSERT INTO dbaas_jobs (uuid, vm_id, account_id, type, db_role, payload, state, expires_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'pending', DATE_ADD(NOW(), INTERVAL ? SECOND))";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement(sql)) {
                pstmt.setString(1, uuid);
                pstmt.setLong(2, vmId);
                pstmt.setLong(3, accountId);
                pstmt.setString(4, type);
                pstmt.setString(5, dbRole);
                pstmt.setString(6, DBEncryptionUtil.encrypt(payload));
                pstmt.setInt(7, DbaasJobTtl.value());
                pstmt.executeUpdate();
            }
        } catch (Exception e) {
            throw new CloudRuntimeException("failed to create the console job: " + e.getMessage(), e);
        }
        // Audit line: job uuid, type, account, instance, role -- never the
        // payload, which can carry SQL text as sensitive as the data.
        logger.info("console job {} created: type={} vm={} account={} role={}", uuid, type, vmId, accountId, dbRole);
        return uuid;
    }

    // The engine type for a template, derived from its script entry in
    // config.json (mysql.sh -> mysql): the key the type allowlist is keyed by.
    public String consoleEngineTypeForVm(Long vmId) {
        JsonObject engine = engineConfigForVm(vmId);
        if (engine == null || !engine.has("script")) {
            return null;
        }
        String script = engine.get("script").getAsString();
        return script.endsWith(".sh") ? script.substring(0, script.length() - 3) : script;
    }

    // The per-engine column-type allowlist, read from config.json -- never a
    // hardcoded list in Java, same rule as the engines map.
    public List<String> consoleTypeAllowlist(String engineType) {
        try {
            JsonObject types = readEnginesConfig().getAsJsonObject("types");
            if (types == null || !types.has(engineType)) {
                return new ArrayList<>();
            }
            List<String> result = new ArrayList<>();
            for (JsonElement e : types.get(engineType).getAsJsonArray()) {
                result.add(e.getAsString());
            }
            return result;
        } catch (Exception e) {
            logger.warn("could not read the type allowlist for engine {}", engineType, e);
            return new ArrayList<>();
        }
    }

    // Long-poll for the agent: holds up to longPollSeconds waiting for a
    // pending job, marks it dispatched exactly once, and returns the job as
    // JSON (payload decrypted, limits included, plus a rotated token when due).
    // Returns an empty string when the hold expired with nothing to do.
    public String agentPollJob(String vmUuid, int longPollSeconds) {
        String vmIdSql = "SELECT t.vm_id, t.token_hash, t.rotated_at FROM dbaas_agent_tokens t"
                + " JOIN vm_instance v ON v.id = t.vm_id WHERE v.uuid = ? AND v.removed IS NULL";
        long vmId = -1;
        String tokenHash = null;
        java.sql.Timestamp rotatedAt = null;
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement(vmIdSql)) {
                pstmt.setString(1, vmUuid);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        vmId = rs.getLong(1);
                        tokenHash = rs.getString(2);
                        rotatedAt = rs.getTimestamp(3);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("agent poll failed to resolve the VM {}", vmUuid, e);
            return "";
        }
        if (vmId < 0) {
            return "";
        }
        long deadline = System.nanoTime() + longPollSeconds * 1_000_000_000L;
        long[] jobId = {-1};
        String jobUuid = null;
        String type = null;
        String dbRole = null;
        String payloadEncrypted = null;
        while (System.nanoTime() < deadline) {
            String find = "SELECT id, uuid, type, db_role, payload FROM dbaas_jobs"
                    + " WHERE vm_id = ? AND state = 'pending' AND expires_at > NOW()"
                    + " ORDER BY created_at ASC LIMIT 1";
            try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
                try (PreparedStatement pstmt = txn.prepareStatement(find)) {
                    pstmt.setLong(1, vmId);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            jobId[0] = rs.getLong(1);
                            jobUuid = rs.getString(2);
                            type = rs.getString(3);
                            dbRole = rs.getString(4);
                            payloadEncrypted = rs.getString(5);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("agent poll failed for VM {}", vmUuid, e);
                return "";
            }
            if (jobId[0] > 0) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
        if (jobId[0] < 0) {
            return "";
        }
        // Mark dispatched exactly once: the state predicate makes a concurrent
        // second dispatch update zero rows.
        String dispatch = "UPDATE dbaas_jobs SET state = 'dispatched', dispatched_at = NOW()"
                + " WHERE id = ? AND state = 'pending'";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement(dispatch)) {
                pstmt.setLong(1, jobId[0]);
                if (pstmt.executeUpdate() == 0) {
                    return "";   // someone else took it; the agent re-polls
                }
            }
        } catch (Exception e) {
            logger.warn("agent poll failed to dispatch job {} for VM {}", jobUuid, vmUuid, e);
            return "";
        }
        JsonObject response = new JsonObject();
        response.addProperty("jobid", jobUuid);
        response.addProperty("type", type);
        response.addProperty("db_role", dbRole);
        response.addProperty("payload", DBEncryptionUtil.decrypt(payloadEncrypted));
        response.addProperty("row_limit", DbaasConsoleRowLimit.value());
        response.addProperty("bytes_limit", DbaasConsoleBytesLimit.value());
        response.addProperty("timeout_seconds", DbaasConsoleStatementTimeout.value());
        // Token rotation: a successful poll past the rotation age hands the
        // agent a fresh token, which replaces the old one on its next call.
        if (rotatedAt == null || rotatedAt.getTime() < System.currentTimeMillis()
                - DbaasAgentTokenRotateDays.value() * 86_400_000L) {
            String fresh = generateReportToken();
            try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
                try (PreparedStatement pstmt = txn.prepareStatement(
                        "UPDATE dbaas_agent_tokens SET token_hash = ?, rotated_at = NOW() WHERE vm_id = ?")) {
                    pstmt.setString(1, sha256Hex(fresh));
                    pstmt.setLong(2, vmId);
                    pstmt.executeUpdate();
                }
            } catch (Exception e) {
                logger.warn("agent token rotation failed for VM {}", vmUuid, e);
            }
            response.addProperty("new_token", fresh);
        }
        return response.toString();
    }

    // The agent reports a finished job. Validates that the job belongs to
    // this VM+token and is in the dispatched state, writes the encrypted
    // result row, and closes the job. Returns false when anything does not
    // line up -- the caller answers 403 with an identical body either way.
    public boolean agentReportResult(String vmUuid, String token, String jobUuid, String status,
            int rowCount, boolean truncated, String result, String error) {
        String find = "SELECT j.id FROM dbaas_jobs j"
                + " JOIN dbaas_agent_tokens t ON t.vm_id = j.vm_id"
                + " JOIN vm_instance v ON v.id = t.vm_id"
                + " WHERE j.uuid = ? AND v.uuid = ? AND t.token_hash = ? AND j.state = 'dispatched'";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            long jobId = -1;
            try (PreparedStatement pstmt = txn.prepareStatement(find)) {
                pstmt.setString(1, jobUuid);
                pstmt.setString(2, vmUuid);
                pstmt.setString(3, sha256Hex(token));
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        jobId = rs.getLong(1);
                    }
                }
            }
            if (jobId < 0) {
                return false;
            }
            boolean ok = STATUS_CONFIRMED.equals(status);
            try (PreparedStatement ins = txn.prepareStatement(
                    "INSERT INTO dbaas_job_results (job_id, result) VALUES (?, ?)")) {
                ins.setLong(1, jobId);
                ins.setString(2, DBEncryptionUtil.encrypt(result == null ? "" : result));
                ins.executeUpdate();
            }
            try (PreparedStatement upd = txn.prepareStatement(
                    "UPDATE dbaas_jobs SET state = ?, finished_at = NOW(), row_count = ?, truncated = ?,"
                    + " error = ? WHERE id = ? AND state = 'dispatched'")) {
                upd.setString(1, ok ? STATUS_CONFIRMED : STATUS_FAILED);
                if (rowCount >= 0) {
                    upd.setInt(2, rowCount);
                } else {
                    upd.setNull(2, java.sql.Types.INTEGER);
                }
                upd.setInt(3, truncated ? 1 : 0);
                if (error != null && !error.isEmpty()) {
                    upd.setString(4, error.length() > 1000 ? error.substring(0, 1000) : error);
                } else {
                    upd.setNull(4, java.sql.Types.VARCHAR);
                }
                upd.setLong(5, jobId);
                int updated = upd.executeUpdate();
                if (updated > 0) {
                    logger.info("console job {} {}: rows={} truncated={}", jobUuid,
                            ok ? "done" : "failed", rowCount, truncated);
                    return true;
                }
                return false;
            }
        } catch (Exception e) {
            logger.warn("failed to record the console result for job {}", jobUuid, e);
            return false;
        }
    }

    // Delete-on-read: the result row is removed the moment it is fetched,
    // the job row stays as the audit trail. Returns null when the job does
    // not exist for this account; the caller turns that into a not-found.
    public String getUserJobResult(String jobUuid, long accountId) {
        String find = "SELECT id, state, type, row_count, truncated, error FROM dbaas_jobs"
                + " WHERE uuid = ? AND account_id = ?";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            long jobId = -1;
            String state = null;
            String type = null;
            long rowCount = -1;
            boolean truncated = false;
            String error = null;
            try (PreparedStatement pstmt = txn.prepareStatement(find)) {
                pstmt.setString(1, jobUuid);
                pstmt.setLong(2, accountId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        jobId = rs.getLong(1);
                        state = rs.getString(2);
                        type = rs.getString(3);
                        rowCount = rs.getLong(4);
                        truncated = rs.getBoolean(5);
                        error = rs.getString(6);
                    }
                }
            }
            if (jobId < 0) {
                return null;
            }
            JsonObject response = new JsonObject();
            response.addProperty("jobid", jobUuid);
            response.addProperty("type", type);
            response.addProperty("state", state);
            response.addProperty("row_count", rowCount);
            response.addProperty("truncated", truncated);
            if (error != null) {
                response.addProperty("error", error);
            }
            if (STATUS_CONFIRMED.equals(state)) {
                String resultSql = "SELECT result FROM dbaas_job_results WHERE job_id = ?";
                try (PreparedStatement pstmt = txn.prepareStatement(resultSql)) {
                    pstmt.setLong(1, jobId);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            response.addProperty("result", DBEncryptionUtil.decrypt(rs.getString(1)));
                            try (PreparedStatement del = txn.prepareStatement(
                                    "DELETE FROM dbaas_job_results WHERE job_id = ?")) {
                                del.setLong(1, jobId);
                                del.executeUpdate();
                            }
                        } else {
                            response.addProperty("collected", true);
                        }
                    }
                }
            }
            return response.toString();
        } catch (Exception e) {
            throw new CloudRuntimeException("failed to read the console job result: " + e.getMessage(), e);
        }
    }

    // Sweeps the console tables: undispatched jobs expire by TTL, uncollected
    // results die by their own TTL, and agent tokens of expunged instances
    // are revoked.
    private void sweepConsole() {
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement(
                    "UPDATE dbaas_jobs SET state = 'expired', finished_at = NOW()"
                    + " WHERE state = 'pending' AND expires_at < NOW()")) {
                int expired = pstmt.executeUpdate();
                if (expired > 0) {
                    logger.info("console sweep expired {} undispatched job(s)", expired);
                }
            }
            try (PreparedStatement pstmt = txn.prepareStatement(
                    "DELETE FROM dbaas_job_results"
                    + " WHERE created_at < DATE_SUB(NOW(), INTERVAL " + DbaasJobResultTtl.value() + " SECOND)")) {
                int swept = pstmt.executeUpdate();
                if (swept > 0) {
                    logger.info("console sweep removed {} uncollected result(s)", swept);
                }
            }
            try (PreparedStatement pstmt = txn.prepareStatement(
                    "DELETE t FROM dbaas_agent_tokens t"
                    + " LEFT JOIN vm_instance v ON v.id = t.vm_id"
                    + " WHERE v.id IS NULL OR v.removed IS NOT NULL")) {
                int revoked = pstmt.executeUpdate();
                if (revoked > 0) {
                    logger.info("console sweep revoked {} agent token(s) of expunged instances", revoked);
                }
            }
        } catch (Exception e) {
            logger.warn("console sweep failed", e);
        }
    }

    static final String SCHEMA_RESOURCE = "db/schema-dbaas-credentials.sql";

    /**
     * The one definition of the table lives in the .sql resource bundled into
     * this jar, so the DDL cannot drift between a Java string and a file
     * nobody runs. Read from the classpath rather than the filesystem: the
     * resource travels inside the jar, with no deployment path to get wrong.
     */
    static String readSchemaStatement() throws IOException {
        try (InputStream in = DbaasManagerImpl.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IOException("schema resource not found on the classpath: " + SCHEMA_RESOURCE);
            }
            String contents = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // The file is one statement wrapped in explanatory comments; strip
            // the comments and the trailing semicolon so it can be prepared.
            String sql = Arrays.stream(contents.split("\n"))
                    .filter(line -> !line.trim().startsWith("--"))
                    .collect(Collectors.joining("\n"))
                    .trim();
            if (sql.endsWith(";")) {
                sql = sql.substring(0, sql.length() - 1);
            }
            if (sql.isEmpty()) {
                throw new IOException("schema resource contained no statement: " + SCHEMA_RESOURCE);
            }
            return sql;
        }
    }

    // No DatabaseUpgradeChecker hook for this plugin (see schema-dbaas-credentials.sql),
    // so every management server start is what stands in for a migration step.
    // CREATE TABLE IF NOT EXISTS makes repeating it on every start harmless.
    private void ensureCredentialsTableExists() {
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            String sql = readSchemaStatement();
            PreparedStatement pstmt = txn.prepareStatement(sql);
            pstmt.executeUpdate();
            ensureLegacyVmColumnsDropped(txn);
            ensureDbRoleColumn(txn);
        } catch (Exception e) {
            // Credential storage degrades gracefully (see storeCredential), so
            // a management server that can't create this table should still
            // come up and serve create_database/reset_password normally.
            // Exception, not SQLException: TransactionLegacy.open() throws
            // unchecked CloudRuntimeException (DB down / pool exhausted) that
            // a SQLException catch would let escape and fail management start.
            logger.error("failed to ensure dbaas_credentials table exists", e);
        }
    }

    // The vm_username / vm_password_encrypted columns shipped briefly and
    // were removed: the instance login password is shown once at creation and
    // never stored. Tables created during that window get the columns dropped
    // here; CREATE TABLE IF NOT EXISTS is unchanged for everyone else.
    private void ensureLegacyVmColumnsDropped(TransactionLegacy txn) throws SQLException {
        String check = "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = 'dbaas_credentials' AND COLUMN_NAME = 'vm_username'";
        try (PreparedStatement pstmt = txn.prepareStatement(check); ResultSet rs = pstmt.executeQuery()) {
            if (rs.next() && rs.getInt(1) == 0) {
                return;
            }
        }
        try (PreparedStatement pstmt = txn.prepareStatement("ALTER TABLE `dbaas_credentials`"
                + " DROP COLUMN `vm_username`, DROP COLUMN `vm_password_encrypted`")) {
            pstmt.executeUpdate();
            logger.info("dropped legacy vm_username / vm_password_encrypted columns from dbaas_credentials");
        }
    }

    /**
     * Provisioning is entirely config-drive: the management server writes the
     * request onto the instance's config drive and (re)starts it, and the
     * instance configures its own engine at first boot. Nothing here connects
     * to the instance, so it works on networks the management server has no
     * route to, and with the virtual router down -- there is no SSH fallback
     * to fall back to.
     * <p>
     * User data is only read at boot, so a running instance is stopped first.
     * That is a real, brief interruption for "Create Database" on an
     * already-running instance (the wizard avoids it entirely by deploying
     * with startvm=false up front); the Create Database dialog warns the
     * tenant before submitting (CreateDatabase.vue), which is the only
     * warning there is -- the response carries no message field.
     * <p>
     * Identifiers and the template's config-drive support are validated, and
     * the Stopped state awaited, before anything else runs; every step after
     * the stop is wrapped so a failure starts the instance again instead of
     * leaving the tenant with an outage.
     * <p>
     * The credential is stored before the instance (re)starts, so Show
     * Password has something to show immediately; it stays 'pending' until
     * the instance reports back that its engine really was configured.
     */
    @Override
    public DbaasResponse createDatabase(CreateDatabaseCmd cmd) {
        VirtualMachine vm = _entityMgr.findById(VirtualMachine.class, cmd.getVirtualMachineId());
        if (vm == null) {
            throw new InvalidParameterValueException("VM not found: " + cmd.getVirtualMachineId());
        }
        // Fail fast on everything checkable BEFORE touching the instance's
        // power state, so a bad request never costs the tenant a restart.
        String dbUsername = cmd.getDbUsername();
        if (dbUsername == null || dbUsername.trim().isEmpty()) {
            dbUsername = cmd.getDbName();
        }
        validateIdentifier(cmd.getDbName(), "dbname");
        validateIdentifier(dbUsername, "dbusername");
        String dbPassword = validateOrGeneratePassword(cmd.getDbPassword());
        // The console's read-only role: same charset rules, name derived from
        // the owner. Created by the engine script from the request; stored as
        // a second credential row with db_role='readonly'.
        final String dbUserRo = dbUsername + "_ro";
        final String dbPasswordRo = generatePassword();

        VirtualMachineTemplate template = _entityMgr.findById(VirtualMachineTemplate.class, vm.getTemplateId());
        if (template == null) {
            throw new InvalidParameterValueException("template not found for instance " + vm.getUuid());
        }
        String engineName = template.getName();
        requireConfigDriveTemplate(template);
        JsonObject engineConfig = engineConfigForVm(cmd.getVirtualMachineId());
        if (engineConfig == null) {
            throw new InvalidParameterValueException("template " + engineName + " is not listed in the engines map"
                    + " of " + DbaasConfigPath.value() + ", so this plugin cannot serve databases from it;"
                    + " add an engines entry for it or deploy from a template that is listed");
        }

        if (vm.getState() == VirtualMachine.State.Running) {
            try {
                userVmService.stopVirtualMachine(vm.getId(), false);
            } catch (Exception e) {
                throw new CloudRuntimeException("could not stop instance " + vm.getUuid() + " to attach the"
                        + " provisioning request (config-drive user data is only read at boot): " + e.getMessage(), e);
            }
            // stopVirtualMachine returns before the transition is necessarily
            // visible to the entity manager -- poll briefly instead of
            // re-reading once, or a just-stopped instance can still read
            // Running and the state check would fail on a stale read.
            if (!awaitVmState(vm.getId(), VirtualMachine.State.Stopped, 30)) {
                throw new CloudRuntimeException("instance " + vm.getUuid() + " did not reach Stopped within 30s"
                        + " of being stopped; refusing to attach the provisioning request in an unknown state");
            }
        }
        try {
            String reportUrl = DbaasReportApiUrl.value();
            boolean reportingConfigured = reportUrl != null && !reportUrl.isEmpty();
            String reportToken = reportingConfigured ? generateReportToken() : null;
            String agentToken = reportingConfigured ? generateReportToken() : null;
            String userData = buildUserData(cmd.getDbName(), dbUsername, dbPassword, dbUserRo, dbPasswordRo,
                    vm.getUuid(), reportUrl, reportToken, agentToken);
            userVmManager.updateVirtualMachine(vm.getId(), null, null, null, null, null, null,
                    userData, null, null, null, BaseCmd.HTTPMethod.POST, null, null, null, null, null);

            // Stored before the start, not after: a start that fails leaves an
            // instance the tenant can start themselves, and the credential it
            // will provision with must already be recoverable when they do.
            // Only the token's hash is kept; the raw value already left with
            // the user data and cannot be recovered from this row.
            if (reportToken != null) {
                java.sql.Timestamp expiresAt = new java.sql.Timestamp(
                        System.currentTimeMillis() + DbaasReportTokenTtl.value() * 1000L);
                storeCredential(vm.getUuid(), dbUsername, dbPassword, engineName, STATUS_PENDING,
                        sha256Hex(reportToken), expiresAt, ROLE_OWNER);
            } else {
                logger.warn("dbaas.report.api.url is not set -- instance {} cannot report its provisioning result,"
                        + " and its credential will stay 'pending'", vm.getUuid());
                storeCredential(vm.getUuid(), dbUsername, dbPassword, engineName, STATUS_PENDING,
                        null, null, ROLE_OWNER);
            }
            // The console's read-only credential, stored as its own row so
            // Show Password per role and the agent's roles.json both resolve.
            storeCredential(vm.getUuid(), dbUserRo, dbPasswordRo, engineName, STATUS_PENDING,
                    null, null, ROLE_READONLY);
            if (agentToken != null) {
                recordAgentToken(vm.getId(), sha256Hex(agentToken));
            }

            // Looked up as a UserVm rather than cast: the entity manager hands back
            // whatever VO backs the row, and a cast would only fail at runtime.
            UserVm userVm = _entityMgr.findById(UserVm.class, cmd.getVirtualMachineId());
            if (userVm == null) {
                throw new CloudRuntimeException("instance " + vm.getUuid()
                        + " carries the provisioning request but is not a user instance, so it cannot be started here");
            }
            userVmService.startVirtualMachine(userVm, null);
        } catch (Exception e) {
            // The instance was stopped for this request: leaving it stopped
            // without a database would turn a failed create into an outage.
            // Best-effort start, then surface the original failure.
            restartQuietly(cmd.getVirtualMachineId(), vm.getUuid());
            if (e instanceof CloudRuntimeException) {
                throw (CloudRuntimeException) e;
            }
            throw new CloudRuntimeException("failed to attach the provisioning request to instance "
                    + vm.getUuid() + " and start it: " + e.getMessage(), e);
        }

        // Tag the data disks now that the first start has attached them: a
        // volume requested with the deploy is not linked to the instance
        // until that first attach, and an unlinked disk is invisible both to
        // the destroy-time lookup and to the cleanup sweeper.
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement(
                    "INSERT INTO volume_details (volume_id, name, value, display)"
                    + " SELECT v.id, 'dbaas.instance', ?, 1 FROM volumes v"
                    + " WHERE v.instance_id = ? AND v.volume_type = 'DATADISK' AND v.removed IS NULL"
                    + " AND NOT EXISTS (SELECT 1 FROM volume_details vd"
                    + " WHERE vd.volume_id = v.id AND vd.name = 'dbaas.instance' AND vd.value = ?)")) {
                pstmt.setString(1, vm.getUuid());
                pstmt.setLong(2, cmd.getVirtualMachineId());
                pstmt.setString(3, vm.getUuid());
                final int tagged = pstmt.executeUpdate();
                if (tagged > 0) {
                    logger.info("marked {} data disk(s) of instance {} with dbaas.instance for cleanup tracking",
                            tagged, vm.getUuid());
                }
            }
        } catch (Exception e) {
            logger.warn("could not mark the data disks of {} with dbaas.instance -- cleanup falls back"
                    + " to the UI lookup", vm.getUuid(), e);
        }

        DbaasResponse response = new DbaasResponse();
        response.setEngine(engineName);
        // Read after the start: the pre-start VM's NIC can still have no
        // address, which would render the UI's connect command blank.
        VirtualMachine started = _entityMgr.findById(VirtualMachine.class, cmd.getVirtualMachineId());
        response.setHost(primaryIpAddress(started));
        response.setPort(enginePort(engineConfig));
        response.setDatabase(cmd.getDbName());
        response.setUsername(dbUsername);
        response.setPassword(dbPassword);
        response.setObjectName("dbaas");
        return response;
    }

    // A template that is not built for config-drive provisioning would boot,
    // read nothing from the config drive, and leave its credential 'pending'
    // forever with no error anywhere -- reject it up front instead. The image
    // itself cannot be inspected from here, so the template carries the
    // marker: detail dbaas.configdrive=true, set at registration (see
    // TEMPLATES.md).
    private void requireConfigDriveTemplate(VirtualMachineTemplate template) {
        Map<?, ?> details = _templateDetailsDao.listDetailsKeyPairs(template.getId());
        Object value = details == null ? null : details.get(CONFIGDRIVE_DETAIL_KEY);
        if (value == null || !"true".equalsIgnoreCase(String.valueOf(value).trim())) {
            throw new InvalidParameterValueException("template " + template.getName() + " does not declare"
                    + " config-drive provisioning support: set its " + CONFIGDRIVE_DETAIL_KEY + "=true template"
                    + " detail on an image built for config-drive provisioning (one carrying"
                    + " /opt/dbaas/firstboot.sh), or deploy from a template that has it -- otherwise the request"
                    + " would never be read and the credential would stay 'pending' forever");
        }
    }

    // Waits up to the given seconds for the instance to reach the expected
    // state, polling once a second; false on timeout or a vanished instance.
    private boolean awaitVmState(Long vmId, VirtualMachine.State expected, int seconds) {
        for (int waited = 0; waited < seconds; waited++) {
            VirtualMachine current = _entityMgr.findById(VirtualMachine.class, vmId);
            if (current == null) {
                return false;
            }
            if (current.getState() == expected) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // Best-effort start of an instance that this call stopped; every failure
    // is logged, none is propagated -- the caller rethrows the original error.
    private void restartQuietly(Long vmId, String uuid) {
        try {
            UserVm userVm = _entityMgr.findById(UserVm.class, vmId);
            if (userVm != null) {
                userVmService.startVirtualMachine(userVm, null);
            }
            logger.warn("instance {} was stopped to attach a provisioning request, the request failed, and it"
                    + " was started again", uuid);
        } catch (Exception startException) {
            logger.error("instance {} could not be provisioned AND could not be started again -- it is left"
                    + " Stopped and must be started manually", uuid, startException);
        }
    }

    // Resetting a database password needs a channel into a VM that is already
    // running its engine -- config-drive user data only ever runs at first
    // boot, so it cannot deliver this. That channel is the in-VM agent
    // (PLAN.md Phase D); until it exists there is no way to reset a database
    // password without SSH, which this plugin no longer has.
    @Override
    public DbaasResponse resetDatabasePassword(ResetDatabasePasswordCmd cmd) {
        throw new CloudRuntimeException("resetting a database password requires the in-VM agent (PLAN.md Phase D),"
                + " which does not exist yet -- config-drive provisioning only runs once, at first boot, and"
                + " cannot deliver a reset to an instance that is already running its engine");
    }

    @Override
    public DbaasResponse getDatabasePassword(GetDatabasePasswordCmd cmd) {
        // findById + ACL already ran in getEntityOwnerId before execute() was
        // reached; this just resolves the UUID the table is keyed on.
        String vmId = vmUuid(cmd.getVirtualMachineId());
        // Credentials are per (instance, role): the owner row for DDL and
        // writes, the readonly row for browse and query. Older rows --
        // provisioned before the column existed -- default to 'owner' via
        // the ALTER in ensureDbRoleColumn.
        String dbRole = cmd.getDbRole() == null || cmd.getDbRole().isEmpty()
                ? DbaasManagerImpl.ROLE_OWNER : cmd.getDbRole();

        String sql = "SELECT db_username, db_password_encrypted, engine, status, status_message FROM dbaas_credentials WHERE vm_id = ?"
                + (cmd.getDbUsername() != null ? " AND db_username = ?" : "")
                + " AND db_role = ?"
                + " ORDER BY created_at DESC, id DESC LIMIT 1";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            PreparedStatement pstmt = txn.prepareStatement(sql);
            pstmt.setString(1, vmId);
            if (cmd.getDbUsername() != null) {
                pstmt.setString(2, cmd.getDbUsername());
                pstmt.setString(3, dbRole);
            } else {
                pstmt.setString(2, dbRole);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                DbaasResponse response = new DbaasResponse();
                response.setObjectName("dbaas");
                // A miss (no row yet -- the database is still being
                // provisioned, or was never created) is not an error: respond
                // 200 with found=false so the UI can drive its auto-check UX
                // from a machine-readable flag instead of parsing error text.
                // Only genuine database failures are thrown as errors.
                if (!rs.next()) {
                    response.setFound(false);
                    return response;
                }
                response.setFound(true);
                response.setUsername(rs.getString("db_username"));
                response.setPassword(DBEncryptionUtil.decrypt(rs.getString("db_password_encrypted")));
                response.setEngine(rs.getString("engine"));
                // The recorded outcome, so the caller does not have to guess
                // whether a credential that exists was ever applied.
                response.setStatus(rs.getString("status"));
                response.setStatusMessage(rs.getString("status_message"));
                // The connection command needs a reachable host and the
                // engine's port: resolve the instance's current IP live (it
                // may have changed since provisioning) and take the port from
                // the engines map in config.json.
                VirtualMachine vm = _entityMgr.findById(VirtualMachine.class, cmd.getVirtualMachineId());
                if (vm != null) {
                    for (NicVO nic : _nicDao.listByVmIdOrderByDeviceId(vm.getId())) {
                        if (nic.getIPv4Address() != null) {
                            response.setHost(nic.getIPv4Address());
                            break;
                        }
                    }
                }
                try {
                    JsonObject engines = readEnginesConfig().getAsJsonObject("engines");
                    String engineKey = response.getEngine();
                    if (engineKey != null && engines.has(engineKey)) {
                        response.setPort(engines.get(engineKey).getAsJsonObject().get("port").getAsInt());
                    }
                } catch (Exception e) {
                    logger.warn("could not resolve engine port for {}", response.getEngine(), e);
                }
                return response;
            }
        } catch (Exception e) {
            throw new CloudRuntimeException("failed to read stored database credential", e);
        }
    }

    private String vmUuid(Long vmId) {
        VirtualMachine vm = _entityMgr.findById(VirtualMachine.class, vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("VM not found: " + vmId);
        }
        return vm.getUuid();
    }

    // create_database/reset_password both call this on every success, so
    // Show Password always reflects whatever the tenant's database credential
    // actually is right now -- not just what it was the first time. The
    // instance login password is intentionally not stored here; it is shown
    // exactly once on the creation screen / notification.
    private void storeCredential(String vmId, String dbUsername, String dbPassword, String engine, String status) {
        storeCredential(vmId, dbUsername, dbPassword, engine, status, null, null, ROLE_OWNER);
    }

    private void storeCredential(String vmId, String dbUsername, String dbPassword, String engine, String status,
            String reportTokenHash, java.sql.Timestamp reportTokenExpiresAt, String dbRole) {
        String sql = "INSERT INTO dbaas_credentials"
                + " (vm_id, db_username, db_password_encrypted, engine, status, report_token_hash,"
                + " report_token_expires_at, db_role)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            PreparedStatement pstmt = txn.prepareStatement(sql);
            pstmt.setString(1, vmId);
            pstmt.setString(2, dbUsername);
            pstmt.setString(3, DBEncryptionUtil.encrypt(dbPassword));
            pstmt.setString(4, engine);
            pstmt.setString(5, status);
            pstmt.setString(6, reportTokenHash);
            pstmt.setTimestamp(7, reportTokenExpiresAt);
            pstmt.setString(8, dbRole);
            pstmt.executeUpdate();
        } catch (Exception e) {
            // The provisioning call already succeeded and the tenant already
            // has the password from the API response -- losing the ability to
            // show it again later is degraded, not broken, so this does not
            // fail the whole request. Exception, not SQLException, for the
            // same unchecked-exception reason as ensureCredentialsTableExists.
            logger.error("failed to store dbaas credential for VM {}", vmId, e);
        }
    }

    // The engine entry for the template this instance was deployed from, or
    // null when config.json does not know it -- callers report that as their
    // own failure (a database on a template with no engine entry).
    private JsonObject engineConfigForVm(Long vmId) {
        try {
            VirtualMachine vm = _entityMgr.findById(VirtualMachine.class, vmId);
            if (vm == null) {
                return null;
            }
            VirtualMachineTemplate template = _entityMgr.findById(VirtualMachineTemplate.class, vm.getTemplateId());
            if (template == null) {
                return null;
            }
            JsonObject engines = readEnginesConfig().getAsJsonObject("engines");
            JsonElement entry = engines.get(template.getName());
            return entry == null ? null : entry.getAsJsonObject();
        } catch (Exception e) {
            logger.warn("could not resolve the engine config for VM {}", vmId, e);
            return null;
        }
    }

    @Override
    public List<DbaasEngineResponse> listEngines() {
        // A broken config.json must not take the whole API down: the UI's
        // engine picker and the Database section both call this, so a failure
        // here degrades to "no engines available" (logged loudly) instead of
        // erroring every page that touches the plugin. One malformed entry
        // skips that entry only -- the healthy ones still list.
        List<DbaasEngineResponse> result = new ArrayList<>();
        try {
            JsonObject engines = readEnginesConfig().getAsJsonObject("engines");
            for (Map.Entry<String, JsonElement> entry : engines.entrySet()) {
                try {
                    JsonObject cfg = entry.getValue().getAsJsonObject();
                    DbaasEngineResponse engine = new DbaasEngineResponse();
                    engine.setTemplate(entry.getKey());
                    engine.setPort(cfg.get("port").getAsInt());
                    engine.setObjectName("dbaasengine");
                    result.add(engine);
                } catch (Exception e) {
                    logger.warn("skipping malformed engine entry '{}' in the dbaas config", entry.getKey(), e);
                }
            }
        } catch (Exception e) {
            logger.error("failed to read dbaas engines from config.json -- reporting no engines", e);
        }
        return result;
    }

    // The engines map inside config.json is the single source of truth for
    // which templates are DBaaS engines.
    private JsonObject readEnginesConfig() {
        File configFile = new File(DbaasConfigPath.value());
        try (FileReader reader = new FileReader(configFile)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new CloudRuntimeException("failed to read dbaas engine config at " + configFile, e);
        }
    }

    // Takes the instance UUID directly (string): callers may target an
    // instance whose row is already expunged, where uuid -> id resolution is
    // not possible and not needed -- dbaas_credentials is keyed on the uuid.
    @Override
    public int deleteCredentialsForVm(String vmUuid) {
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            try (PreparedStatement pstmt = txn.prepareStatement("DELETE FROM dbaas_credentials WHERE vm_id = ?")) {
                pstmt.setString(1, vmUuid);
                final int deleted = pstmt.executeUpdate();
                logger.info("deleted {} dbaas credential row(s) for VM {}", deleted, vmUuid);
                return deleted;
            }
        } catch (Exception e) {
            // Cleanup is best-effort: the instance is already gone, the rows
            // only linger until the documented manual cleanup runs.
            logger.warn("failed to delete dbaas credentials for VM {}", vmUuid, e);
            return 0;
        }
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(CreateDatabaseCmd.class);
        cmdList.add(ResetDatabasePasswordCmd.class);
        cmdList.add(GetDatabasePasswordCmd.class);
        cmdList.add(ListDbaasEnginesCmd.class);
        cmdList.add(DeleteDbaasCredentialsCmd.class);
        // console job commands (PLAN-DBAAS-CONSOLE.md section 4.1)
        cmdList.add(ListDbaasTablesCmd.class);
        cmdList.add(DescribeDbaasTableCmd.class);
        cmdList.add(PreviewDbaasTableCmd.class);
        cmdList.add(CreateDbaasTableCmd.class);
        cmdList.add(DropDbaasTableCmd.class);
        cmdList.add(AddDbaasColumnCmd.class);
        cmdList.add(DropDbaasColumnCmd.class);
        cmdList.add(CreateDbaasIndexCmd.class);
        cmdList.add(DropDbaasIndexCmd.class);
        cmdList.add(RunDbaasQueryCmd.class);
        cmdList.add(GetDbaasJobResultCmd.class);
        return cmdList;
    }

    // ReportProvisioningResultCmd is registered here, not in getCommands():
    // getAuthCommands() is how the API framework discovers commands that
    // bypass normal signature auth (the same mechanism SAML/OAuth login use).
    // The instance calling it has no CloudStack credential, only the one-time
    // token minted for it in buildUserData().
    @Override
    public List<Class<?>> getAuthCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(ReportProvisioningResultCmd.class);
        // the console agent's long-poll and result reporting (PLAN-DBAAS-CONSOLE.md 4.3)
        cmdList.add(GetDbaasAgentJobCmd.class);
        cmdList.add(ReportDbaasJobResultCmd.class);
        return cmdList;
    }

    /**
     * Redeems a report token: matches it against the stored hash for the
     * given instance, checks it has not expired, and if both hold, records the
     * outcome and clears the token so it cannot be redeemed again. Every
     * failure path -- unknown instance, no pending report, wrong token,
     * expired token -- returns the same generic outcome, so a caller cannot
     * use the response to tell a wrong token from a nonexistent instance.
     *
     * @return true if the report was accepted
     */
    @Override
    public boolean applyProvisioningReport(String vmUuid, String token, String status, String message) {
        if (vmUuid == null || token == null || (!STATUS_CONFIRMED.equals(status) && !STATUS_FAILED.equals(status))) {
            return false;
        }
        // The column is varchar(1024): a longer message would fail the whole
        // UPDATE and lose the report exactly when it mattered most. Truncate
        // to the same cap firstboot.sh already applies.
        if (message != null && message.length() > STATUS_MESSAGE_MAX) {
            message = message.substring(0, STATUS_MESSAGE_MAX);
        }
        String tokenHash = sha256Hex(token);
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            // Find the newest unredeemed row for this instance, then compare
            // expiry in Java: the timestamp was written from this JVM's clock
            // (storeCredential), and comparing it against NOW() of the DB
            // would silently shift the real TTL with any clock skew between
            // the two machines.
            String find = "SELECT id, report_token_expires_at FROM dbaas_credentials"
                    + " WHERE vm_id = ? AND report_token_hash = ?"
                    + " ORDER BY created_at DESC, id DESC";
            long rowId = -1;
            java.sql.Timestamp expiresAt = null;
            try (PreparedStatement pstmt = txn.prepareStatement(find)) {
                pstmt.setString(1, vmUuid);
                pstmt.setString(2, tokenHash);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        rowId = rs.getLong(1);
                        expiresAt = rs.getTimestamp(2);
                    }
                }
            }
            if (rowId < 0 || expiresAt == null || expiresAt.getTime() <= System.currentTimeMillis()) {
                logger.warn("provisioning report rejected for VM {}: no matching pending token", vmUuid);
                return false;
            }
            // Clearing the hash in the same statement makes a concurrent
            // replay of the same token update zero rows instead of two.
            String sql = "UPDATE dbaas_credentials SET status = ?, status_message = ?,"
                    + " report_token_hash = NULL, report_token_expires_at = NULL"
                    + " WHERE id = ? AND report_token_hash = ?";
            try (PreparedStatement pstmt = txn.prepareStatement(sql)) {
                pstmt.setString(1, status);
                pstmt.setString(2, message);
                pstmt.setLong(3, rowId);
                pstmt.setString(4, tokenHash);
                int updated = pstmt.executeUpdate();
                if (updated > 0) {
                    logger.info("provisioning report accepted for VM {}: {}", vmUuid, status);
                    return true;
                }
                logger.warn("provisioning report rejected for VM {}: no matching pending token", vmUuid);
                return false;
            }
        } catch (Exception e) {
            logger.warn("failed to record provisioning report for VM {}", vmUuid, e);
            return false;
        }
    }

    @Override
    public String getConfigComponentName() {
        return DbaasManagerImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {DbaasConfigPath, DbaasCredentialsCleanupInterval,
                DbaasReportTokenTtl, DbaasReportApiUrl, DbaasReportRateLimit, DbaasDataDiskCleanupEnabled,
                DbaasConsoleEnabled, DbaasConsoleRowLimit, DbaasConsoleBytesLimit,
                DbaasConsoleStatementTimeout, DbaasConsoleWriteEnabled, DbaasConsoleDropEnabled,
                DbaasAgentLongPollSeconds, DbaasAgentTokenRotateDays, DbaasJobTtl, DbaasJobResultTtl,
                DbaasAgentLongPollMaxWaiters};
    }
}
