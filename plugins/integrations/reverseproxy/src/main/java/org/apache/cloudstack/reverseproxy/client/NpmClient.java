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

package org.apache.cloudstack.reverseproxy.client;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.X509TrustManager;

import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.cloud.utils.nio.TrustAllManager;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST client for the Nginx Proxy Manager (NPM) admin API.
 * <p>
 * NPM does not support API keys, therefore this client authenticates non-interactively with
 * the credentials of a dedicated NPM user: it logs in via POST /api/tokens, caches the
 * returned JWT until shortly before its expiry and transparently re-authenticates on 401
 * responses.
 * </p>
 */
public class NpmClient {
    protected Logger logger = LogManager.getLogger(getClass());

    private static final long DEFAULT_TOKEN_VALIDITY_MS = 12L * 60 * 60 * 1000; // 12 hours, NPM default expiry is 1 day
    private static final long TOKEN_REFRESH_MARGIN_MS = 60L * 1000;

    private final HttpClient httpClient;
    private final String npmApiUrl;
    private final String username;
    private final String password;
    private final ObjectMapper mapper;

    private volatile String token;
    private volatile long tokenExpiresAt;

    public NpmClient(final String url, final String username, final String password, final boolean validateSslCertificate,
            final int timeoutSeconds) throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        if (StringUtils.isBlank(url) || StringUtils.isAnyBlank(username, password)) {
            throw new CloudStackNpmConfigurationException("Nginx Proxy Manager URL, user and password must be configured");
        }
        this.npmApiUrl = buildApiUrl(url);
        this.username = username;
        this.password = password;
        this.mapper = new ObjectMapper();
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        final RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutSeconds * 1000)
                .setConnectionRequestTimeout(timeoutSeconds * 1000)
                .setSocketTimeout(timeoutSeconds * 1000)
                .build();

        if (!validateSslCertificate && npmApiUrl.startsWith("https")) {
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new X509TrustManager[] {new TrustAllManager()}, new SecureRandom());
            final SSLConnectionSocketFactory factory = new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);
            this.httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(config)
                    .setConnectionManager(buildConnectionManager())
                    .setSSLSocketFactory(factory)
                    .build();
        } else {
            this.httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(config)
                    .setConnectionManager(buildConnectionManager())
                    .build();
        }
    }

    /**
     * The default pool allows only 2 connections per route and would serialize concurrent requests;
     * a slightly larger pool with staleness validation avoids stalls when NPM closes idle connections.
     */
    private PoolingHttpClientConnectionManager buildConnectionManager() {
        final PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(20);
        connectionManager.setDefaultMaxPerRoute(8);
        connectionManager.setValidateAfterInactivity(2000);
        return connectionManager;
    }

    /**
     * Visible for testing
     */
    protected NpmClient(final HttpClient httpClient, final String url, final String username, final String password) {
        this.httpClient = httpClient;
        this.npmApiUrl = buildApiUrl(url);
        this.username = username;
        this.password = password;
        this.mapper = new ObjectMapper();
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public static class CloudStackNpmConfigurationException extends RuntimeException {
        public CloudStackNpmConfigurationException(String message) {
            super(message);
        }
    }

    private static String buildApiUrl(final String url) {
        String trimmed = url.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/api")) {
            return trimmed;
        }
        return trimmed + "/api";
    }

    ///////////////////////////////////////////////////////////
    //////////////////// Authentication ///////////////////////
    ///////////////////////////////////////////////////////////

    protected synchronized void login() {
        try {
            final Map<String, String> credentials = new HashMap<>();
            credentials.put("identity", username);
            credentials.put("secret", password);
            final HttpPost request = new HttpPost(npmApiUrl + "/tokens");
            request.setHeader("content-type", "application/json");
            request.setEntity(new StringEntity(mapper.writeValueAsString(credentials), StandardCharsets.UTF_8));

            final HttpResponse response = httpClient.execute(request);
            final int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != HttpStatus.SC_OK) {
                final String errorMessage = parseErrorMessage(response, "Invalid Nginx Proxy Manager credentials");
                EntityUtils.consumeQuietly(response.getEntity());
                logger.error(String.format("Failed to authenticate against the Nginx Proxy Manager API at %s, status code %d: %s",
                        npmApiUrl, statusCode, errorMessage));
                throw new ServerApiException(ApiErrorCode.UNAUTHORIZED, String.format(
                        "Failed to authenticate against the Nginx Proxy Manager API, please ask your administrator to check the "
                                + "reverse proxy integration settings: %s", errorMessage));
            }
            final JsonNode root = mapper.readTree(readBodyAndRelease(response));
            token = root.path("token").asText(null);
            tokenExpiresAt = parseExpiryMillis(root.get("expires"));
            if (StringUtils.isBlank(token)) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Nginx Proxy Manager API returned no auth token");
            }
            logger.debug(String.format("Authenticated against the Nginx Proxy Manager API at %s, token valid until %s",
                    npmApiUrl, new Date(tokenExpiresAt)));
        } catch (final IOException e) {
            logger.error("Failed to authenticate against the Nginx Proxy Manager API due to: ", e);
            throwTimeoutOrServerException(e);
        }
    }

    private long parseExpiryMillis(final JsonNode expiresNode) {
        if (expiresNode != null && !expiresNode.isNull()) {
            try {
                final Date expires = mapper.convertValue(expiresNode.asText(), Date.class);
                if (expires != null) {
                    return expires.getTime();
                }
            } catch (final IllegalArgumentException e) {
                logger.debug("Could not parse Nginx Proxy Manager token expiry, using default validity", e);
            }
        }
        return System.currentTimeMillis() + DEFAULT_TOKEN_VALIDITY_MS;
    }

    private synchronized void invalidateToken() {
        token = null;
        tokenExpiresAt = 0;
    }

    ///////////////////////////////////////////////////////////
    //////////////////// Request plumbing /////////////////////
    ///////////////////////////////////////////////////////////

    private HttpResponse execute(final HttpUriRequest request) throws IOException {
        return executeWithAuth(request, true);
    }

    /**
     * Reads the response body as a String and makes sure the underlying connection is released back to
     * the pool, even when the caller does not consume the entity itself. Must be called for every
     * successful response, otherwise the pooled connections leak and the client stalls.
     */
    private String readBodyAndRelease(final HttpResponse response) throws IOException {
        try {
            final HttpEntity entity = response.getEntity();
            return entity == null ? null : EntityUtils.toString(entity, StandardCharsets.UTF_8);
        } finally {
            EntityUtils.consumeQuietly(response.getEntity());
        }
    }

    private HttpResponse executeWithAuth(final HttpUriRequest request, final boolean allowRetry) throws IOException {
        final String currentToken = getToken();
        if (currentToken != null) {
            request.setHeader("Authorization", "Bearer " + currentToken);
        }
        final HttpResponse response = httpClient.execute(request);
        if (response.getStatusLine().getStatusCode() == HttpStatus.SC_UNAUTHORIZED && allowRetry) {
            logger.debug("Nginx Proxy Manager API returned 401, re-authenticating and retrying the request");
            EntityUtils.consumeQuietly(response.getEntity());
            invalidateToken();
            final String freshToken = getToken();
            request.setHeader("Authorization", "Bearer " + freshToken);
            return httpClient.execute(request);
        }
        return response;
    }

    private String getToken() {
        if (token == null || System.currentTimeMillis() >= tokenExpiresAt - TOKEN_REFRESH_MARGIN_MS) {
            login();
        }
        return token;
    }

    private String parseErrorMessage(final HttpResponse response, final String fallback) {
        try {
            final HttpEntity entity = response.getEntity();
            if (entity == null) {
                return fallback;
            }
            final String body = EntityUtils.toString(entity, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(body)) {
                return fallback;
            }
            final JsonNode root = mapper.readTree(body);
            final JsonNode error = root.get("error");
            if (error != null && error.get("message") != null) {
                return error.get("message").asText();
            }
            return body;
        } catch (final IOException e) {
            return fallback;
        }
    }

    private void throwTimeoutOrServerException(final IOException e) {
        if (e instanceof ConnectTimeoutException || e instanceof SocketTimeoutException) {
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR,
                    "Connection to the Nginx Proxy Manager API timed out, please try again later.");
        } else if (e instanceof SSLException) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "SSL error connecting to the Nginx Proxy Manager API", e);
        }
        throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR,
                "Internal error communicating with the Nginx Proxy Manager API", e);
    }

    private void checkResponse(final HttpResponse response, final HttpUriRequest request, final int... expectedStatusCodes) {
        for (final int expected : expectedStatusCodes) {
            if (response.getStatusLine().getStatusCode() == expected) {
                return;
            }
        }
        final int statusCode = response.getStatusLine().getStatusCode();
        final String errorMessage = parseErrorMessage(response, "no error details provided by Nginx Proxy Manager");
        logger.error(String.format("Unexpected response from Nginx Proxy Manager API %s %s: status=%d, error=%s",
                request.getMethod(), request.getURI(), statusCode, errorMessage));
        if (statusCode == HttpStatus.SC_BAD_REQUEST) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format(
                    "Nginx Proxy Manager rejected the request: %s", errorMessage));
        }
        if (statusCode == HttpStatus.SC_FORBIDDEN) {
            throw new ServerApiException(ApiErrorCode.UNAUTHORIZED, String.format(
                    "The Nginx Proxy Manager account is not allowed to perform this operation: %s", errorMessage));
        }
        throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format(
                "Unexpected response from Nginx Proxy Manager (status %d): %s", statusCode, errorMessage));
    }

    ///////////////////////////////////////////////////////////
    ///////////////// Public APIs: proxy hosts ////////////////
    ///////////////////////////////////////////////////////////

    /**
     * Lists proxy hosts, optionally filtered by a search query matched against the domain names
     */
    public List<NpmProxyHost> listProxyHosts(final String query) {
        StringBuilder path = new StringBuilder("/nginx/proxy-hosts");
        if (StringUtils.isNotBlank(query)) {
            path.append("?query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        }
        try {
            final HttpGet request = new HttpGet(npmApiUrl + path);
            final HttpResponse response = execute(request);
            try {
                if (response.getStatusLine().getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                    // tolerate a 404 from proxies in front of the NPM API, treat as an empty result
                    return new ArrayList<>();
                }
                checkResponse(response, request, HttpStatus.SC_OK);
                final String body = readBodyAndRelease(response);
                if (StringUtils.isBlank(body)) {
                    return new ArrayList<>();
                }
                final NpmProxyHost[] hosts = mapper.readValue(body, NpmProxyHost[].class);
                return new ArrayList<>(Arrays.asList(hosts));
            } finally {
                EntityUtils.consumeQuietly(response.getEntity());
            }
        } catch (final IOException e) {
            logger.error("Failed to list proxy hosts on the Nginx Proxy Manager due to: ", e);
            throwTimeoutOrServerException(e);
            return new ArrayList<>(); // never reached
        }
    }

    /**
     * Returns the proxy host serving exactly the given domain name (case insensitive), or null
     */
    public NpmProxyHost findProxyHostByDomain(final String domainName) {
        if (StringUtils.isBlank(domainName)) {
            return null;
        }
        for (final NpmProxyHost host : listProxyHosts(domainName)) {
            if (host.getDomainNames() == null) {
                continue;
            }
            for (final String domain : host.getDomainNames()) {
                if (domainName.equalsIgnoreCase(domain)) {
                    return host;
                }
            }
        }
        return null;
    }

    /**
     * Creates a proxy host in NPM
     * @return the created proxy host
     */
    public NpmProxyHost createProxyHost(final NpmProxyHost proxyHost) {
        try {
            final HttpPost request = new HttpPost(npmApiUrl + "/nginx/proxy-hosts");
            request.setHeader("content-type", "application/json");
            request.setEntity(new StringEntity(mapper.writeValueAsString(proxyHost), StandardCharsets.UTF_8));
            final HttpResponse response = execute(request);
            try {
                checkResponse(response, request, HttpStatus.SC_CREATED, HttpStatus.SC_OK);
                return mapper.readValue(readBodyAndRelease(response), NpmProxyHost.class);
            } finally {
                EntityUtils.consumeQuietly(response.getEntity());
            }
        } catch (final IOException e) {
            logger.error("Failed to create proxy host on the Nginx Proxy Manager due to: ", e);
            throwTimeoutOrServerException(e);
            return null; // never reached
        }
    }

    /**
     * Deletes a proxy host in NPM. Deleting a non existing host is tolerated.
     * @return true if the host is gone after this call
     */
    public boolean deleteProxyHost(final long proxyHostId) {
        try {
            final HttpDelete request = new HttpDelete(npmApiUrl + "/nginx/proxy-hosts/" + proxyHostId);
            final HttpResponse response = execute(request);
            try {
                final int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == HttpStatus.SC_OK) {
                    return true;
                }
                if (statusCode == HttpStatus.SC_NOT_FOUND) {
                    logger.warn(String.format("Proxy host id=%s does not exist on the Nginx Proxy Manager, treating deletion as done",
                            proxyHostId));
                    return true;
                }
                checkResponse(response, request, HttpStatus.SC_OK);
                return false;
            } finally {
                EntityUtils.consumeQuietly(response.getEntity());
            }
        } catch (final IOException e) {
            logger.error(String.format("Failed to delete proxy host id=%s on the Nginx Proxy Manager due to: ", proxyHostId), e);
            throwTimeoutOrServerException(e);
            return false; // never reached
        }
    }

    ///////////////////////////////////////////////////////////
    //////////////// Public APIs: certificates ////////////////
    ///////////////////////////////////////////////////////////

    /**
     * Lists the certificates known to NPM
     */
    public List<NpmCertificate> listCertificates() {
        try {
            final HttpGet request = new HttpGet(npmApiUrl + "/nginx/certificates");
            final HttpResponse response = execute(request);
            try {
                checkResponse(response, request, HttpStatus.SC_OK);
                final String body = readBodyAndRelease(response);
                if (StringUtils.isBlank(body)) {
                    return new ArrayList<>();
                }
                final NpmCertificate[] certificates = mapper.readValue(body, NpmCertificate[].class);
                return new ArrayList<>(Arrays.asList(certificates));
            } finally {
                EntityUtils.consumeQuietly(response.getEntity());
            }
        } catch (final IOException e) {
            logger.error("Failed to list certificates on the Nginx Proxy Manager due to: ", e);
            throwTimeoutOrServerException(e);
            return new ArrayList<>(); // never reached
        }
    }

    /**
     * Finds the certificate covering the given wildcard domain, e.g. '*.cloud.company.com'
     * @return the certificate id or null if no (unique) match was found
     */
    public NpmCertificate findCertificateForWildcardDomain(final String wildcardDomain) {
        final String needle = wildcardDomain.toLowerCase(Locale.ROOT);
        NpmCertificate match = null;
        for (final NpmCertificate certificate : listCertificates()) {
            if (certificate.getDomainNames() == null) {
                continue;
            }
            for (final String domain : certificate.getDomainNames()) {
                if (needle.equalsIgnoreCase(domain)) {
                    if (match != null) {
                        logger.warn(String.format("More than one Nginx Proxy Manager certificate covers %s", wildcardDomain));
                        return null;
                    }
                    match = certificate;
                }
            }
        }
        return match;
    }

    public String getApiUrl() {
        return npmApiUrl;
    }
}
