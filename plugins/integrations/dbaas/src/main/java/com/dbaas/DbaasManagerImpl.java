package com.dbaas;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
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

import javax.inject.Inject;

import org.joda.time.Duration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.cloud.utils.concurrency.NamedThreadFactory;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import com.cloud.vm.NicVO;
import com.cloud.vm.UserVm;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmService;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;

public class DbaasManagerImpl extends ManagerBase implements DbaasManager, PluggableService, Configurable {

    public static final ConfigKey<String> DbaasExtensionPath = new ConfigKey<>(
            "Advanced", String.class, "dbaas.extension.path",
            "/usr/share/cloudstack-management/extensions/dbaas/extension.py",
            "Filesystem path to the DBaaS extension.py entrypoint.", true);

    public static final ConfigKey<Integer> DbaasCredentialsCleanupInterval = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.credentials.cleanup.interval", "3600",
            // Seconds between orphaned-credential sweeps: rows whose instance
            // has been expunged (removed from vm_instance) are deleted, and
            // orphaned DATADISK volumes are counted and logged for the admin.
            "Interval in seconds between sweeps that delete stored credentials"
                    + " of expunged instances and report orphaned data disks.", true);

    public static final ConfigKey<Integer> DbaasProvisionTimeout = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.provision.timeout", "600",
            // 600s: the extension retries transient SSH failures internally
            // (3 attempts, 15s apart, each up to ssh timeout + the engine's
            // own internal wait -- MongoDB's rotation marker alone is 120s),
            // so the budget must cover the whole retry loop (~570s worst
            // case), not a single attempt. The timeout is handed to
            // extension.py, which derives its own retry budget from it
            // (roughly 2/3) and stops itself before this kill switch fires.
            "Timeout in seconds passed through to extension.py for provisioning.", true);

    @Inject
    private EntityManager _entityMgr;

    @Inject
    private NicDao _nicDao;

    @Inject
    private UserVmManager userVmManager;

    @Inject
    private UserVmService userVmService;

    // Created when the sweep is scheduled at start() and shut down in
    // stop(); null when the interval is configured to 0 (sweeping off).
    private ScheduledExecutorService credentialsCleanupExecutor;

    // A credential is 'pending' from the moment it is generated until the
    // instance confirms it configured the engine with it. The SSH path
    // verifies the login itself before returning, so it stores 'confirmed'
    // directly. 'failed' is written when an instance reports it could not
    // apply the credential.
    static final String STATUS_PENDING = "pending";
    static final String STATUS_CONFIRMED = "confirmed";

    // Alphanumeric only: the generated value travels through shell and SQL on
    // the instance, and quoting bugs there are worse than the entropy lost by
    // dropping symbols. 24 characters of [A-Za-z0-9] is ~143 bits.
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int PASSWORD_LENGTH = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

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
     * Base64 because that is what the user data API takes.
     */
    static String buildUserData(String dbName, String dbUsername, String dbPassword) {
        JsonObject request = new JsonObject();
        request.addProperty("db_name", dbName);
        request.addProperty("db_user", dbUsername);
        request.addProperty("db_password", dbPassword);

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

    /**
     * Runs one extension.py action against a VM and hands back the connection
     * details it reported. Both API commands go through here so the payload
     * shape and the failure handling stay in one place.
     */
    private JsonObject runExtensionAction(String actionName, Long vmId, JsonObject parameters) {
        VirtualMachine vm = _entityMgr.findById(VirtualMachine.class, vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("VM not found: " + vmId);
        }
        VirtualMachineTemplate template = _entityMgr.findById(VirtualMachineTemplate.class, vm.getTemplateId());
        String templateName = template != null ? template.getName() : null;

        // Build the exact payload shape extension.py already expects — do not
        // touch its parsing logic, it's been tested extensively already.
        JsonObject vmDetails = new JsonObject();
        vmDetails.addProperty("templatename", templateName);
        JsonObject externalDetails = new JsonObject();
        externalDetails.add("virtualmachine", vmDetails);

        JsonObject payload = new JsonObject();
        payload.addProperty("virtualmachineid", vm.getUuid());
        payload.add("externaldetails", externalDetails);
        payload.add("parameters", parameters);

        File payloadFile = null;
        try {
            payloadFile = File.createTempFile("dbaas-payload-", ".json");
            try (FileWriter w = new FileWriter(payloadFile)) {
                w.write(payload.toString());
            }

            int timeoutSeconds = DbaasProvisionTimeout.value();
            // Script(String, long, Logger) is deprecated in 4.22; the Duration
            // overload is the supported one.
            Script script = new Script("python3", Duration.standardSeconds(timeoutSeconds), logger);
            script.add(DbaasExtensionPath.value());
            script.add(actionName);
            script.add(payloadFile.getAbsolutePath());
            script.add(String.valueOf(timeoutSeconds));

            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String errorMsg = script.execute(parser);
            if (errorMsg != null) {
                throw new CloudRuntimeException("failed to invoke dbaas extension.py: " + errorMsg);
            }

            JsonObject result = JsonParser.parseString(parser.getLines()).getAsJsonObject();
            if (!"success".equals(result.get("status").getAsString())) {
                throw new CloudRuntimeException("dbaas provisioning reported failure: " + result);
            }
            return JsonParser.parseString(result.get("message").getAsString()).getAsJsonObject();
        } catch (IOException e) {
            throw new CloudRuntimeException("failed to write dbaas payload file", e);
        } finally {
            // The payload file briefly holds nothing secret going in, but
            // delete it regardless — no leftover temp files, ever.
            if (payloadFile != null) {
                payloadFile.delete();
            }
        }
    }

    @Override
    public boolean start() {
        ensureCredentialsTableExists();
        cleanupOrphanedCredentials();
        reportOrphanedDataDisks();
        scheduleCredentialsCleanup();
        return true;
    }

    @Override
    public boolean stop() {
        if (credentialsCleanupExecutor != null) {
            credentialsCleanupExecutor.shutdown();
        }
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
                reportOrphanedDataDisks();
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

    @Override
    public DbaasResponse createDatabase(CreateDatabaseCmd cmd) {
        if (PROVISION_MODE_CONFIG_DRIVE.equals(provisionModeForVm(cmd.getVirtualMachineId()))) {
            return createDatabaseViaConfigDrive(cmd);
        }
        return createDatabaseOverSsh(cmd);
    }

    // The engine entry for the template this instance was deployed from, or
    // null when config.json does not know it (the SSH path reports that as a
    // failure with the list of known engines, so this stays quiet).
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

    private String provisionModeForVm(Long vmId) {
        return provisionMode(engineConfigForVm(vmId));
    }

    /**
     * Config-drive provisioning: the management server writes the request onto
     * the instance's config drive and starts it, and the instance configures
     * its own engine at first boot. Nothing here connects to the instance, so
     * it works on networks the management server has no route to, and with the
     * virtual router down.
     * <p>
     * The credential is stored before the instance starts, so Show Password
     * has something to show immediately; it stays 'pending' until the instance
     * reports back that its engine really was configured.
     */
    private DbaasResponse createDatabaseViaConfigDrive(CreateDatabaseCmd cmd) {
        VirtualMachine vm = _entityMgr.findById(VirtualMachine.class, cmd.getVirtualMachineId());
        if (vm == null) {
            throw new InvalidParameterValueException("VM not found: " + cmd.getVirtualMachineId());
        }
        // User data is only picked up when the instance boots, so it has to be
        // attached while the instance is still stopped. The wizard deploys
        // with startvm=false for exactly this reason.
        if (vm.getState() != VirtualMachine.State.Stopped) {
            throw new InvalidParameterValueException("this engine provisions from its config drive, which is only"
                    + " read at boot: the instance must be stopped when the database is created, but it is "
                    + vm.getState() + ". Deploy with startvm=false, or use an instance that is stopped.");
        }
        VirtualMachineTemplate template = _entityMgr.findById(VirtualMachineTemplate.class, vm.getTemplateId());
        String engineName = template != null ? template.getName() : null;

        String dbUsername = cmd.getDbUsername();
        if (dbUsername == null || dbUsername.trim().isEmpty()) {
            dbUsername = cmd.getDbName();
        }
        String dbPassword = generatePassword();

        String userData = buildUserData(cmd.getDbName(), dbUsername, dbPassword);
        try {
            userVmManager.updateVirtualMachine(vm.getId(), null, null, null, null, null, null,
                    userData, null, null, null, BaseCmd.HTTPMethod.POST, null, null, null, null, null);
        } catch (Exception e) {
            throw new CloudRuntimeException("failed to attach the provisioning request to instance "
                    + vm.getUuid() + ": " + e.getMessage(), e);
        }

        // Stored before the start, not after: a start that fails leaves an
        // instance the tenant can start themselves, and the credential it will
        // provision with must already be recoverable when they do.
        storeCredential(vm.getUuid(), dbUsername, dbPassword, engineName, STATUS_PENDING);

        // Looked up as a UserVm rather than cast: the entity manager hands back
        // whatever VO backs the row, and a cast would only fail at runtime.
        UserVm userVm = _entityMgr.findById(UserVm.class, cmd.getVirtualMachineId());
        if (userVm == null) {
            throw new CloudRuntimeException("instance " + vm.getUuid()
                    + " carries the provisioning request but is not a user instance, so it cannot be started here");
        }
        try {
            userVmService.startVirtualMachine(userVm, null);
        } catch (Exception e) {
            throw new CloudRuntimeException("the provisioning request was attached to instance " + vm.getUuid()
                    + " but it could not be started: " + e.getMessage(), e);
        }

        DbaasResponse response = new DbaasResponse();
        response.setEngine(engineName);
        response.setHost(primaryIpAddress(vm));
        response.setPort(enginePort(engineConfigForVm(cmd.getVirtualMachineId())));
        response.setDatabase(cmd.getDbName());
        response.setUsername(dbUsername);
        response.setPassword(dbPassword);
        response.setObjectName("dbaas");
        return response;
    }

    private DbaasResponse createDatabaseOverSsh(CreateDatabaseCmd cmd) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("db_name", cmd.getDbName());
        // The username is optional: the form banner advertises that an empty
        // one defaults to the database name. Both share one identifier shape,
        // so the provisioning script's validation accepts either, and a
        // name-derived user makes a retry on the same VM fail loudly on the
        // duplicate user instead of stacking anonymous users.
        String dbUsername = cmd.getDbUsername();
        if (dbUsername == null || dbUsername.trim().isEmpty()) {
            dbUsername = cmd.getDbName();
        }
        parameters.addProperty("db_username", dbUsername);
        parameters.addProperty("reset_vm_password", Boolean.TRUE.equals(cmd.isResetVmPassword()));

        JsonObject details = runExtensionAction("create_database", cmd.getVirtualMachineId(), parameters);

        DbaasResponse response = new DbaasResponse();
        response.setEngine(details.get("engine").getAsString());
        response.setHost(details.get("host").getAsString());
        response.setPort(details.get("port").getAsInt());
        response.setDatabase(details.get("database").getAsString());
        response.setUsername(details.get("username").getAsString());
        response.setPassword(details.get("password").getAsString());
        // VM access is best-effort on the extension side: it only reports
        // vm_* fields when reset_vm_password was set and the template ships
        // vmaccess.sh; templates built before it existed report none.
        if (details.has("vm_username")) {
            response.setVmUsername(details.get("vm_username").getAsString());
        }
        if (details.has("vm_password")) {
            response.setVmPassword(details.get("vm_password").getAsString());
        }
        response.setObjectName("dbaas");

        // The instance login password is deliberately NOT stored: it is
        // delivered once, on the creation screen / notification, and the user
        // is expected to keep it. Only database credentials are recoverable.
        storeCredential(vmUuid(cmd.getVirtualMachineId()), response.getUsername(), response.getPassword(),
                response.getEngine());
        return response;
    }

    @Override
    public DbaasResponse resetDatabasePassword(ResetDatabasePasswordCmd cmd) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("db_username", cmd.getDbUsername());

        JsonObject details = runExtensionAction("reset_password", cmd.getVirtualMachineId(), parameters);

        // A reset does not name a database: the user keeps whatever it already
        // had access to, so that field stays unset rather than guessed at.
        DbaasResponse response = new DbaasResponse();
        response.setEngine(details.get("engine").getAsString());
        response.setHost(details.get("host").getAsString());
        response.setPort(details.get("port").getAsInt());
        response.setUsername(details.get("username").getAsString());
        response.setPassword(details.get("password").getAsString());
        response.setObjectName("dbaas");

        storeCredential(vmUuid(cmd.getVirtualMachineId()), response.getUsername(), response.getPassword(),
                response.getEngine());
        return response;
    }

    @Override
    public DbaasResponse getDatabasePassword(GetDatabasePasswordCmd cmd) {
        // findById + ACL already ran in getEntityOwnerId before execute() was
        // reached; this just resolves the UUID the table is keyed on.
        String vmId = vmUuid(cmd.getVirtualMachineId());

        String sql = "SELECT db_username, db_password_encrypted, engine FROM dbaas_credentials WHERE vm_id = ?"
                + (cmd.getDbUsername() != null ? " AND db_username = ?" : "")
                + " ORDER BY created_at DESC, id DESC LIMIT 1";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            PreparedStatement pstmt = txn.prepareStatement(sql);
            pstmt.setString(1, vmId);
            if (cmd.getDbUsername() != null) {
                pstmt.setString(2, cmd.getDbUsername());
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
    private void storeCredential(String vmId, String dbUsername, String dbPassword, String engine) {
        storeCredential(vmId, dbUsername, dbPassword, engine, STATUS_CONFIRMED);
    }

    private void storeCredential(String vmId, String dbUsername, String dbPassword, String engine, String status) {
        String sql = "INSERT INTO dbaas_credentials (vm_id, db_username, db_password_encrypted, engine, status)"
                + " VALUES (?, ?, ?, ?, ?)";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            PreparedStatement pstmt = txn.prepareStatement(sql);
            pstmt.setString(1, vmId);
            pstmt.setString(2, dbUsername);
            pstmt.setString(3, DBEncryptionUtil.encrypt(dbPassword));
            pstmt.setString(4, engine);
            pstmt.setString(5, status);
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

    // Provisioning transport for one engine, straight from config.json. An
    // entry without the key is an image built before the config-drive script
    // existed, which can only be provisioned over SSH -- so that, not the new
    // path, is the default. Never hardcode the mapping itself here.
    static final String PROVISION_MODE_SSH = "ssh";
    static final String PROVISION_MODE_CONFIG_DRIVE = "configdrive";

    private String provisionMode(JsonObject engineConfig) {
        if (engineConfig == null || !engineConfig.has("provision_mode")) {
            return PROVISION_MODE_SSH;
        }
        String mode = engineConfig.get("provision_mode").getAsString();
        if (PROVISION_MODE_CONFIG_DRIVE.equalsIgnoreCase(mode)) {
            return PROVISION_MODE_CONFIG_DRIVE;
        }
        if (!PROVISION_MODE_SSH.equalsIgnoreCase(mode)) {
            logger.warn("unknown provision_mode {} in config.json -- treating it as {}", mode, PROVISION_MODE_SSH);
        }
        return PROVISION_MODE_SSH;
    }

    @Override
    public List<DbaasEngineResponse> listEngines() {
        // A broken config.json must not take the whole API down: the UI's
        // engine picker and the Database section both call this, so a failure
        // here degrades to "no engines available" (logged loudly) instead of
        // erroring every page that touches the plugin.
        List<DbaasEngineResponse> result = new ArrayList<>();
        try {
            JsonObject engines = readEnginesConfig().getAsJsonObject("engines");
            for (Map.Entry<String, JsonElement> entry : engines.entrySet()) {
                JsonObject cfg = entry.getValue().getAsJsonObject();
                DbaasEngineResponse engine = new DbaasEngineResponse();
                engine.setTemplate(entry.getKey());
                engine.setPort(cfg.get("port").getAsInt());
                engine.setProvisionMode(provisionMode(cfg));
                engine.setObjectName("dbaasengine");
                result.add(engine);
            }
        } catch (Exception e) {
            logger.error("failed to read dbaas engines from config.json -- reporting no engines", e);
        }
        return result;
    }

    // config.json lives next to extension.py; the engines map inside it is the
    // single source of truth for which templates are DBaaS engines.
    private JsonObject readEnginesConfig() {
        File extensionFile = new File(DbaasExtensionPath.value());
        File configFile = new File(extensionFile.getParentFile(), "config.json");
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
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return DbaasManagerImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {DbaasExtensionPath, DbaasProvisionTimeout, DbaasCredentialsCleanupInterval};
    }
}
