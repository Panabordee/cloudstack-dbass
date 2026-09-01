// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.reverseproxy;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.command.admin.reverseproxy.AddReverseProxyDomainCmd;
import org.apache.cloudstack.api.command.admin.reverseproxy.DeleteReverseProxyDomainCmd;
import org.apache.cloudstack.api.command.admin.reverseproxy.ListReverseProxyHostsCmd;
import org.apache.cloudstack.api.command.admin.reverseproxy.UpdateReverseProxyDomainCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.AddInstanceProxyCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.CheckInstanceProxyNameCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.DeleteInstanceProxyCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.ListInstanceProxiesCmd;
import org.apache.cloudstack.api.command.user.reverseproxy.ListReverseProxyDomainsCmd;
import org.apache.cloudstack.api.response.CheckInstanceProxyNameResponse;
import org.apache.cloudstack.api.response.InstanceProxyResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ReverseProxyDomainResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.reverseproxy.client.NpmCertificate;
import org.apache.cloudstack.reverseproxy.client.NpmClient;
import org.apache.cloudstack.reverseproxy.client.NpmProxyHost;
import org.apache.cloudstack.reverseproxy.dao.ReverseProxyDomainDao;
import org.apache.cloudstack.reverseproxy.dao.ReverseProxyDomainMapDao;
import org.apache.cloudstack.reverseproxy.dao.ReverseProxyHostDao;
import org.apache.commons.lang3.StringUtils;

import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.projects.Project;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ComponentLifecycleBase;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateListener;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.Nic;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;

public class ReverseProxyManagerImpl extends ComponentLifecycleBase implements ReverseProxyService, Configurable {

    /**
     * A proxy name must be a single valid DNS label (lowercase letters, digits and hyphens,
     * starting and ending with an alphanumeric character).
     */
    private static final Pattern PROXY_NAME_PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    /**
     * A domain suffix must consist of at least two valid DNS labels
     */
    private static final Pattern DOMAIN_NAME_PATTERN =
            Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");

    private static final String HTTP = "http";
    private static final String HTTPS = "https";

    @Inject
    private ReverseProxyHostDao reverseProxyHostDao;

    @Inject
    private ReverseProxyDomainDao reverseProxyDomainDao;

    @Inject
    private ReverseProxyDomainMapDao reverseProxyDomainMapDao;

    @Inject
    private UserVmDao userVmDao;

    @Inject
    private NetworkDao networkDao;

    @Inject
    private NetworkModel networkModel;

    @Inject
    private AccountManager accountManager;

    @Inject
    private AccountDao accountDao;

    @Inject
    private DomainDao domainDao;

    private volatile NpmClient npmClient;
    private volatile String npmClientSignature;

