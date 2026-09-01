//Licensed to the Apache Software Foundation (ASF) under one
//or more contributor license agreements.  See the NOTICE file
//distributed with this work for additional information
//regarding copyright ownership.  The ASF licenses this file
//to you under the Apache License, Version 2.0 (the
//"License"); you may not use this file except in compliance
//the License.  You may obtain a copy of the License at
//
//http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing,
//software distributed under the License is distributed on an
//"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
//KIND, either express or implied.  See the License for the
//specific language governing permissions and limitations
//under the License.
package org.apache.cloudstack.oauth2.keycloak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.cloudstack.oauth2.dao.OauthProviderDao;
import org.apache.cloudstack.oauth2.vo.OauthProviderVO;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.cloud.exception.CloudAuthenticationException;
import com.cloud.utils.exception.CloudRuntimeException;

public class KeycloakOAuth2ProviderTest {

    @Mock
    private OauthProviderDao oauthProviderDao;

    @Mock
    private CloseableHttpClient httpClient;

    private KeycloakOAuth2Provider provider;

    private OauthProviderVO mockProviderVO;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        provider = new KeycloakOAuth2Provider(httpClient);
        provider.oauthProviderDao = oauthProviderDao;

        mockProviderVO = new OauthProviderVO();
        mockProviderVO.setClientId("test-client");
        mockProviderVO.setSecretKey("test-secret");
        mockProviderVO.setTokenUrl("http://localhost/token");
        mockProviderVO.setRedirectUri("http://localhost/redirect");
    }

    @Test
    public void testGetName() {
        assertEquals("keycloak", provider.getName());
    }

    @Test(expected = CloudAuthenticationException.class)
    public void testVerifyUserEmptyParams() {
        provider.verifyUser("", "");
    }

    @Test(expected = CloudAuthenticationException.class)
    public void testVerifyUserProviderNotFound() {
        when(oauthProviderDao.findByProvider("keycloak")).thenReturn(null);
        provider.verifyUser("test@example.com", "code123");
    }

    @Test(expected = CloudRuntimeException.class)
    public void testVerifyCodeAndFetchEmailHttpError() throws IOException {
        when(oauthProviderDao.findByProvider("keycloak")).thenReturn(mockProviderVO);

        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);

        when(statusLine.getStatusCode()).thenReturn(400);
        when(response.getStatusLine()).thenReturn(statusLine);

        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream("error".getBytes()));
        when(response.getEntity()).thenReturn(entity);

        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        provider.verifyCodeAndFetchEmail("invalid-code");
    }

    @Test(expected = CloudRuntimeException.class)
    public void testVerifyCodeAndFetchEmailNetworkFailure() throws IOException {
        when(oauthProviderDao.findByProvider("keycloak")).thenReturn(mockProviderVO);
        when(httpClient.execute(any(HttpPost.class))).thenThrow(new IOException("Connection refused"));

        provider.verifyCodeAndFetchEmail("code");
    }

    @Test(expected = CloudRuntimeException.class)
    public void testVerifyUserWithMismatchedEmail() throws IOException {
        when(oauthProviderDao.findByProvider("keycloak")).thenReturn(mockProviderVO);

        String testEmail = "anotheruser@example.com";
        String secretCode = "valid-auth-code";

        String header = "{\"alg\":\"none\"}";
        String payload = "{" +
                "\"aud\":[\"test-client\"]," +
                "\"email\":\"" + testEmail + "\"," +
                "\"iss\":\"http://keycloak\"," +
                "\"sub\":\"12345\"" +
                "}";

        String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes());
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes());
        String fakeJwt = encodedHeader + "." + encodedPayload + ".not-checked-signature";

        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        HttpEntity entity = mock(HttpEntity.class);

        when(statusLine.getStatusCode()).thenReturn(200);
        when(response.getStatusLine()).thenReturn(statusLine);

        String jsonResponseBody = "{\"id_token\":\"" + fakeJwt + "\", \"access_token\":\"acc-123\"}";
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(jsonResponseBody.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);

        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        provider.verifyUser("user@example.com", secretCode);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testVerifyUserWithMismatchedClient() throws IOException {
        when(oauthProviderDao.findByProvider("keycloak")).thenReturn(mockProviderVO);

        String testEmail = "anotheruser@example.com";
        String secretCode = "valid-auth-code";

        String header = "{\"alg\":\"none\"}";
        String payload = "{" +
                "\"aud\":[\"anothertest-client\"]," +
                "\"email\":\"" + testEmail + "\"," +
                "\"iss\":\"http://keycloak\"," +
                "\"sub\":\"12345\"" +
                "}";

        String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes());
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes());
        String fakeJwt = encodedHeader + "." + encodedPayload + ".not-checked-signature";

        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        HttpEntity entity = mock(HttpEntity.class);

        when(statusLine.getStatusCode()).thenReturn(200);
        when(response.getStatusLine()).thenReturn(statusLine);

        String jsonResponseBody = "{\"id_token\":\"" + fakeJwt + "\", \"access_token\":\"acc-123\"}";
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(jsonResponseBody.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);

        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        provider.verifyUser(testEmail, secretCode);
    }

    @Test
    public void testVerifyUserEmail() throws IOException {
        when(oauthProviderDao.findByProvider("keycloak")).thenReturn(mockProviderVO);

        String testEmail = "user@example.com";
        String secretCode = "valid-auth-code";

        String header = "{\"alg\":\"none\"}";
        String payload = "{" +
                "\"aud\":[\"test-client\"]," +
                "\"email\":\"" + testEmail + "\"," +
                "\"iss\":\"http://keycloak\"," +
                "\"sub\":\"12345\"" +
                "}";

        String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes());
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes());
        String fakeJwt = encodedHeader + "." + encodedPayload + ".not-checked-signature";

        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        HttpEntity entity = mock(HttpEntity.class);

        when(statusLine.getStatusCode()).thenReturn(200);
        when(response.getStatusLine()).thenReturn(statusLine);

        String jsonResponseBody = "{\"id_token\":\"" + fakeJwt + "\", \"access_token\":\"acc-123\"}";
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(jsonResponseBody.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);

        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        boolean result = provider.verifyUser(testEmail, secretCode);

        assertTrue("User successfully verified", result);
    }

    @Test
    public void testGetDescription() {
        assertEquals("Keycloak OAuth2 Provider Plugin", provider.getDescription());
    }

    private String buildFakeIdToken(String email, String audience) {
        String header = "{\"alg\":\"none\"}";
        String payload = "{" +
                "\"aud\":[\"" + audience + "\"]," +
                "\"email\":\"" + email + "\"," +
                "\"iss\":\"http://keycloak\"," +
                "\"sub\":\"12345\"" +
                "}";

        String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes());
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes());
        return encodedHeader + "." + encodedPayload + ".not-checked-signature";
    }

    private CloseableHttpResponse mockSuccessfulTokenResponse(String idToken) throws IOException {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        HttpEntity entity = mock(HttpEntity.class);

        when(statusLine.getStatusCode()).thenReturn(200);
        when(response.getStatusLine()).thenReturn(statusLine);

        String jsonResponseBody = "{\"id_token\":\"" + idToken + "\", \"access_token\":\"acc-123\"}";
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(jsonResponseBody.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);

        return response;
    }

    @Test
    public void testVerifyCodeAndFetchEmailUsesCacheForSameCode() throws IOException {
        when(oauthProviderDao.findByProvider("keycloak")).thenReturn(mockProviderVO);

        String testEmail = "user@example.com";
        CloseableHttpResponse response = mockSuccessfulTokenResponse(buildFakeIdToken(testEmail, "test-client"));
        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        String firstEmail = provider.verifyCodeAndFetchEmail("valid-auth-code");
        String secondEmail = provider.verifyCodeAndFetchEmail("valid-auth-code");

        assertEquals("First call should return the email from the id_token", testEmail, firstEmail);
        assertEquals("Same code should be served from the cache", testEmail, secondEmail);
        verify(httpClient, times(1)).execute(any(HttpPost.class));
    }

    @Test
    public void testSecondCodeReturnsFreshEmail() throws IOException {
        when(oauthProviderDao.findByProvider("keycloak")).thenReturn(mockProviderVO);

        String firstEmail = "first.user@example.com";
        String secondEmail = "second.user@example.com";
        CloseableHttpResponse firstResponse = mockSuccessfulTokenResponse(buildFakeIdToken(firstEmail, "test-client"));
        CloseableHttpResponse secondResponse = mockSuccessfulTokenResponse(buildFakeIdToken(secondEmail, "test-client"));
        when(httpClient.execute(any(HttpPost.class))).thenReturn(firstResponse).thenReturn(secondResponse);

        String first = provider.verifyCodeAndFetchEmail("first-auth-code");
        String second = provider.verifyCodeAndFetchEmail("second-auth-code");

        assertEquals("First code should return the email of its own token", firstEmail, first);
        assertEquals("Second code must not be served a stale cached email", secondEmail, second);
        verify(httpClient, times(2)).execute(any(HttpPost.class));
    }

    @Test(expected = CloudRuntimeException.class)
    public void testVerifyCodeAndFetchEmailWithoutEmailClaim() throws IOException {
        when(oauthProviderDao.findByProvider("keycloak")).thenReturn(mockProviderVO);

        String header = "{\"alg\":\"none\"}";
        String payload = "{" +
                "\"aud\":[\"test-client\"]," +
                "\"iss\":\"http://keycloak\"," +
                "\"sub\":\"12345\"" +
                "}";

        String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes());
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes());
        String fakeJwt = encodedHeader + "." + encodedPayload + ".not-checked-signature";

        CloseableHttpResponse response = mockSuccessfulTokenResponse(fakeJwt);
        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);

        provider.verifyCodeAndFetchEmail("valid-auth-code");
    }
}
