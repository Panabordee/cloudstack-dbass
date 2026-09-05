package com.dbaas;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    private static final Logger S_LOGGER = LogManager.getLogger(ReportProvisioningResultCmd.class);

    // The manager is NOT injected: APIAuthenticationManagerImpl constructs
    // this command with newInstance() and runs ComponentContext.inject() on
    // it from a context that cannot resolve com.dbaas.DbaasManager, which
    // throws and leaves the caller with an empty 200. DbaasManagerImpl
    // publishes itself into a static holder at start() instead; read it from
    // there and answer with a real error when the plugin is genuinely down.
    private static final String STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_FAILED = "failed";

    // Fixed-window per-IP counter for the unauthenticated endpoint: the
    // 256-bit token makes guessing infeasible, but nothing about the endpoint
    // itself stops request flooding. Window start (millis) and hit count are
    // kept per source IP; entries age out of a pruned map, so a spoofed-address
    // flood costs memory only up to the cap. 0/negative limit disables it.
    private static final ConcurrentHashMap<String, long[]> REPORT_WINDOWS = new ConcurrentHashMap<>();
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_IPS = 4096;

    static boolean rateLimited(InetAddress remoteAddress, int limitPerMinute) {
        if (limitPerMinute <= 0 || remoteAddress == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (REPORT_WINDOWS.size() > MAX_TRACKED_IPS) {
            REPORT_WINDOWS.entrySet().removeIf(entry -> now - entry.getValue()[0] >= WINDOW_MILLIS);
        }
        long[] window = REPORT_WINDOWS.computeIfAbsent(remoteAddress.getHostAddress(), k -> new long[] {now, 0});
        synchronized (window) {
            if (now - window[0] >= WINDOW_MILLIS) {
                window[0] = now;
                window[1] = 0;
            }
            if (window[1] >= limitPerMinute) {
                return true;
            }
            window[1]++;
            return false;
        }
    }

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
        // The limit is checked before anything else: it exists precisely for
        // the caller who sends garbage at volume and never reaches the token
        // check. A limited caller gets 429 and nothing else.
        int limitPerMinute = DbaasManagerImpl.DbaasReportRateLimit.value();
        if (rateLimited(remoteAddress, limitPerMinute)) {
            logger.warn("reportDbaasProvisioningResult rate limited for {} (>{} calls/minute)",
                    remoteAddress, limitPerMinute);
            sendErrorQuietly(resp, 429, "report rate limited");
            return "";
        }

        String vmUuid = param(params, "vmid");
        String token = param(params, "token");
        String status = param(params, "status");
        String message = param(params, "message");

        DbaasManager manager = DbaasManagerImpl.getRunningManager();
        if (manager == null) {
            logger.error("reportDbaasProvisioningResult: the DBaaS manager is not running --"
                    + " the report is NOT processed and the token is not consumed");
            sendErrorQuietly(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "DBaaS manager not running");
            return "";
        }

        boolean validStatus = STATUS_CONFIRMED.equals(status) || STATUS_FAILED.equals(status);
        boolean accepted = validStatus && vmUuid != null && token != null
                && manager.applyProvisioningReport(vmUuid, token, status, message);

        auditTrailSb.append("command=").append(command).append(" accepted=").append(accepted);

        if (!accepted) {
            // Generic on the wire: every rejection reason looks identical.
            // sendError commits the response, so the servlet's own write
            // afterwards is a no-op (its IllegalStateException is swallowed) --
            // this is how the 403 survives instead of being reset to 200.
            logger.warn("reportDbaasProvisioningResult rejected for VM {}: invalid request or no matching"
                    + " pending token", vmUuid);
            sendErrorQuietly(resp, HttpServletResponse.SC_FORBIDDEN, "provisioning report rejected");
            return "";
        }

        return serialize(resp, HttpServletResponse.SC_OK, true, responseType);
    }

    // sendError throws IOException (checked): swallow it here -- a failure to
    // write an error page must not mask the status code that was already set.
    private static void sendErrorQuietly(HttpServletResponse resp, int status, String message) {
        try {
            resp.sendError(status, message);
        } catch (IOException e) {
            S_LOGGER.warn("could not write the {} error response: {}", status, e.getMessage());
        }
    }

    private static String serialize(HttpServletResponse resp, int status, boolean success, String responseType) {
        resp.setStatus(status);
        SuccessResponse response = new SuccessResponse("reportdbaasprovisioningresultresponse");
        response.setSuccess(success);
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
