package com.dbaas;

import java.net.InetAddress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.auth.APIAuthenticationType;
import org.apache.cloudstack.api.auth.APIAuthenticator;
import org.apache.cloudstack.api.auth.PluggableAPIAuthenticator;
import org.apache.cloudstack.api.response.SuccessResponse;


// The agent's long-poll: holds the request up to dbaas.agent.longpoll.seconds
// and answers the moment a job is queued. Registered through
// DbaasManagerImpl.getAuthCommands() exactly like the provisioning report.
// The manager comes from the static holder -- an injected field here would
// repeat the UnsatisfiedDependencyException of FIX-1.
@APICommand(name = "getDbaasAgentJob",
        description = "Long-polls for one DBaaS console job; marks it dispatched exactly once.",
        responseObject = SuccessResponse.class,
        responseHasSensitiveInfo = false)
public class GetDbaasAgentJobCmd extends BaseCmd implements APIAuthenticator {

    private static final Logger S_LOGGER = LogManager.getLogger(GetDbaasAgentJobCmd.class);

    private static final String s_name = "getdbaasagentjobresponse";

    @Override
    public void execute() throws ServerApiException {
        throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR,
                "getDbaasAgentJob must be handled by authenticate()");
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String authenticate(String command, Map<String, Object[]> params, HttpSession session,
            InetAddress remoteAddress, String responseType, StringBuilder auditTrailSb,
            HttpServletRequest req, HttpServletResponse resp) throws ServerApiException {
        int limitPerMinute = DbaasManagerImpl.DbaasReportRateLimit.value();
        if (ReportProvisioningResultCmd.rateLimited(remoteAddress, limitPerMinute)) {
            S_LOGGER.warn("getDbaasAgentJob rate limited for {} (>{} calls/minute)", remoteAddress, limitPerMinute);
            sendErrorQuietly(resp, 429, "agent rate limited");
            return "";
        }

        DbaasManager manager = DbaasManagerImpl.getRunningManager();
        if (manager == null) {
            S_LOGGER.error("getDbaasAgentJob: the DBaaS manager is not running");
            sendErrorQuietly(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "DBaaS manager not running");
            return "";
        }

        String vmUuid = ReportProvisioningResultCmd.param(params, "vmid");
        String token = ReportProvisioningResultCmd.param(params, "token");
        if (vmUuid == null || token == null || !manager.isAgentTokenValid(vmUuid, token)) {
            S_LOGGER.warn("getDbaasAgentJob rejected for VM {}: invalid or rotated agent token", vmUuid);
            sendErrorQuietly(resp, HttpServletResponse.SC_FORBIDDEN, "invalid agent token");
            return "";
        }

        int longPollSeconds = Math.min(DbaasManagerImpl.DbaasAgentLongPollSeconds.value(), 30);
        // The hold parks a Jetty worker for its whole duration: cap how many
        // agents may wait at once so 20 instances cannot occupy 20 threads of
        // the API pool indefinitely.
        if (DbaasManagerImpl.tryAcquireLongPollSlot()) {
            try {
                String jobJson = manager.agentPollJob(vmUuid, longPollSeconds);
                auditTrailSb.append("command=").append(command)
                        .append(" job=").append(jobJson.isEmpty() ? "none" : "delivered");
                return jobJson;   // "" = the hold expired with nothing to do
            } finally {
                DbaasManagerImpl.releaseLongPollSlot();
            }
        }
        S_LOGGER.warn("getDbaasAgentJob waiter ceiling reached -- VM {} answered 503", vmUuid);
        sendErrorQuietly(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "too many waiting agents");
        return "";
    }


    private static void sendErrorQuietly(HttpServletResponse resp, int status, String message) {
        try {
            resp.sendError(status, message);
        } catch (java.io.IOException e) {
            S_LOGGER.warn("could not write the {} error response: {}", status, e.getMessage());
        }
    }

    @Override
    public APIAuthenticationType getAPIType() {
        return APIAuthenticationType.READONLY_API;
    }

    @Override
    public void setAuthenticators(List<PluggableAPIAuthenticator> authenticators) {
    }
}
