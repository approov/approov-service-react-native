package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.criticalblue.approovsdk.Approov;

import java.net.InetAddress;
import java.net.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

/**
 * Approov credentials stay on the origin (scheme, host and port) they were issued for. A redirect
 * that changes only the scheme or the port must not carry them either. Runs without the mini-SDK:
 * the service is a mock that issues a fixed token for every https URL.
 */
public class ApproovRedirectOriginTest {
    private static final String HOST = "api.test";
    private static final String TOKEN = "issued-token";

    private MockedStatic<Approov> sdk;
    private ApproovService service;
    private MockWebServer tlsServer;
    private MockWebServer otherTlsServer;
    private MockWebServer plainServer;
    private OkHttpClient client;

    @Before
    public void setUp() throws Exception {
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        sdk = mockStatic(Approov.class);
        service = mock(ApproovService.class);
        when(service.isInitialized()).thenReturn(true);
        when(service.isApproovEnabled()).thenReturn(true);
        when(service.getTokenHeader()).thenReturn("Approov-Token");
        when(service.getTokenPrefix()).thenReturn("");
        Map<String, String> substitutions = new HashMap<>();
        substitutions.put("Api-Key", "");
        when(service.getSubstitutionHeaders()).thenReturn(substitutions);
        Approov.TokenFetchResult result = mock(Approov.TokenFetchResult.class);
        when(result.getStatus()).thenReturn(Approov.TokenFetchStatus.SUCCESS);
        when(result.getToken()).thenReturn(TOKEN);
        when(service.fetchApproovTokenAndWait(anyString())).thenReturn(result);
        // the SDK does not protect cleartext URLs
        Approov.TokenFetchResult unprotected = mock(Approov.TokenFetchResult.class);
        when(unprotected.getStatus()).thenReturn(Approov.TokenFetchStatus.UNKNOWN_URL);
        when(service.fetchApproovTokenAndWait(startsWith("http://"))).thenReturn(unprotected);
        Approov.TokenFetchResult secret = mock(Approov.TokenFetchResult.class);
        when(secret.getStatus()).thenReturn(Approov.TokenFetchStatus.SUCCESS);
        when(secret.getSecureString()).thenReturn("real-secret");
        sdk.when(() -> Approov.fetchSecureStringAndWait("placeholder-key", null)).thenReturn(secret);

        HeldCertificate certificate = new HeldCertificate.Builder().addSubjectAlternativeName(HOST).build();
        HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
                .heldCertificate(certificate).build();
        HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate()).build();
        tlsServer = new MockWebServer();
        tlsServer.useHttps(serverCertificates.sslSocketFactory(), false);
        tlsServer.start();
        otherTlsServer = new MockWebServer();
        otherTlsServer.useHttps(serverCertificates.sslSocketFactory(), false);
        otherTlsServer.start();
        plainServer = new MockWebServer();
        plainServer.start();

        Dns local = hostname -> Collections.singletonList(InetAddress.getByName("127.0.0.1"));
        client = new OkHttpClient.Builder()
                .dns(local)
                .proxy(Proxy.NO_PROXY)
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager())
                .callTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(new ApproovInterceptor(service))
                .addNetworkInterceptor(new ApproovPinningInterceptor())
                .build();
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
        for (MockWebServer server : new MockWebServer[] { tlsServer, otherTlsServer, plainServer }) {
            if (server != null)
                server.shutdown();
        }
        if (sdk != null)
            sdk.close();
    }

    private String url(MockWebServer server, String scheme, String path) {
        return scheme + "://" + HOST + ":" + server.getPort() + path;
    }

    private void redirectTo(String location) {
        tlsServer.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", location));
    }

    private Response call(String url) throws Exception {
        return client.newCall(new Request.Builder().url(url).header("Api-Key", "placeholder-key").build()).execute();
    }

    private static void assertNoApproovCredentials(RecordedRequest request) {
        assertNull("no Approov token may leave its origin", request.getHeader("Approov-Token"));
        assertNull("no substituted secret may leave its origin", request.getHeader("Api-Key"));
    }

    @Test
    public void redirectFromHttpsToHttpOnTheSameHostCarriesNoCredentials() throws Exception {
        redirectTo(url(plainServer, "http", "/end"));
        plainServer.enqueue(new MockResponse().setBody("ok"));
        try (Response r = call(url(tlsServer, "https", "/start"))) {
            assertEquals(200, r.code());
        }

        RecordedRequest first = tlsServer.takeRequest();
        assertEquals(TOKEN, first.getHeader("Approov-Token"));
        assertEquals("real-secret", first.getHeader("Api-Key"));
        assertNoApproovCredentials(plainServer.takeRequest());
    }

    @Test
    public void redirectToAnotherPortOnTheSameHostCarriesNoCredentials() throws Exception {
        redirectTo(url(otherTlsServer, "https", "/end"));
        otherTlsServer.enqueue(new MockResponse().setBody("ok"));
        try (Response r = call(url(tlsServer, "https", "/start"))) {
            assertEquals(200, r.code());
        }

        assertEquals(TOKEN, tlsServer.takeRequest().getHeader("Approov-Token"));
        assertNoApproovCredentials(otherTlsServer.takeRequest());
    }

    @Test
    public void redirectWithinTheSameOriginKeepsTheCredentials() throws Exception {
        redirectTo(url(tlsServer, "https", "/end"));
        tlsServer.enqueue(new MockResponse().setBody("ok"));
        try (Response r = call(url(tlsServer, "https", "/start"))) {
            assertEquals(200, r.code());
        }

        tlsServer.takeRequest();
        RecordedRequest second = tlsServer.takeRequest();
        assertEquals(TOKEN, second.getHeader("Approov-Token"));
        assertEquals("real-secret", second.getHeader("Api-Key"));
    }

    @Test
    public void headerTheAppReplacedIsKeptOnANewCallToAnotherOrigin() throws Exception {
        tlsServer.enqueue(new MockResponse().setBody("ok"));
        plainServer.enqueue(new MockResponse().setBody("ok"));
        Request moved;
        try (Response r = call(url(tlsServer, "https", "/plain"))) {
            assertEquals(200, r.code());
            // the app reuses the processed request for another origin and sets its own key
            moved = r.request().newBuilder()
                    .url(url(plainServer, "http", "/elsewhere"))
                    .header("Api-Key", "app-own-key")
                    .build();
        }
        try (Response r = client.newCall(moved).execute()) {
            assertEquals(200, r.code());
        }

        tlsServer.takeRequest();
        RecordedRequest second = plainServer.takeRequest();
        assertNull(second.getHeader("Approov-Token"));
        assertEquals("the app's own value is not Approov's to remove", "app-own-key", second.getHeader("Api-Key"));
    }

    @Test
    public void recordForAnotherOriginIsDroppedWhenNothingIsApplied() {
        Request issuedFor = new Request.Builder().url("https://" + HOST + "/").build();
        Request applied = issuedFor.newBuilder().header("Approov-Token", TOKEN).build();
        Request tagged = ApproovIssuedHeaders.tag(issuedFor, applied);

        Request moved = tagged.newBuilder().url("https://other.test/").removeHeader("Approov-Token").build();
        Request retagged = ApproovIssuedHeaders.tag(moved, moved);
        assertNull("a record for another origin must not stay on the request",
                retagged.tag(ApproovIssuedHeaders.class));
    }
}