    ///////////////////////////////////////////////////////////
    //////////////////// Lifecycle methods ////////////////////
    ///////////////////////////////////////////////////////////

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        // Register a VM state listener so that proxy hosts are cleaned up when a VM is expunged
        VirtualMachine.State.getStateMachine().registerListener(new VmExpungeStateListener());
        logger.debug("Reverse Proxy integration registered the VM expunge cleanup listener");
        return true;
    }

    ///////////////////////////////////////////////////////////
    //////////////////// Service methods //////////////////////
    ///////////////////////////////////////////////////////////

    @Override
    public boolean isEnabled() {
        return ReverseProxyEnabled.value() != null && ReverseProxyEnabled.value();
    }

    /**
     * Checks whether the integration is enabled and fully configured
     */
    protected boolean isConfigured() {
        if (!isEnabled()) {
            return false;
        }
        return StringUtils.isNoneBlank(ReverseProxyDomain.value(), ReverseProxyNpmUrl.value(), ReverseProxyNpmUser.value(),
                ReverseProxyNpmPassword.value());
    }

    protected void validateConfiguration() {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        if (StringUtils.isBlank(ReverseProxyDomain.value())) {
            throw new CloudRuntimeException("The reverse proxy integration is not fully configured, please set 'reverseproxy.domain'");
        }
        if (StringUtils.isBlank(ReverseProxyNpmUrl.value())) {
            throw new CloudRuntimeException("The reverse proxy integration is not fully configured, please set 'reverseproxy.npm.url'");
        }
        if (StringUtils.isBlank(ReverseProxyNpmUser.value()) || StringUtils.isBlank(ReverseProxyNpmPassword.value())) {
            throw new CloudRuntimeException("The reverse proxy integration is not fully configured, please set "
                    + "'reverseproxy.npm.user' and 'reverseproxy.npm.password'");
        }
    }

    protected NpmClient getClient() {
        final String signature = String.format("%s|%s|%s|%s|%s", ReverseProxyNpmUrl.value(), ReverseProxyNpmUser.value(),
                ReverseProxyNpmPassword.value(), ReverseProxyNpmValidateSsl.value(), ReverseProxyNpmRequestTimeout.value());
        final NpmClient client = npmClient;
        if (client != null && signature.equals(npmClientSignature)) {
            return client;
        }
        synchronized (this) {
            if (npmClient == null || !signature.equals(npmClientSignature)) {
                try {
                    npmClient = new NpmClient(ReverseProxyNpmUrl.value(), ReverseProxyNpmUser.value(), ReverseProxyNpmPassword.value(),
                            ReverseProxyNpmValidateSsl.value(), ReverseProxyNpmRequestTimeout.value());
                    npmClientSignature = signature;
                } catch (final KeyManagementException | KeyStoreException | NoSuchAlgorithmException e) {
                    throw new CloudRuntimeException("Failed to create the Nginx Proxy Manager API client", e);
                }
            }
            return npmClient;
        }
    }

    protected String getWildcardDomain(final ReverseProxyDomainVO domain) {
        return "*." + domain.getDomain();
    }    ///////////////////////////////////////////////////////////
    /////////////////// Domain suffix methods /////////////////
    ///////////////////////////////////////////////////////////

    protected boolean isAdmin(final Account account) {
        return account != null && account.getType() == Account.Type.ADMIN;
    }

    /**
     * Resolves the domain suffix to use for a new proxy host: when no domain id is given exactly one
     * domain suffix must be configured, otherwise the given domain suffix is validated
     */
    protected ReverseProxyDomainVO resolveProxyDomain(final Long domainId, final Account caller, final UserVmVO vm) {
        final List<ReverseProxyDomainVO> domains = reverseProxyDomainDao.listAll();
        if (domains.isEmpty()) {
            throw new CloudRuntimeException("No reverse proxy domain suffix is configured, please ask your administrator "
                    + "to add a domain suffix for the reverse proxy integration");
        }
        final ReverseProxyDomainVO domain;
        if (domainId == null) {
            if (domains.size() > 1) {
                throw new InvalidParameterValueException("Multiple reverse proxy domain suffixes are configured, "
                        + "please specify the domain suffix to expose the instance on");
            }
            domain = domains.get(0);
        } else {
            domain = reverseProxyDomainDao.findById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException(String.format("Unable to find reverse proxy domain suffix with id %s", domainId));
            }
        }
        if (!canUseDomain(caller, vm, domain)) {
            throw new InvalidParameterValueException(String.format("The domain suffix '%s' is not available for this instance, "
                    + "please ask your administrator for access", domain.getDomain()));
        }
        return domain;
    }

    /**
     * Checks whether the caller may use the given domain suffix: admins can use all suffixes, users need the
     * suffix to be public, granted to their account or granted to one of the shared networks of the instance
     */
    protected boolean canUseDomain(final Account caller, final UserVmVO vm, final ReverseProxyDomainVO domain) {
        if (caller == null) {
            return false;
        }
        if (isAdmin(caller)) {
            return true;
        }
        if (domain.isPublic()) {
            return true;
        }
        if (reverseProxyDomainMapDao.findByDomainAndAccount(domain.getId(), caller.getAccountId()) != null) {
            return true;
        }
        if (vm != null) {
            final List<? extends Nic> nics = networkModel.getNics(vm.getId());
            for (final Nic nic : nics) {
                if (nic.getNetworkId() > 0 && reverseProxyDomainMapDao.findByDomainAndNetwork(domain.getId(), nic.getNetworkId()) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Lists the domain suffixes the given account may use: admins get all suffixes, users get the public
     * suffixes, the suffixes granted to their account and (when a VM is given) the suffixes granted to the
     * shared networks of the VM
     */
    protected List<ReverseProxyDomainVO> listAllowedDomains(final Account caller, final UserVmVO vm) {
        final List<ReverseProxyDomainVO> domains = reverseProxyDomainDao.listAll();
        if (caller != null && isAdmin(caller)) {
            return domains;
        }
        final List<ReverseProxyDomainVO> allowed = new ArrayList<>();
        for (final ReverseProxyDomainVO domain : domains) {
            if (canUseDomain(caller, vm, domain)) {
                allowed.add(domain);
            }
        }
        return allowed;
    }

    ///////////////////////////////////////////////////////////
    //////////////////// Business methods /////////////////////
    ///////////////////////////////////////////////////////////

    /**
     * Validates the user provided proxy name and returns it in normalized (lowercase) form
     */
    protected String validateProxyName(final String name) {
        if (StringUtils.isBlank(name)) {
            throw new InvalidParameterValueException("A proxy name is required");
        }
        final String trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (!PROXY_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidParameterValueException(String.format("The name '%s' is not a valid proxy name, it must be a single "
                    + "DNS label consisting of lowercase letters, digits and hyphens and must not start or end with a hyphen", name));
        }
        final String reserved = ReverseProxyReservedNames.value();
        if (StringUtils.isNotBlank(reserved)) {
            for (final String reservedName : reserved.split(",")) {
                if (trimmed.equalsIgnoreCase(reservedName.trim())) {
                    throw new InvalidParameterValueException(String.format("The name '%s' is reserved", trimmed));
                }
            }
        }
        return trimmed;
    }

    /**
     * Validates the user provided protocol (the scheme used to forward requests to the instance)
     */
    protected String validateProtocol(final String protocol) {
        if (StringUtils.isBlank(protocol)) {
            throw new InvalidParameterValueException("A protocol is required, must be http or https");
        }
        final String trimmed = protocol.trim().toLowerCase(Locale.ROOT);
        if (!HTTP.equals(trimmed) && !HTTPS.equals(trimmed)) {
            throw new InvalidParameterValueException(String.format("The protocol '%s' is not supported, must be http or https", protocol));
        }
        return trimmed;
    }

    /**
     * Resolves the shared network that the instance must be proxied on
     */
    protected NetworkVO resolveTargetNetwork(final UserVmVO vm) {
        final String configuredNetworkUuid = ReverseProxyNetworkId.value();
        if (StringUtils.isNotBlank(configuredNetworkUuid)) {
            final NetworkVO network = networkDao.findByUuid(configuredNetworkUuid.trim());
            if (network == null) {
                throw new CloudRuntimeException(String.format(
                        "The configured reverse proxy network (reverseproxy.network.id='%s') does not exist, please ask your "
                                + "administrator to fix the integration settings", configuredNetworkUuid));
            }
            if (network.getGuestType() != Network.GuestType.Shared) {
                throw new CloudRuntimeException(String.format(
                        "The configured reverse proxy network '%s' is not a shared network, please ask your administrator to fix "
                                + "the integration settings", network.getName()));
            }
            return network;
        }
        // Fall back to the default NIC of the instance when it is on a shared network
        final Nic defaultNic = networkModel.getDefaultNic(vm.getId());
        if (defaultNic != null) {
            final NetworkVO network = networkDao.findById(defaultNic.getNetworkId());
            if (network != null && network.getGuestType() == Network.GuestType.Shared) {
                return network;
            }
        }
        throw new InvalidParameterValueException(String.format(
                "The instance '%s' is not on a shared network that can be proxied. Please attach it to the shared network "
                        + "configured for the reverse proxy integration", vm.getHostName()));
    }

    /**
     * Resolves the NPM certificate to terminate TLS with for the given domain suffix: the certificate
     * configured on the domain suffix takes precedence, otherwise the certificate covering the wildcard
     * domain is auto-discovered.
     */
    protected long resolveCertificateId(final NpmClient client, final ReverseProxyDomainVO domain) {
        if (domain.getNpmCertificateId() != null && domain.getNpmCertificateId() > 0) {
            return domain.getNpmCertificateId();
        }
        final NpmCertificate certificate = client.findCertificateForWildcardDomain(getWildcardDomain(domain));
        if (certificate == null || certificate.getId() == null) {
            throw new CloudRuntimeException(String.format(
                    "No Nginx Proxy Manager certificate covering '%s' was found. Please provision the wildcard certificate in "
                            + "Nginx Proxy Manager or set a certificate id on the reverse proxy domain suffix '%s'",
                    getWildcardDomain(domain), domain.getDomain()));
        }
        return certificate.getId();
    }

    @Override
    public ReverseProxyHost createInstanceProxy(final Long vmId, final String name, final String protocol, final Integer port, final Long domainId) {
        validateConfiguration();

        final Account caller = CallContext.current().getCallingAccount();
        final String validatedName = validateProxyName(name);
        final String validatedProtocol = validateProtocol(protocol);
        if (port == null || port < 1 || port > 65535) {
            throw new InvalidParameterValueException("The port must be between 1 and 65535");
        }

        final UserVmVO vm = userVmDao.findById(vmId);
        if (vm == null || vm.getRemoved() != null) {
            throw new InvalidParameterValueException(String.format("Unable to find instance with id %s", vmId));
        }
        accountManager.checkAccess(caller, SecurityChecker.AccessType.OperateEntry, false, vm);

        final ReverseProxyDomainVO domain = resolveProxyDomain(domainId, caller, vm);
        final NetworkVO network = resolveTargetNetwork(vm);
        final Nic nic = networkModel.getNicInNetwork(vmId, network.getId());
        if (nic == null || StringUtils.isBlank(nic.getIPv4Address())) {
            throw new InvalidParameterValueException(String.format(
                    "The instance '%s' does not have an IPv4 address on the network '%s', it cannot be proxied",
                    vm.getHostName(), network.getName()));
        }
        final String ipAddress = nic.getIPv4Address();

        final String fqdn = String.format("%s.%s", validatedName, domain.getDomain());

        // Name availability checks (local mapping table and Nginx Proxy Manager)
        if (reverseProxyHostDao.findByFqdn(fqdn) != null) {
            throw new InvalidParameterValueException(String.format("The name '%s' is already in use, please choose another name", validatedName));
        }
        final NpmClient client = getClient();
        if (client.findProxyHostByDomain(fqdn) != null) {
            throw new InvalidParameterValueException(String.format("The domain '%s' is already in use, please choose another name", fqdn));
        }

        final long certificateId = resolveCertificateId(client, domain);

        final NpmProxyHost request = new NpmProxyHost();
        request.setDomainNames(Arrays.asList(fqdn));
        request.setForwardScheme(validatedProtocol);
        request.setForwardHost(ipAddress);
        request.setForwardPort(port);
        request.setCertificateId((int) certificateId);
        request.setSslForced(ReverseProxyForceHttps.value());
        request.setEnabled(true);

        logger.debug(String.format("Creating proxy host on the Nginx Proxy Manager: %s forwarding to %s:%d (%s)", fqdn, ipAddress, port, validatedProtocol));
        final NpmProxyHost created = client.createProxyHost(request);
        if (created == null || created.getId() == null) {
            throw new CloudRuntimeException("The Nginx Proxy Manager did not return the created proxy host");
        }

        final ReverseProxyHostVO proxy = new ReverseProxyHostVO();
        proxy.setName(validatedName);
        proxy.setFqdn(fqdn);
        proxy.setVmInstanceId(vmId);
        proxy.setNetworkId(network.getId());
        proxy.setIpAddress(ipAddress);
        proxy.setForwardScheme(validatedProtocol);
        proxy.setForwardPort(port);
        proxy.setNpmProxyHostId(created.getId());
        proxy.setReverseProxyDomainId(domain.getId());
        proxy.setAccountId(vm.getAccountId());
        proxy.setDomainId(vm.getDomainId());
        try {
            final ReverseProxyHostVO persisted = reverseProxyHostDao.persist(proxy);
            logger.info(String.format("Created instance proxy %s (id=%s) for instance %s forwarding to %s:%d (%s)",
                    fqdn, persisted.getUuid(), vm.getHostName(), ipAddress, port, validatedProtocol));
            return persisted;
        } catch (final Exception e) {
            logger.error(String.format("Failed to persist the instance proxy %s, attempting to revert the proxy host on the "
                    + "Nginx Proxy Manager", fqdn), e);
            try {
                client.deleteProxyHost(created.getId());
            } catch (final Exception cleanupException) {
                logger.error(String.format("Failed to revert proxy host %s on the Nginx Proxy Manager after a persistence "
                        + "failure, please remove it manually", created.getId()), cleanupException);
            }
            throw new CloudRuntimeException("Failed to create the instance proxy, please try again", e);
        }
    }

    @Override
    public void deleteInstanceProxy(final Long proxyId) {
        validateConfiguration();

        if (proxyId == null) {
            throw new InvalidParameterValueException("An instance proxy id is required");
        }
        final Account caller = CallContext.current().getCallingAccount();
        final ReverseProxyHostVO proxy = reverseProxyHostDao.findById(proxyId);
        if (proxy == null) {
            throw new InvalidParameterValueException(String.format("Unable to find instance proxy with id %s", proxyId));
        }
        accountManager.checkAccess(caller, SecurityChecker.AccessType.OperateEntry, false, proxy);

        final NpmClient client = getClient();
        try {
            client.deleteProxyHost(proxy.getNpmProxyHostId());
        } catch (final Exception e) {
            logger.warn(String.format("Failed to delete proxy host id=%s from the Nginx Proxy Manager while removing instance "
                    + "proxy %s", proxy.getNpmProxyHostId(), proxy.getFqdn()), e);
            throw new CloudRuntimeException("Failed to remove the proxy host from the Nginx Proxy Manager, please try again later");
        }
        reverseProxyHostDao.remove(proxy.getId());
        logger.info(String.format("Deleted instance proxy %s (id=%s)", proxy.getFqdn(), proxy.getUuid()));
    }

    @Override
    public void cleanupProxiesForVm(final Long vmId) {
        if (vmId == null) {
            return;
        }
        final List<ReverseProxyHostVO> proxies = reverseProxyHostDao.listByVmId(vmId);
        if (proxies.isEmpty()) {
            return;
        }
        final NpmClient client = isConfigured() ? getClient() : null;
        for (final ReverseProxyHostVO proxy : proxies) {
            if (client != null) {
                try {
                    client.deleteProxyHost(proxy.getNpmProxyHostId());
                } catch (final Exception e) {
                    logger.error(String.format("Failed to delete proxy host %s (npm id=%s) from the Nginx Proxy Manager while "
                            + "cleaning up proxies for instance id=%s, please remove it manually",
                            proxy.getFqdn(), proxy.getNpmProxyHostId(), vmId), e);
                    continue;
                }
            }
            reverseProxyHostDao.remove(proxy.getId());
            logger.info(String.format("Cleaned up instance proxy %s for expunged instance id=%s", proxy.getFqdn(), vmId));
        }
    }

    @Override
    public CheckInstanceProxyNameResponse checkInstanceProxyName(final String name, final Long domainId, final CheckInstanceProxyNameCmd cmd) {
        validateConfiguration();

        final CheckInstanceProxyNameResponse response = new CheckInstanceProxyNameResponse();
        String message = null;
        String validatedName = null;
        try {
            validatedName = validateProxyName(name);
        } catch (final InvalidParameterValueException e) {
            message = e.getMessage();
        }
        final String fqdn;
        if (validatedName != null) {
            final ReverseProxyDomainVO domain;
            try {
                final Account caller = CallContext.current().getCallingAccount();
                domain = resolveProxyDomain(domainId, caller, null);
            } catch (final CloudRuntimeException e) {
                response.setName(validatedName);
                response.setMessage(e.getMessage());
                response.setAvailable(false);
                return response;
            }
            fqdn = String.format("%s.%s", validatedName, domain.getDomain());
            response.setName(validatedName);
            response.setFqdn(fqdn);
            if (reverseProxyHostDao.findByFqdn(fqdn) != null) {
                message = String.format("The name '%s' is already in use, please choose another name", validatedName);
            } else {
                try {
                    final NpmClient client = getClient();
                    if (client.findProxyHostByDomain(fqdn) != null) {
                        message = String.format("The domain '%s' is already in use, please choose another name", fqdn);
                    }
                } catch (final Exception e) {
                    logger.warn(String.format("Failed to check the availability of %s on the Nginx Proxy Manager", fqdn), e);
                    message = "Unable to check the name availability on the Nginx Proxy Manager, please try again later";
                }
            }
        } else {
            response.setName(name);
        }
        response.setMessage(message);
        response.setAvailable(message == null);
        return response;
    }

    ///////////////////////////////////////////////////////////
    /////////////// Domain suffix management //////////////////
    ///////////////////////////////////////////////////////////

    protected String validateDomainName(final String domain) {
        if (StringUtils.isBlank(domain)) {
            throw new InvalidParameterValueException("A domain suffix is required");
        }
        final String trimmed = domain.trim().toLowerCase(Locale.ROOT);
        if (!DOMAIN_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidParameterValueException(String.format("The domain suffix '%s' is not valid, it must consist of "
                    + "DNS labels of lowercase letters, digits and hyphens separated by dots, for example 'cloud.company.com'", domain));
        }
        return trimmed;
    }

    @Override
    public ReverseProxyDomainVO addReverseProxyDomain(final String domain, final String description, final Boolean isPublic,
            final Long npmCertificateId, final List<Long> accountIds, final List<Long> networkIds) {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        final String validated = validateDomainName(domain);
        if (reverseProxyDomainDao.findByName(validated) != null) {
            throw new InvalidParameterValueException(String.format("The domain suffix '%s' already exists", validated));
        }
        final ReverseProxyDomainVO vo = new ReverseProxyDomainVO(validated);
        vo.setDescription(StringUtils.trimToNull(description));
        vo.setPublic(isPublic != null && isPublic);
        if (npmCertificateId != null && npmCertificateId < 0) {
            throw new InvalidParameterValueException("The Nginx Proxy Manager certificate id must not be negative");
        }
        vo.setNpmCertificateId(npmCertificateId);
        final ReverseProxyDomainVO persisted = reverseProxyDomainDao.persist(vo);
        updateGrants(persisted, accountIds, networkIds);
        logger.info(String.format("Added reverse proxy domain suffix %s (id=%s, public=%s)", persisted.getDomain(),
                persisted.getUuid(), persisted.isPublic()));
        return persisted;
    }

    @Override
    public ReverseProxyDomainVO updateReverseProxyDomain(final Long id, final String description, final Boolean isPublic,
            final Long npmCertificateId, final List<Long> accountIds, final List<Long> networkIds) {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        final ReverseProxyDomainVO domain = getDomainByIdOrThrow(id);
        if (description != null) {
            domain.setDescription(StringUtils.trimToNull(description));
        }
        if (isPublic != null) {
            domain.setPublic(isPublic);
        }
        if (npmCertificateId != null) {
            if (npmCertificateId < 0) {
                throw new InvalidParameterValueException("The Nginx Proxy Manager certificate id must not be negative");
            }
            domain.setNpmCertificateId(npmCertificateId > 0 ? npmCertificateId : null);
        }
        reverseProxyDomainDao.update(id, domain);
        updateGrants(domain, accountIds, networkIds);
        logger.info(String.format("Updated reverse proxy domain suffix %s (id=%s, public=%s)", domain.getDomain(),
                domain.getUuid(), domain.isPublic()));
        return domain;
    }

    @Override
    public void deleteReverseProxyDomain(final Long id) {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        final ReverseProxyDomainVO domain = getDomainByIdOrThrow(id);
        final List<ReverseProxyHostVO> proxies = reverseProxyHostDao.listByDomainId(domain.getId());
        if (!proxies.isEmpty()) {
            logger.info(String.format("Deleting %d proxy hosts still exposed on the reverse proxy domain suffix %s (id=%s)",
                    proxies.size(), domain.getDomain(), domain.getUuid()));
            final NpmClient client = isConfigured() ? getClient() : null;
            for (final ReverseProxyHostVO proxy : proxies) {
                if (client != null) {
                    try {
                        client.deleteProxyHost(proxy.getNpmProxyHostId());
                    } catch (final Exception e) {
                        logger.error(String.format("Failed to delete proxy host %s (npm id=%s) from the Nginx Proxy Manager while "
                                + "deleting the reverse proxy domain suffix %s, please remove it manually",
                                proxy.getFqdn(), proxy.getNpmProxyHostId(), domain.getDomain()), e);
                    }
                }
                reverseProxyHostDao.remove(proxy.getId());
                logger.info(String.format("Deleted instance proxy %s while deleting the reverse proxy domain suffix %s (id=%s)",
                        proxy.getFqdn(), domain.getDomain(), domain.getUuid()));
            }
        }
        for (final ReverseProxyDomainMapVO map : reverseProxyDomainMapDao.listByDomainId(domain.getId())) {
            reverseProxyDomainMapDao.remove(map.getId());
        }
        reverseProxyDomainDao.remove(domain.getId());
        logger.info(String.format("Deleted reverse proxy domain suffix %s (id=%s)", domain.getDomain(), domain.getUuid()));
    }

    @Override
    public ListResponse<ReverseProxyDomainResponse> listReverseProxyDomains(final ListReverseProxyDomainsCmd cmd) {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        final Account caller = CallContext.current().getCallingAccount();
        final UserVmVO vm = cmd.getVirtualMachineId() != null ? userVmDao.findById(cmd.getVirtualMachineId()) : null;
        final List<ReverseProxyDomainVO> domains = listAllowedDomains(caller, vm);
        final Long domainId = cmd.getId();
        final String keyword = cmd.getKeyword();
        final boolean showDetails = isAdmin(caller);
        final List<ReverseProxyDomainResponse> responses = new ArrayList<>();
        int count = 0;
        for (final ReverseProxyDomainVO domain : domains) {
            if (domainId != null && domain.getId() != domainId) {
                continue;
            }
            if (StringUtils.isNotBlank(keyword) && !domain.getDomain().contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            count++;
            responses.add(createReverseProxyDomainResponse(domain, showDetails));
        }
        final ListResponse<ReverseProxyDomainResponse> response = new ListResponse<>();
        response.setResponses(responses, count);
        return response;
    }

    protected ReverseProxyDomainVO getDomainByIdOrThrow(final Long id) {
        if (id == null) {
            throw new InvalidParameterValueException("A reverse proxy domain suffix id is required");
        }
        final ReverseProxyDomainVO domain = reverseProxyDomainDao.findById(id);
        if (domain == null) {
            throw new InvalidParameterValueException(String.format("Unable to find reverse proxy domain suffix with id %s", id));
        }
        return domain;
    }

    /**
     * Replaces the grants of the given domain suffix with the given accounts and shared networks.
     * Null lists leave the grants unchanged.
     */
    protected void updateGrants(final ReverseProxyDomainVO domain, final List<Long> accountIds, final List<Long> networkIds) {
        if (accountIds == null && networkIds == null) {
            return;
        }
        for (final ReverseProxyDomainMapVO map : reverseProxyDomainMapDao.listByDomainId(domain.getId())) {
            reverseProxyDomainMapDao.remove(map.getId());
        }
        if (accountIds != null) {
            for (final Long accountId : new ArrayList<>(new java.util.LinkedHashSet<>(accountIds))) {
                if (accountId == null) {
                    continue;
                }
                final Account account = accountDao.findById(accountId);
                if (account == null || account.getRemoved() != null) {
                    throw new InvalidParameterValueException(String.format("Unable to find account with id %s", accountId));
                }
                reverseProxyDomainMapDao.persist(new ReverseProxyDomainMapVO(domain.getId(), accountId, null));
            }
        }
        if (networkIds != null) {
            for (final Long networkId : new ArrayList<>(new java.util.LinkedHashSet<>(networkIds))) {
                if (networkId == null) {
                    continue;
                }
                final NetworkVO network = networkDao.findById(networkId);
                if (network == null) {
                    throw new InvalidParameterValueException(String.format("Unable to find network with id %s", networkId));
                }
                if (network.getGuestType() != Network.GuestType.Shared) {
                    throw new InvalidParameterValueException(String.format("The network '%s' is not a shared network, only "
                            + "shared networks can be granted access to a reverse proxy domain suffix", network.getName()));
                }
                reverseProxyDomainMapDao.persist(new ReverseProxyDomainMapVO(domain.getId(), null, networkId));
            }
        }
    }

    @Override
    public ReverseProxyDomainResponse createReverseProxyDomainResponse(final ReverseProxyDomainVO domain, final boolean showDetails) {
        final ReverseProxyDomainResponse response = new ReverseProxyDomainResponse();
        response.setObjectName("reverseproxydomain");
        response.setId(domain.getUuid());
        response.setDomain(domain.getDomain());
        response.setDescription(domain.getDescription());
        response.setPublic(domain.isPublic());
        response.setNpmCertificateId(domain.getNpmCertificateId());
        response.setCreated(domain.getCreated());
        if (showDetails) {
            final List<String> accounts = new ArrayList<>();
            final List<String> accountIds = new ArrayList<>();
            final List<String> networks = new ArrayList<>();
            final List<String> networkIds = new ArrayList<>();
            for (final ReverseProxyDomainMapVO map : reverseProxyDomainMapDao.listByDomainId(domain.getId())) {
                if (map.getAccountId() != null) {
                    final Account account = accountDao.findById(map.getAccountId());
                    if (account != null) {
                        accounts.add(account.getAccountName());
                        accountIds.add(account.getUuid());
                    }
                } else if (map.getNetworkId() != null) {
                    final NetworkVO network = networkDao.findById(map.getNetworkId());
                    if (network != null) {
                        networks.add(network.getName());
                        networkIds.add(network.getUuid());
                    }
                }
            }
            response.setAccounts(accounts);
            response.setAccountIds(accountIds);
            response.setNetworks(networks);
            response.setNetworkIds(networkIds);
            response.setProxyCount(reverseProxyHostDao.countByDomainId(domain.getId()));
        }
        return response;
    }

    @Override
    public ListResponse<InstanceProxyResponse> listInstanceProxies(final ListInstanceProxiesCmd cmd) {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        final Account caller = CallContext.current().getCallingAccount();
        final Long proxyId = cmd.getId();
        final Long vmId = cmd.getVirtualMachineId();
        final String keyword = cmd.getKeyword();

        final List<Long> permittedAccounts = new ArrayList<>();
        final Ternary<Long, Boolean, Project.ListProjectResourcesCriteria> domainIdRecursiveListProject =
                new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountManager.buildACLSearchParameters(caller, proxyId, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts,
                domainIdRecursiveListProject, cmd.listAll(), false);
        final Long domainId = domainIdRecursiveListProject.first();
        final Boolean isRecursive = domainIdRecursiveListProject.second();
        final Project.ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        final Filter searchFilter = new Filter(ReverseProxyHostVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        final SearchBuilder<ReverseProxyHostVO> sb = reverseProxyHostDao.createSearchBuilder();
        accountManager.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("vmInstanceId", sb.entity().getVmInstanceId(), SearchCriteria.Op.EQ);
        sb.and("fqdn", sb.entity().getFqdn(), SearchCriteria.Op.LIKE);

        final SearchCriteria<ReverseProxyHostVO> sc = sb.create();
        accountManager.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        if (proxyId != null) {
            sc.setParameters("id", proxyId);
        }
        if (vmId != null) {
            sc.setParameters("vmInstanceId", vmId);
        }
        if (keyword != null) {
            sc.setParameters("fqdn", "%" + keyword + "%");
        }

        final Pair<List<ReverseProxyHostVO>, Integer> result = reverseProxyHostDao.searchAndCount(sc, searchFilter);
        final List<InstanceProxyResponse> responses = new ArrayList<>();
        for (final ReverseProxyHostVO proxy : result.first()) {
            responses.add(createInstanceProxyResponse(proxy));
        }
        final ListResponse<InstanceProxyResponse> response = new ListResponse<>();
        response.setResponses(responses, result.second());
        return response;
    }

    @Override
    public ListResponse<InstanceProxyResponse> listReverseProxyHosts(final ListReverseProxyHostsCmd cmd) {
        if (!isEnabled()) {
            throw new CloudRuntimeException("The reverse proxy integration is disabled");
        }
        final Long domainId = cmd.getDomainId();
        final Long vmId = cmd.getVirtualMachineId();
        final String keyword = cmd.getKeyword();

        final Filter searchFilter = new Filter(ReverseProxyHostVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        final SearchBuilder<ReverseProxyHostVO> sb = reverseProxyHostDao.createSearchBuilder();
        sb.and("vmInstanceId", sb.entity().getVmInstanceId(), SearchCriteria.Op.EQ);
        sb.and("fqdn", sb.entity().getFqdn(), SearchCriteria.Op.LIKE);
        sb.and("domainId", sb.entity().getDomainId(), SearchCriteria.Op.IN);
        sb.and("reverseProxyDomainId", sb.entity().getReverseProxyDomainId(), SearchCriteria.Op.EQ);

        final SearchCriteria<ReverseProxyHostVO> sc = sb.create();
        if (vmId != null) {
            sc.setParameters("vmInstanceId", vmId);
        }
        if (StringUtils.isNotBlank(keyword)) {
            sc.setParameters("fqdn", "%" + keyword.trim() + "%");
        }
        final Long reverseProxyDomainId = cmd.getReverseProxyDomainId();
        if (reverseProxyDomainId != null) {
            sc.setParameters("reverseProxyDomainId", reverseProxyDomainId);
        }
        final List<Long> domainIds = resolveDomainIds(domainId, cmd.isRecursive());
        if (!domainIds.isEmpty()) {
            sc.setParameters("domainId", domainIds.toArray(new Long[0]));
        }

        final Pair<List<ReverseProxyHostVO>, Integer> result = reverseProxyHostDao.searchAndCount(sc, searchFilter);
        final List<InstanceProxyResponse> responses = new ArrayList<>();
        for (final ReverseProxyHostVO proxy : result.first()) {
            responses.add(createInstanceProxyResponse(proxy));
        }
        final ListResponse<InstanceProxyResponse> response = new ListResponse<>();
        response.setResponses(responses, result.second());
        return response;
    }

    /**
     * Resolves the domain ids to filter on: when no domain id is given all domains are returned,
     * when recursive is set the sub-domains of the given domain are included as well
     */
    protected List<Long> resolveDomainIds(final Long domainId, final Boolean recursive) {
        if (domainId == null) {
            return new ArrayList<>();
        }
        if (recursive != null && recursive) {
            return domainDao.getDomainAndChildrenIds(domainId);
        }
        return List.of(domainId);
    }

    @Override
    public InstanceProxyResponse createInstanceProxyResponse(final ReverseProxyHost proxy) {
        final InstanceProxyResponse response = new InstanceProxyResponse();
        response.setObjectName("instanceproxy");
        response.setId(proxy.getUuid());
        response.setName(proxy.getName());
        response.setFqdn(proxy.getFqdn());
        // The proxy hosts are always created with a certificate, therefore https is always available
        response.setUrl(String.format("https://%s", proxy.getFqdn()));
        response.setIpAddress(proxy.getIpAddress());
        response.setProtocol(proxy.getForwardScheme());
        response.setPort(proxy.getForwardPort());
        response.setState(proxy.getState() != null ? proxy.getState().name() : ReverseProxyHost.State.Active.name());
        response.setCreated(proxy.getCreated());

        final UserVmVO vm = userVmDao.findById(proxy.getVmInstanceId());
        if (vm != null) {
            response.setVirtualMachineId(vm.getUuid());
            response.setVirtualMachineName(vm.getDisplayName() != null ? vm.getDisplayName() : vm.getHostName());
        }
        final Account account = accountDao.findById(proxy.getAccountId());
        if (account != null) {
            response.setAccount(account.getAccountName());
        }
        final com.cloud.domain.DomainVO domain = domainDao.findById(proxy.getDomainId());
        if (domain != null) {
            response.setDomainId(String.valueOf(proxy.getDomainId()));
            response.setDomain(domain.getName());
        }
        return response;
    }

    ///////////////////////////////////////////////////////////
    ////////////////// Plugin configuration ///////////////////
    ///////////////////////////////////////////////////////////

    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<>();
        if (!isEnabled()) {
            return cmdList;
        }
        cmdList.add(AddInstanceProxyCmd.class);
        cmdList.add(ListInstanceProxiesCmd.class);
        cmdList.add(DeleteInstanceProxyCmd.class);
        cmdList.add(CheckInstanceProxyNameCmd.class);
        cmdList.add(ListReverseProxyHostsCmd.class);
        cmdList.add(ListReverseProxyDomainsCmd.class);
        cmdList.add(AddReverseProxyDomainCmd.class);
        cmdList.add(UpdateReverseProxyDomainCmd.class);
        cmdList.add(DeleteReverseProxyDomainCmd.class);
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return ReverseProxyService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                ReverseProxyEnabled,
                ReverseProxyDomain,
                ReverseProxyNetworkId,
                ReverseProxyNpmUrl,
                ReverseProxyNpmUser,
                ReverseProxyNpmPassword,
                ReverseProxyNpmValidateSsl,
                ReverseProxyNpmRequestTimeout,
                ReverseProxyNpmCertificateId,
                ReverseProxyForceHttps,
                ReverseProxyReservedNames
        };
    }

    ///////////////////////////////////////////////////////////
    ///////////////// VM expunge cleanup hook //////////////////
    ///////////////////////////////////////////////////////////

    protected class VmExpungeStateListener implements StateListener<VirtualMachine.State, VirtualMachine.Event, VirtualMachine> {
        @Override
        public boolean preStateTransitionEvent(final VirtualMachine.State oldState, final VirtualMachine.Event event,
                final VirtualMachine.State newState, final VirtualMachine vm, final boolean status, final Object opaque) {
            return true;
        }

        @Override
        public boolean postStateTransitionEvent(final StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition,
                final VirtualMachine vm, final boolean status, final Object opaque) {
            if (transition.getToState() == VirtualMachine.State.Expunging && transition.getEvent() == VirtualMachine.Event.ExpungeOperation) {
                try {
                    cleanupProxiesForVm(vm.getId());
                } catch (final Exception e) {
                    logger.warn(String.format("Failed to clean up reverse proxy hosts for the expunged instance id=%s", vm.getId()), e);
                }
            }
            return true;
        }
    }
}
