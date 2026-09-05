package com.dbaas;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
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

import com.cloud.api.response.ApiResponseSerializer;

/**
 * Lets a config-drive instance report whether it configured its database
 * engine, without holding any CloudStack credential. Registered through
 * {@link DbaasManagerImpl#getAuthCommands()} as a {@link PluggableAPIAuthenticator}
 * command -- the same mechanism the SAML and OAuth login callbacks use to
 * bypass the normal signed-request requirement -- and authorized instead by
 * the one-time token minted into the instance's user data
 * (see DbaasManagerImpl#buildUserData). The token is single use: redeeming it
 * clears the stored hash, so a replayed report is rejected.
 * <p>
 * Every rejection (unknown instance, wrong token, expired token, already
 * redeemed) returns the same generic failure so the response cannot be used
 * to tell them apart.
 */
@APICommand(name = "reportDbaasProvisioningResult",
        description = "Reports whether a config-drive DBaaS instance configured its database engine."
                + " Called by the instance itself, authorized by a one-time token, not by an API key.",
        responseObject = SuccessResponse.class,
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false)
public class ReportProvisioningResultCmd extends BaseCmd implements APIAuthenticator {

    private static final String STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_FAILED = "failed";

    @Inject
    private DbaasManager dbaasManager;

    @Override
    public void execute() throws ServerApiException {
        // Never reached: APIAuthenticator.authenticate() below handles the
        // whole request instead of the normal dispatch path.
        throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR,
                "reportDbaasProvisioningResult must be handled by authenticate()");
    }

    @Override
    public String getCommandName() {
        return "reportdbaasprovisioningresultresponse";
    }

    @Override
    public long getEntityOwnerId() {
        // Never called: this command never reaches the normal ACL path.
        return 0;
    }

    @Override
    public String authenticate(String command, Map<String, Object[]> params, HttpSession session,
            InetAddress remoteAddress, String responseType, StringBuilder auditTrailSb,
            HttpServletRequest req, HttpServletResponse resp) throws ServerApiException {
        String vmUuid = param(params, "vmid");
        String token = param(params, "token");
        String status = param(params, "status");
        String message = param(params, "message");

        boolean validStatus = STATUS_CONFIRMED.equals(status) || STATUS_FAILED.equals(status);
        boolean accepted = validStatus && vmUuid != null && token != null
                && dbaasManager.applyProvisioningReport(vmUuid, token, status, message);

        auditTrailSb.append("command=").append(command).append(" accepted=").append(accepted);

        SuccessResponse response = new SuccessResponse(getCommandName());
        response.setSuccess(accepted);
        resp.setStatus(accepted ? HttpServletResponse.SC_OK : HttpServletResponse.SC_FORBIDDEN);
        return ApiResponseSerializer.toSerializedString(response, responseType);
    }

    private static String param(Map<String, Object[]> params, String name) {
        Object[] values = params.get(name);
        if (values == null || values.length == 0 || values[0] == null) {
            return null;
        }
        String value = String.valueOf(values[0]);
        return value.isEmpty() ? null : value;
    }

    @Override
    public APIAuthenticationType getAPIType() {
        // Not a login: no session is created, nothing about the caller's
        // identity is established. This is the same "unauthenticated,
        // non-login" type ListLoginDomainsCmd and the SAML metadata/IdP
        // listing commands use.
        return APIAuthenticationType.READONLY_API;
    }

    @Override
    public void setAuthenticators(List<PluggableAPIAuthenticator> authenticators) {
        // No delegation to other pluggable authenticators; this command is
        // fully self-contained.
    }
}
