package com.dbaas;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.joda.time.Duration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import com.cloud.vm.VirtualMachine;

public class DbaasManagerImpl extends ManagerBase implements DbaasManager, PluggableService, Configurable {

    public static final ConfigKey<String> DbaasExtensionPath = new ConfigKey<>(
            "Advanced", String.class, "dbaas.extension.path",
            "/usr/share/cloudstack-management/extensions/dbaas/extension.py",
            "Filesystem path to the DBaaS extension.py entrypoint.", true);

    public static final ConfigKey<Integer> DbaasProvisionTimeout = new ConfigKey<>(
            "Advanced", Integer.class, "dbaas.provision.timeout", "120",
            "Timeout in seconds passed through to extension.py for provisioning.", true);

    @Inject
    private EntityManager _entityMgr;

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
        return true;
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
            ensureVmCredentialColumns(txn);
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

    // CREATE TABLE IF NOT EXISTS cannot extend a table created before the
    // vm_username / vm_password_encrypted columns existed; add them when an
    // upgrade finds a pre-vmaccess table.
    private void ensureVmCredentialColumns(TransactionLegacy txn) throws SQLException {
        String check = "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = 'dbaas_credentials' AND COLUMN_NAME = 'vm_username'";
        try (PreparedStatement pstmt = txn.prepareStatement(check); ResultSet rs = pstmt.executeQuery()) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }
        try (PreparedStatement pstmt = txn.prepareStatement("ALTER TABLE `dbaas_credentials`"
                + " ADD COLUMN `vm_username` varchar(255) NULL,"
                + " ADD COLUMN `vm_password_encrypted` varchar(512) NULL")) {
            pstmt.executeUpdate();
            logger.info("added vm_username / vm_password_encrypted columns to dbaas_credentials");
        }
    }

    @Override
    public DbaasResponse createDatabase(CreateDatabaseCmd cmd) {
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

        // A create without resetvmpassword never touched the OS password, so
        // pass nulls: storeCredential inherits the previous row's VM
        // credentials, keeping them recoverable through Show Password.
        storeCredential(vmUuid(cmd.getVirtualMachineId()), response.getUsername(), response.getPassword(),
                response.getEngine(), response.getVmUsername(), response.getVmPassword());
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
                response.getEngine(), null, null);
        return response;
    }

    @Override
    public DbaasResponse getDatabasePassword(GetDatabasePasswordCmd cmd) {
        // findById + ACL already ran in getEntityOwnerId before execute() was
        // reached; this just resolves the UUID the table is keyed on.
        String vmId = vmUuid(cmd.getVirtualMachineId());

        String sql = "SELECT db_username, db_password_encrypted, vm_username, vm_password_encrypted, engine FROM dbaas_credentials WHERE vm_id = ?"
                + (cmd.getDbUsername() != null ? " AND db_username = ?" : "")
                + " ORDER BY created_at DESC, id DESC LIMIT 1";
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            PreparedStatement pstmt = txn.prepareStatement(sql);
            pstmt.setString(1, vmId);
            if (cmd.getDbUsername() != null) {
                pstmt.setString(2, cmd.getDbUsername());
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    throw new InvalidParameterValueException("No stored database credential found for this VM"
                            + (cmd.getDbUsername() != null ? " and username " + cmd.getDbUsername() : ""));
                }
                DbaasResponse response = new DbaasResponse();
                response.setUsername(rs.getString("db_username"));
                response.setPassword(DBEncryptionUtil.decrypt(rs.getString("db_password_encrypted")));
                response.setEngine(rs.getString("engine"));
                // VM credentials ride along when the instance's login password
                // was ever set; a pre-vmaccess row has NULLs and the response
                // simply omits the fields.
                String vmUsername = rs.getString("vm_username");
                String vmPasswordEncrypted = rs.getString("vm_password_encrypted");
                if (vmUsername != null && vmPasswordEncrypted != null) {
                    response.setVmUsername(vmUsername);
                    response.setVmPassword(DBEncryptionUtil.decrypt(vmPasswordEncrypted));
                }
                response.setObjectName("dbaas");
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
    // Show Password always reflects whatever the tenant's credential
    // actually is right now -- not just what it was the first time.
    // create_database/reset_password both call this on every success, so
    // Show Password always reflects whatever the tenant's credential
    // actually is right now -- not just what it was the first time.
    // vmUsername/vmPassword are null when the call never touched the OS
    // password: the previous row's values are then copied forward, so Show
    // Password keeps returning the tenant's actual login credentials instead
    // of losing them to a newer database-only row.
    private void storeCredential(String vmId, String dbUsername, String dbPassword, String engine,
            String vmUsername, String vmPassword) {
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            if (vmUsername == null || vmPassword == null) {
                String previous = "SELECT vm_username, vm_password_encrypted FROM dbaas_credentials"
                        + " WHERE vm_id = ? AND vm_username IS NOT NULL AND vm_password_encrypted IS NOT NULL"
                        + " ORDER BY created_at DESC, id DESC LIMIT 1";
                try (PreparedStatement pstmt = txn.prepareStatement(previous)) {
                    pstmt.setString(1, vmId);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            vmUsername = rs.getString("vm_username");
                            // Re-encrypting would round-trip needlessly; the
                            // ciphertext is reusable as-is under the same key.
                            vmPassword = rs.getString("vm_password_encrypted");
                        }
                    }
                }
            }
            String sql = "INSERT INTO dbaas_credentials (vm_id, db_username, db_password_encrypted, engine,"
                    + " vm_username, vm_password_encrypted) VALUES (?, ?, ?, ?, ?, ?)";
            PreparedStatement pstmt = txn.prepareStatement(sql);
            pstmt.setString(1, vmId);
            pstmt.setString(2, dbUsername);
            pstmt.setString(3, DBEncryptionUtil.encrypt(dbPassword));
            pstmt.setString(4, engine);
            if (vmUsername != null && vmPassword != null) {
                pstmt.setString(5, vmUsername);
                pstmt.setString(6, DBEncryptionUtil.encrypt(vmPassword));
            } else {
                pstmt.setNull(5, java.sql.Types.VARCHAR);
                pstmt.setNull(6, java.sql.Types.VARCHAR);
            }
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

    @Override
    public List<DbaasEngineResponse> listEngines() {
        JsonObject engines = readEnginesConfig().getAsJsonObject("engines");
        List<DbaasEngineResponse> result = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : engines.entrySet()) {
            JsonObject cfg = entry.getValue().getAsJsonObject();
            DbaasEngineResponse engine = new DbaasEngineResponse();
            engine.setTemplate(entry.getKey());
            engine.setScript(cfg.get("script").getAsString());
            if (cfg.has("reset_script")) {
                engine.setResetScript(cfg.get("reset_script").getAsString());
            }
            engine.setPort(cfg.get("port").getAsInt());
            engine.setObjectName("dbaasengine");
            result.add(engine);
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

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(CreateDatabaseCmd.class);
        cmdList.add(ResetDatabasePasswordCmd.class);
        cmdList.add(GetDatabasePasswordCmd.class);
        cmdList.add(ListDbaasEnginesCmd.class);
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return DbaasManagerImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {DbaasExtensionPath, DbaasProvisionTimeout};
    }
}
