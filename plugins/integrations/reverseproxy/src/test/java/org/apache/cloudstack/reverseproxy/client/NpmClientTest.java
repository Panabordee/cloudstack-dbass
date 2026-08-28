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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.github.tomakehurst.wiremock.junit.WireMockRule;

public class NpmClientTest {

    private final int port = 14433;
    private final String baseUrl = "http://localhost:" + port;
    private final String user = "cloudstack@example.com";
    private final String password = "secret";

    private NpmClient client;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(port);

    @Before
    public void setUp() throws Exception {
        stubSuccessfulLogin();
        client = new NpmClient(baseUrl, user, password, false, 5);
    }

    private void stubSuccessfulLogin() {
        wireMockRule.resetAll();
        wireMockRule.stubFor(post(urlEqualTo("/api/tokens"))
                .withRequestBody(containing("identity"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withStatus(200)
                        .withBody("{\"token\":\"jwt-token\",\"expires\":\"2030-01-01T00:00:00.000Z\"}")));
    }

    private NpmProxyHost createTestProxyHost() {
        final NpmProxyHost host = new NpmProxyHost();
        host.setDomainNames(Collections.singletonList("my-web.cloud.company.com"));
        host.setForwardScheme("http");
        host.setForwardHost("10.0.0.5");
        host.setForwardPort(8080);
        host.setCertificateId(3);
        host.setSslForced(false);
        host.setEnabled(true);
        return host;
    }

    ///////////////////////////////////////////////////////////
    //////////////////// Authentication ///////////////////////
    ///////////////////////////////////////////////////////////

    @Test
    public void testLoginSendsCredentialsAndRequestsCarryBearerToken() {
        wireMockRule.stubFor(get(urlEqualTo("/api/nginx/proxy-hosts"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withStatus(200)
                        .withBody("[]")));
        client.listProxyHosts(null);
        verify(postRequestedFor(urlEqualTo("/api/tokens"))
                .withRequestBody(containing("\"identity\":\"" + user + "\""))
                .withRequestBody(containing("\"secret\":\"" + password + "\"")));
        verify(getRequestedFor(urlEqualTo("/api/nginx/proxy-hosts"))
                .withHeader("Authorization", equalTo("Bearer jwt-token")));
    }

    @Test
    public void testInvalidCredentialsThrowUnauthorized() throws Exception {
        stubSuccessfulLogin();
        wireMockRule.stubFor(post(urlEqualTo("/api/tokens"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withStatus(401)
                        .withBody("{\"error\":{\"code\":401,\"message\":\"Invalid email or password\"}}")));
        final NpmClient badClient = new NpmClient(baseUrl, user, "wrongpassword", false, 5);
        try {
            badClient.listProxyHosts(null);
            fail("Expected a ServerApiException for invalid NPM credentials");
        } catch (final ServerApiException e) {
            assertEquals(ApiErrorCode.UNAUTHORIZED, e.getErrorCode());
        }
    }

    @Test
    public void testReAuthenticatesOnUnexpected401() {
        wireMockRule.stubFor(get(urlEqualTo("/api/nginx/proxy-hosts"))
                .inScenario("auth-retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("retried"));
        wireMockRule.stubFor(get(urlEqualTo("/api/nginx/proxy-hosts"))
                .inScenario("auth-retry")
                .whenScenarioStateIs("retried")
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withStatus(200)
                        .withBody("[]")));
        final List<NpmProxyHost> hosts = client.listProxyHosts(null);
        assertNotNull(hosts);
        assertTrue(hosts.isEmpty());
        // one login for the initial token and one for the forced re-authentication
        verify(2, postRequestedFor(urlEqualTo("/api/tokens")));
    }

    ///////////////////////////////////////////////////////////
    //////////////////// Proxy hosts //////////////////////////
    ///////////////////////////////////////////////////////////

    @Test
    public void testCreateProxyHostReturnsCreatedHost() {
        wireMockRule.stubFor(post(urlEqualTo("/api/nginx/proxy-hosts"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withStatus(201)
                        .withBody("{\"id\": 5, \"domain_names\": [\"my-web.cloud.company.com\"], \"forward_scheme\": \"http\","
                                + " \"forward_host\": \"10.0.0.5\", \"forward_port\": 8080, \"certificate_id\": 3,"
                                + " \"ssl_forced\": false, \"enabled\": true, \"meta\": {\"nginx_online\": true}}")));
        final NpmProxyHost created = client.createProxyHost(createTestProxyHost());
        assertNotNull(created);
        assertEquals(Long.valueOf(5), created.getId());
        assertEquals(Integer.valueOf(8080), created.getForwardPort());
        assertNotNull(created.getMeta());
        assertEquals(Boolean.TRUE, created.getMeta().get("nginx_online"));
        verify(postRequestedFor(urlEqualTo("/api/nginx/proxy-hosts"))
                .withHeader("Authorization", equalTo("Bearer jwt-token"))
                .withRequestBody(containing("\"domain_names\":[\"my-web.cloud.company.com\"]"))
                .withRequestBody(containing("\"forward_scheme\":\"http\""))
                .withRequestBody(containing("\"forward_host\":\"10.0.0.5\""))
                .withRequestBody(containing("\"forward_port\":8080"))
                .withRequestBody(containing("\"certificate_id\":3")));
    }

    @Test
    public void testCreateProxyHostDuplicateDomainThrowsParamError() {
        wireMockRule.stubFor(post(urlEqualTo("/api/nginx/proxy-hosts"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withStatus(400)
                        .withBody("{\"error\":{\"code\":400,\"message\":\"my-web.cloud.company.com is already in use\"}}")));
        try {
            client.createProxyHost(createTestProxyHost());
            fail("Expected a ServerApiException for a duplicate domain");
        } catch (final ServerApiException e) {
            assertEquals(ApiErrorCode.PARAM_ERROR, e.getErrorCode());
            Assert.assertTrue(e.getMessage().contains("already in use"));
        }
    }

    @Test
    public void testFindProxyHostByDomainMatchesExactly() {
        wireMockRule.stubFor(get(urlEqualTo("/api/nginx/proxy-hosts?query=my-web.cloud.company.com"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withStatus(200)
                        .withBody("[{\"id\": 1, \"domain_names\": [\"my-web.cloud.company.com\"]},"
                                + " {\"id\": 2, \"domain_names\": [\"my-web2.cloud.company.com\"]}]")));
        wireMockRule.stubFor(get(urlEqualTo("/api/nginx/proxy-hosts?query=other.cloud.company.com"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withStatus(200)
                        .withBody("[]")));
        final NpmProxyHost match = client.findProxyHostByDomain("my-web.cloud.company.com");
        assertNotNull(match);
        assertEquals(Long.valueOf(1), match.getId());
        assertNull(client.findProxyHostByDomain("other.cloud.company.com"));
    }

    @Test
    public void testDeleteProxyHostToleratesNotFound() {
        wireMockRule.stubFor(com.github.tomakehurst.wiremock.client.WireMock.delete(urlEqualTo("/api/nginx/proxy-hosts/7"))
                .willReturn(aResponse().withStatus(200).withBody("true")));
        assertTrue(client.deleteProxyHost(7));
        wireMockRule.stubFor(com.github.tomakehurst.wiremock.client.WireMock.delete(urlEqualTo("/api/nginx/proxy-hosts/8"))
                .willReturn(aResponse().withStatus(404)
                        .withBody("{\"error\":{\"code\":404,\"message\":\"Not found\"}}")));
        assertTrue(client.deleteProxyHost(8));
        verify(deleteRequestedFor(urlEqualTo("/api/nginx/proxy-hosts/7")));
        verify(deleteRequestedFor(urlEqualTo("/api/nginx/proxy-hosts/8")));
    }

    ///////////////////////////////////////////////////////////
    //////////////////// Certificates /////////////////////////
    ///////////////////////////////////////////////////////////

    @Test
    public void testFindCertificateForWildcardDomain() {
        wireMockRule.stubFor(get(urlEqualTo("/api/nginx/certificates"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withStatus(200)
                        .withBody("[{\"id\": 1, \"provider\": \"letsencrypt\", \"domain_names\": [\"site.example.com\"]},"
                                + " {\"id\": 2, \"provider\": \"letsencrypt\", \"domain_names\": [\"*.cloud.company.com\"]}]")));
        final NpmCertificate certificate = client.findCertificateForWildcardDomain("*.cloud.company.com");
        assertNotNull(certificate);
        assertEquals(Long.valueOf(2), certificate.getId());
        assertNull(client.findCertificateForWildcardDomain("*.other.example.com"));
    }

    @Test
    public void testFindCertificateWithMultipleMatchesReturnsNull() {
        wireMockRule.stubFor(get(urlEqualTo("/api/nginx/certificates"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withStatus(200)
                        .withBody("[{\"id\": 1, \"domain_names\": [\"*.cloud.company.com\"]},"
                                + " {\"id\": 2, \"domain_names\": [\"*.cloud.company.com\"]}]")));
        assertNull(client.findCertificateForWildcardDomain("*.cloud.company.com"));
    }

    @Test
    public void testApiUrlAppendsApiPath() {
        wireMockRule.stubFor(get(urlEqualTo("/api/nginx/proxy-hosts"))
                .willReturn(aResponse().withStatus(200).withBody("[]")));
        client.listProxyHosts(null);
        // implicit verification: the request above was served, meaning the path /api was appended
        verify(getRequestedFor(urlEqualTo("/api/nginx/proxy-hosts")));
    }

    @Test
    public void testProxyHostCreatePayloadOmitsNullFields() throws Exception {
        final java.util.Set<String> expected = new java.util.HashSet<>(Arrays.asList(
                "domain_names", "forward_scheme", "forward_host", "forward_port", "certificate_id", "ssl_forced", "enabled"));
        final String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(createTestProxyHost());
        final com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        final java.util.Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        // null fields must not be serialized: NPM rejects unknown/extra properties
        assertEquals(expected, actual);
    }
}
