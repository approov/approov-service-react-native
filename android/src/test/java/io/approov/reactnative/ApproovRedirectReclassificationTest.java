package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.criticalblue.approovsdk.Approov;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

/**
 * OkHttp follows a redirect after the application interceptors ran and builds the follow-up from
 * the previous attempt's headers, so everything Approov added for the first host (token, trace ID,
 * substituted secrets, signature) would travel to the redirect target. A rebuilt attempt must be
 * reclassified for its own URL: Approov's additions are taken back out and the attempt is
 * processed afresh (core-service-layers-testing TESTING_REQUIREMENTS, "Redirect Followups Are
 * Reclassified").
 */
public class ApproovRedirectReclassificationTest {
    private static final String PROTECTED = "protected.test";
    private static final String OTHER_PROTECTED = "other-protected.test";
    private static final String UNPROTECTED = "unprotected.test";

    private ServerSocket server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    // each request head the server received, keyed by order of arrival
    private final List<Map<String, String>> received = Collections.synchronizedList(new ArrayList<>());
    private volatile String redirectTarget;
    private MockedStatic<Approov> sdk;
    private ApproovService service;
    private OkHttpClient client;

    @Before
    public void setUp() throws Exception {
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        threads.execute(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    threads.execute(() -> serve(s));
                } catch (IOException stopped) {
                    return;
                }
            }
        });
        sdk = mockStatic(Approov.class);
        sdk.when(() -> Approov.getPins("public-key-sha256")).thenReturn(Collections.emptyMap());
        Approov.TokenFetchResult secret = result(Approov.TokenFetchStatus.SUCCESS, null, "real-secret");
        sdk.when(() -> Approov.fetchSecureStringAndWait("placeholder-key", null)).thenReturn(secret);

        service = mock(ApproovService.class);
        when(service.isApproovEnabled()).thenReturn(true);
        when(service.isInitialized()).thenReturn(true);
        when(service.getTokenHeader()).thenReturn("Approov-Token");
        when(service.getTokenPrefix()).thenReturn("");
        when(service.getTraceIDHeader()).thenReturn("Approov-TraceID");
        when(service.getExclusionURLRegexs()).thenReturn(Collections.emptyMap());
        Map<String, String> substitution = new HashMap<>();
        substitution.put("Api-Key", "");
        when(service.getSubstitutionHeaders()).thenReturn(substitution);
        when(service.getSubstitutionQueryParams()).thenReturn(Collections.emptyMap());
        Approov.TokenFetchResult protectedToken = result(Approov.TokenFetchStatus.SUCCESS, "jwt-protected", null);
        Approov.TokenFetchResult otherToken = result(Approov.TokenFetchStatus.SUCCESS, "jwt-other", null);
        Approov.TokenFetchResult unknownUrl = result(Approov.TokenFetchStatus.UNKNOWN_URL, null, null);
        when(service.fetchApproovTokenAndWait(startsWith("http://" + PROTECTED))).thenReturn(protectedToken);
        when(service.fetchApproovTokenAndWait(startsWith("http://" + OTHER_PROTECTED))).thenReturn(otherToken);
        when(service.fetchApproovTokenAndWait(startsWith("http://" + UNPROTECTED))).thenReturn(unknownUrl);

        ApproovPinningInterceptor pinning = new ApproovPinningInterceptor();
        Dns local = hostname -> Collections.singletonList(InetAddress.getByName("127.0.0.1"));
        client = new OkHttpClient.Builder()
                .dns(local)
                .callTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(new ApproovInterceptor(service))
                .addNetworkInterceptor(pinning)
                .build();
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
        server.close();
        threads.shutdownNow();
        if (sdk != null)
            sdk.close();
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
    }

    private static Approov.TokenFetchResult result(Approov.TokenFetchStatus status, String token, String secure) {
        Approov.TokenFetchResult r = mock(Approov.TokenFetchResult.class);
        when(r.getStatus()).thenReturn(status);
        when(r.getToken()).thenReturn(token == null ? "" : token);
        when(r.getSecureString()).thenReturn(secure);
        when(r.getTraceID()).thenReturn(token == null ? null : "trace-" + token);
        when(r.getLoggableToken()).thenReturn("{}");
        return r;
    }

    // /start answers 302 to redirectTarget; anything else answers 200
    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            String line;
            while ((line = in.readLine()) != null) {
                Map<String, String> head = new LinkedHashMap<>();
                head.put(":line", line);
                String h;
                while ((h = in.readLine()) != null && !h.isEmpty()) {
                    int c = h.indexOf(':');
                    head.put(h.substring(0, c).trim().toLowerCase(), h.substring(c + 1).trim());
                }
                received.add(head);
                String response = line.startsWith("GET /start ")
                        ? "HTTP/1.1 302 Found\r\nLocation: " + redirectTarget + "\r\nContent-Length: 0\r\n\r\n"
                        : "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException closed) {
            // connection closed
        }
    }

    private String url(String host, String path) {
        return "http://" + host + ":" + server.getLocalPort() + path;
    }

    private void get(String host, String path, String apiKey) throws IOException {
        Request.Builder b = new Request.Builder().url(url(host, path));
        if (apiKey != null)
            b.header("Api-Key", apiKey);
        try (Response r = client.newCall(b.build()).execute()) {
            assertEquals(200, r.code());
        }
    }

    @Test
    public void redirectToUnprotectedHostCarriesNothingApproovAdded() throws Exception {
        redirectTarget = url(UNPROTECTED, "/end");
        get(PROTECTED, "/start", "placeholder-key");

        assertEquals(2, received.size());
        Map<String, String> first = received.get(0);
        assertEquals("the protected request carries its token", "jwt-protected", first.get("approov-token"));
        assertEquals("the protected request carries the substituted secret", "real-secret", first.get("api-key"));

        Map<String, String> followUp = received.get(1);
        assertEquals(UNPROTECTED + ":" + server.getLocalPort(), followUp.get("host"));
        assertNull("no Approov token may reach the unprotected host", followUp.get("approov-token"));
        assertNull("no Approov trace ID may reach the unprotected host", followUp.get("approov-traceid"));
        assertEquals("the secret must be put back to the app's placeholder", "placeholder-key", followUp.get("api-key"));
    }

    @Test
    public void redirectToAnotherProtectedHostGetsThatHostsToken() throws Exception {
        redirectTarget = url(OTHER_PROTECTED, "/end");
        get(PROTECTED, "/start", null);

        Map<String, String> followUp = received.get(1);
        assertEquals("the follow-up is protected for its own host", "jwt-other", followUp.get("approov-token"));
        assertEquals("trace-jwt-other", followUp.get("approov-traceid"));
    }

    @Test
    public void sameHostRedirectIsProtectedAgain() throws Exception {
        redirectTarget = url(PROTECTED, "/end");
        get(PROTECTED, "/start", null);

        assertEquals("jwt-protected", received.get(1).get("approov-token"));
    }

    @Test
    public void ordinaryRequestIsNotReprocessed() throws Exception {
        get(PROTECTED, "/plain", "placeholder-key");

        assertEquals(1, received.size());
        assertEquals("jwt-protected", received.get(0).get("approov-token"));
        assertEquals("real-secret", received.get(0).get("api-key"));
        assertTrue(received.get(0).get(":line").startsWith("GET /plain "));
        verify(service, times(1)).fetchApproovTokenAndWait(anyString());
    }

    @Test
    public void signatureForTheFirstHostDoesNotFollowTheRedirect() throws Exception {
        // stands in for message signing: the mutator signs every request it protects
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(ApproovService s, Request request,
                    ApproovRequestMutations changes) {
                if (changes.getTokenHeaderKey() == null)
                    return request;
                return request.newBuilder().header("Signature", "sig-" + request.url().host()).build();
            }
        });
        redirectTarget = url(UNPROTECTED, "/end");
        get(PROTECTED, "/start", null);

        assertEquals("sig-" + PROTECTED, received.get(0).get("signature"));
        assertNull("the first host's signature may not reach the unprotected host", received.get(1).get("signature"));

        received.clear();
        redirectTarget = url(OTHER_PROTECTED, "/end");
        get(PROTECTED, "/start", null);
        assertEquals("the follow-up is signed for its own host", "sig-" + OTHER_PROTECTED, received.get(1).get("signature"));
    }

    @Test
    public void redirectFromUnprotectedToProtectedHostIsProtected() throws Exception {
        redirectTarget = url(PROTECTED, "/end");
        get(UNPROTECTED, "/start", null);

        assertNull(received.get(0).get("approov-token"));
        assertEquals("jwt-protected", received.get(1).get("approov-token"));
    }
}
