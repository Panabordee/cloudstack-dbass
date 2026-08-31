package com.dbaas;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.joda.time.Duration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.db.EntityManager;
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
    public DbaasResponse createDatabase(CreateDatabaseCmd cmd) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("db_name", cmd.getDbName());
        parameters.addProperty("db_username", cmd.getDbUsername());

        JsonObject details = runExtensionAction("create_database", cmd.getVirtualMachineId(), parameters);

        DbaasResponse response = new DbaasResponse();
        response.setEngine(details.get("engine").getAsString());
        response.setHost(details.get("host").getAsString());
        response.setPort(details.get("port").getAsInt());
        response.setDatabase(details.get("database").getAsString());
        response.setUsername(details.get("username").getAsString());
        response.setPassword(details.get("password").getAsString());
        response.setObjectName("dbaas");
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
        return response;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(CreateDatabaseCmd.class);
        cmdList.add(ResetDatabasePasswordCmd.class);
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
