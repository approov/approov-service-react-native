package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import com.criticalblue.approovsdk.Approov;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * OkHttp follows a redirect after the application interceptors ran and builds the follow-up from
 * the previous request's headers, so the token, trace ID, substituted secrets and signature added
 * for the first host would be sent to the redirect target. Approov credentials must never cross
 * hosts: on a follow-up to another host every header Approov added or changed is removed. The
 * follow-up is not protected again, and a redirect within the same host is left as it is
 * (core-service-layers-testing TESTING_REQUIREMENTS, "Approov Credentials Never Cross Hosts").
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ApproovCrossHostRedirectTest {
    private static final String PROTECTED = "protected.test";
    private static final String OTHER_PROTECTED = "other-protected.test";
    private static final String UNPROTECTED = "unprotected.test";

    private SSLServerSocket server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    // each request head the server received, in order of arrival
    private final List<Map<String, String>> received = Collections.synchronizedList(new ArrayList<>());
    // path -> response head; anything else answers 200
    private final Map<String, String> replies = new ConcurrentHashMap<>();
    private MockedStatic<Approov> sdk;
    private ApproovService service;
    private OkHttpClient client;

    @Before
    public void setUp() throws Exception {
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        // a local TLS server answering for every test host (the mini-SDK only issues tokens
        // for https URLs); the test-only certificate names localhost, so the client accepts
        // the synthetic host names
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream("/pinning-test.p12")) {
            assertNotNull(in);
            keys.load(in, "test-password".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "test-password".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keys);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
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

        // the mini-SDK protects two of the hosts and holds the secure string for the placeholder
        MiniSdkHarness.loadScenario("\"protectedDomains\": [\"" + PROTECTED + "\", \"" + OTHER_PROTECTED + "\"],"
                + "\"initialSecureStrings\": {\"placeholder-key\": \"real-secret\"}");
        service = MiniSdkHarness.initializedService(MiniSdkHarness.reactContext());
        service.addSubstitutionHeader("Api-Key", "");
        // every SDK call from here on is answered by the mini-SDK and recorded
        sdk = mockStatic(Approov.class, CALLS_REAL_METHODS);

        Dns local = hostname -> Collections.singletonList(InetAddress.getByName("127.0.0.1"));
        java.util.Set<String> testHosts = new java.util.HashSet<>(java.util.Arrays.asList(PROTECTED, OTHER_PROTECTED, UNPROTECTED));
        client = new OkHttpClient.Builder()
                .dns(local)
                .sslSocketFactory(tls.getSocketFactory(), (X509TrustManager) tmf.getTrustManagers()[0])
                .hostnameVerifier((hostname, session) -> testHosts.contains(hostname))
                .callTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(new ApproovInterceptor(service))
                .addNetworkInterceptor(service.getPinningInterceptor())
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
        MiniSdkHarness.tearDown();
    }

    // the host a mini-SDK token was issued for (its aud claim)
    private static String audienceOf(String jwt) throws Exception {
        assertNotNull("a token should be present", jwt);
        String[] parts = jwt.split("\\.");
        assertEquals("a JWT has three parts", 3, parts.length);
        String body = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return new org.json.JSONObject(body).getString("aud");
    }

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
                    String name = h.substring(0, c).trim().toLowerCase();
                    String value = h.substring(c + 1).trim();
                    head.merge(name, value, (a, b) -> a + "," + b);
                }
                received.add(head);
                String path = line.split(" ")[1];
                String reply = replies.getOrDefault(path, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
                out.write(reply.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException closed) {
            // connection closed
        }
    }

    private String url(String host, String path) {
        return "https://" + host + ":" + server.getLocalPort() + path;
    }

    private void redirect(String path, String location) {
        replies.put(path, "HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\n\r\n");
    }

    private Response call(Request request) throws IOException {
        return client.newCall(request).execute();
    }

    private Request get(String host, String path) {
        return new Request.Builder().url(url(host, path)).header("Api-Key", "placeholder-key").build();
    }

    private static void assertNoApproovCredentials(Map<String, String> head) {
        assertNull("no Approov token may cross hosts", head.get("approov-token"));
        assertNull("no Approov trace ID may cross hosts", head.get("approov-traceid"));
        assertNull("no signature may cross hosts", head.get("signature"));
        assertNull("no substituted secret may cross hosts", head.get("api-key"));
    }

    @Test
    public void redirectToUnprotectedHostCarriesNoApproovCredentials() throws Exception {
        redirect("/start", url(UNPROTECTED, "/end"));
        try (Response r = call(get(PROTECTED, "/start"))) {
            assertEquals(200, r.code());
        }

        assertEquals(2, received.size());
        assertEquals(PROTECTED, audienceOf(received.get(0).get("approov-token")));
        assertEquals("real-secret", received.get(0).get("api-key"));
        assertEquals(UNPROTECTED + ":" + server.getLocalPort(), received.get(1).get("host"));
        assertNoApproovCredentials(received.get(1));
    }

    @Test
    public void redirectToAnotherProtectedHostIsNotProtectedAgain() throws Exception {
        redirect("/start", url(OTHER_PROTECTED, "/end"));
        try (Response r = call(get(PROTECTED, "/start"))) {
            assertEquals(200, r.code());
        }

        assertNoApproovCredentials(received.get(1));
        sdk.verify(() -> Approov.fetchApproovTokenAndWait(anyString()), times(1));
    }

    @Test
    public void sameHostRedirectIsLeftAsItIs() throws Exception {
        redirect("/start", url(PROTECTED, "/end"));
        try (Response r = call(get(PROTECTED, "/start"))) {
            assertEquals(200, r.code());
        }

        assertEquals("the same-host follow-up keeps the token", received.get(0).get("approov-token"), received.get(1).get("approov-token"));
        assertEquals(received.get(0).get("approov-traceid"), received.get(1).get("approov-traceid"));
        assertEquals("real-secret", received.get(1).get("api-key"));
        sdk.verify(() -> Approov.fetchApproovTokenAndWait(anyString()), times(1));
    }

    @Test
    public void valuesAppendedAfterApproovDoNotKeepItsHeadersAcrossHosts() throws Exception {
        // an interceptor added after Approov appends its own values to headers Approov set
        client = client.newBuilder()
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                        .addHeader("Api-Key", "extra")
                        .addHeader("Approov-Token", "extra")
                        .build()))
                .build();
        redirect("/start", url(UNPROTECTED, "/end"));
        try (Response r = call(get(PROTECTED, "/start"))) {
            assertEquals(200, r.code());
        }

        assertTrue(received.get(0).get("api-key").contains("real-secret"));
        assertNoApproovCredentials(received.get(1));
    }

    @Test
    public void signatureForTheFirstHostDoesNotCrossHosts() throws Exception {
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(ApproovService s, Request request,
                    ApproovRequestMutations changes) {
                if (changes.getTokenHeaderKey() == null)
                    return request;
                return request.newBuilder()
                        .header("Signature", "sig-" + request.url().host())
                        .header("Signature-Input", "sig=()")
                        .build();
            }
        });
        redirect("/start", url(UNPROTECTED, "/end"));
        try (Response r = call(get(PROTECTED, "/start"))) {
            assertEquals(200, r.code());
        }

        assertEquals("sig-" + PROTECTED, received.get(0).get("signature"));
        assertNoApproovCredentials(received.get(1));
        assertNull(received.get(1).get("signature-input"));
    }

    @Test
    public void newCallFromResponseRequestDoesNotCarryCredentialsAcrossHosts() throws Exception {
        // the app retries a 401 with Response.request(), which carries what Approov applied,
        // and the retry is then redirected to another host
        replies.put("/auth", "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n");
        redirect("/auth-again", url(UNPROTECTED, "/end"));
        Request retry;
        try (Response r = call(get(PROTECTED, "/auth"))) {
            assertEquals(401, r.code());
            retry = r.request().newBuilder()
                    .url(url(PROTECTED, "/auth-again"))
                    .header("Authorization", "Bearer refreshed")
                    .build();
        }
        try (Response r = call(retry)) {
            assertEquals(200, r.code());
        }

        assertEquals(3, received.size());
        assertEquals(PROTECTED, audienceOf(received.get(1).get("approov-token")));
        assertNoApproovCredentials(received.get(2));
        assertEquals("Bearer refreshed", received.get(1).get("authorization"));
    }

    @Test
    public void newCallFromResponseRequestToAnotherHostCarriesNoCredentials() throws Exception {
        Request moved;
        try (Response r = call(get(PROTECTED, "/plain"))) {
            assertEquals(200, r.code());
            moved = r.request().newBuilder().url(url(UNPROTECTED, "/elsewhere")).build();
        }
        try (Response r = call(moved)) {
            assertEquals(200, r.code());
        }

        assertEquals(2, received.size());
        assertNoApproovCredentials(received.get(1));
    }

    @Test
    public void ordinaryRequestIsProtectedOnce() throws Exception {
        try (Response r = call(get(PROTECTED, "/plain"))) {
            assertEquals(200, r.code());
        }

        assertEquals(1, received.size());
        assertEquals(PROTECTED, audienceOf(received.get(0).get("approov-token")));
        assertEquals("real-secret", received.get(0).get("api-key"));
        sdk.verify(() -> Approov.fetchApproovTokenAndWait(anyString()), times(1));
    }
}
