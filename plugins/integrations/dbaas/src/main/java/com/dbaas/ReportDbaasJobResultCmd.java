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
import com.cloud.api.response.ApiResponseSerializer;
import org.apache.cloudstack.api.auth.PluggableAPIAuthenticator;
import org.apache.cloudstack.api.response.SuccessResponse;

// The agent reports a finished console job. Same authentication pattern as
// the provisioning report: one long-lived (and rotated) agent token per
// instance, generic 403 on every rejection, and the manager resolved through
// the static holder rather than injection.
@APICommand(name = "reportDbaasJobResult",
        description = "Reports the result of a DBaaS console job.",
        responseObject = SuccessResponse.class,
        responseHasSensitiveInfo = false)
public class ReportDbaasJobResultCmd extends BaseCmd implements APIAuthenticator {

    private static final Logger S_LOGGER = LogManager.getLogger(ReportDbaasJobResultCmd.class);

    private static final String s_name = "reportdbaasjobresultresponse";

    @Override
    public void execute() throws ServerApiException {
        throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR,
                "reportDbaasJobResult must be handled by authenticate()");
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
        DbaasManager manager = DbaasManagerImpl.getRunningManager();
        if (manager == null) {
            S_LOGGER.error("reportDbaasJobResult: the DBaaS manager is not running");
            sendErrorQuietly(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "DBaaS manager not running");
            return "";
        }

        String vmUuid = ReportProvisioningResultCmd.param(params, "vmid");
        String token = ReportProvisioningResultCmd.param(params, "token");
        String jobUuid = ReportProvisioningResultCmd.param(params, "jobid");
        String status = ReportProvisioningResultCmd.param(params, "status");
        String result = ReportProvisioningResultCmd.param(params, "result");
        String error = ReportProvisioningResultCmd.param(params, "error");
        int rowCount = -1;
        String rowRaw = ReportProvisioningResultCmd.param(params, "rowcount");
        if (rowRaw != null && rowRaw.matches("[0-9]+")) {
            rowCount = Integer.parseInt(rowRaw);
        }
        boolean truncated = "true".equalsIgnoreCase(ReportProvisioningResultCmd.param(params, "truncated"));

        boolean ok = vmUuid != null && token != null && jobUuid != null
                && ("confirmed".equals(status) || "failed".equals(status))
                && manager.agentReportResult(vmUuid, token, jobUuid, status, rowCount, truncated, result, error);

        auditTrailSb.append("command=").append(command).append(" accepted=").append(ok);

        if (!ok) {
            S_LOGGER.warn("reportDbaasJobResult rejected for VM {} job {}: no matching dispatched job",
                    vmUuid, jobUuid);
            sendErrorQuietly(resp, HttpServletResponse.SC_FORBIDDEN, "provisioning job report rejected");
            return "";
        }
        S_LOGGER.info("console job result recorded for VM {} job {}", vmUuid, jobUuid);
        return serialize(resp, HttpServletResponse.SC_OK, true, responseType);
    }

    private static void sendErrorQuietly(HttpServletResponse resp, int status, String message) {
        try {
            resp.sendError(status, message);
        } catch (java.io.IOException e) {
            S_LOGGER.warn("could not write the {} error response: {}", status, e.getMessage());
        }
    }

    private static String serialize(HttpServletResponse resp, int status, boolean success, String responseType) {
        resp.setStatus(status);
        SuccessResponse response = new SuccessResponse("reportdbaasjobresultresponse");
        response.setSuccess(success);
        return ApiResponseSerializer.toSerializedString(response, responseType);
    }

    @Override
    public APIAuthenticationType getAPIType() {
        return APIAuthenticationType.READONLY_API;
    }

    @Override
    public void setAuthenticators(List<PluggableAPIAuthenticator> authenticators) {
    }
}
