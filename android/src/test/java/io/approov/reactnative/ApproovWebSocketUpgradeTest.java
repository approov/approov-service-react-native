package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.criticalblue.approovsdk.Approov;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

/**
 * WebSockets are not supported. OkHttp runs application interceptors on a WebSocket upgrade
 * request but skips network interceptors, so Approov's pin check never runs for it. The
 * upgrade must therefore carry nothing Approov issues: no token, even when the host is a
 * protected API domain and the pins would reject the connection (core-service-layers-testing
 * TESTING_REQUIREMENTS, "WebSockets Are Not Supported").
 */
public class ApproovWebSocketUpgradeTest {
    private static final String WRONG_PIN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private SSLServerSocket server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final List<String> received = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch requestSeen = new CountDownLatch(1);
    private MockedStatic<Approov> sdk;
    private OkHttpClient client;
    private ApproovPinningInterceptor pinning;
    private ApproovService service;
    private String matchingPin;

    @Before
    public void setUp() throws Exception {
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        sdk = mockStatic(Approov.class);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream("/pinning-test.p12")) {
            assertNotNull(in);
            keys.load(in, "test-password".toCharArray());
        }
        matchingPin = CertificatePinner.pin(keys.getCertificate("localhost")).substring("sha256/".length());
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "test-password".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keys);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(0, 10,
                java.net.InetAddress.getByName("127.0.0.1"));
        threads.execute(() -> {
            while (!server.isClosed()) {
                try {
                    SSLSocket s = (SSLSocket) server.accept();
                    threads.execute(() -> serve(s));
                } catch (IOException stopped) {
                    return;
                }
            }
        });

        // 127.0.0.1 is not the localhost pass-through, so ordinary requests take the token path
        service = mock(ApproovService.class);
        when(service.isApproovEnabled()).thenReturn(true);
        when(service.isInitialized()).thenReturn(true);
        when(service.getTokenHeader()).thenReturn("Approov-Token");
        when(service.getTokenPrefix()).thenReturn("");
        when(service.getExclusionURLRegexs()).thenReturn(Collections.emptyMap());
        when(service.getSubstitutionHeaders()).thenReturn(Collections.emptyMap());
        when(service.getSubstitutionQueryParams()).thenReturn(Collections.emptyMap());
        Approov.TokenFetchResult result = mock(Approov.TokenFetchResult.class);
        when(result.getStatus()).thenReturn(Approov.TokenFetchStatus.SUCCESS);
        when(result.getToken()).thenReturn("jwt-token");
        when(service.fetchApproovTokenAndWait(anyString())).thenReturn(result);

        pinning = new ApproovPinningInterceptor();
        client = new OkHttpClient.Builder()
                .sslSocketFactory(tls.getSocketFactory(), (X509TrustManager) tmf.getTrustManagers()[0])
                .callTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(new ApproovInterceptor(service))
                .addNetworkInterceptor(pinning)
                .build();
    }

    @After
    public void tearDown() throws Exception {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
        server.close();
        threads.shutdownNow();
        sdk.close();
    }

    // records each request head, then refuses the upgrade (the headers are what matter here)
    private void serve(SSLSocket socket) {
        try (SSLSocket s = socket) {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            String line;
            while ((line = in.readLine()) != null) {
                StringBuilder head = new StringBuilder(line).append('\n');
                String h;
                while ((h = in.readLine()) != null && !h.isEmpty())
                    head.append(h).append('\n');
                received.add(head.toString());
                requestSeen.countDown();
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException closed) {
            // connection closed by the client
        }
    }

    private String url(String scheme) {
        return scheme + "://127.0.0.1:" + server.getLocalPort() + "/socket";
    }

    private void pin(String pin) {
        sdk.when(() -> Approov.getPins("public-key-sha256"))
                .thenReturn(Collections.singletonMap("127.0.0.1", Collections.singletonList(pin)));
        pinning.rebuildPins(service);
    }

    private String openWebSocketAndCaptureUpgrade() throws Exception {
        WebSocket ws = client.newWebSocket(new Request.Builder().url(url("wss")).build(), new WebSocketListener() { });
        try {
            assertTrue("the upgrade request should reach the server", requestSeen.await(5, TimeUnit.SECONDS));
        } finally {
            ws.cancel();
        }
        return received.get(0);
    }

    @Test
    public void ordinaryHttpsRequestCarriesTheToken() throws Exception {
        // control: proves this client would add the token if the upgrade were processed
        pin(matchingPin);
        try (Response r = client.newCall(new Request.Builder().url(url("https")).build()).execute()) {
            assertEquals(200, r.code());
        }
        assertTrue(received.get(0).contains("Approov-Token: jwt-token"));
    }

    @Test
    public void webSocketUpgradeCarriesNoTokenEvenWhenPinsWouldReject() throws Exception {
        pin(WRONG_PIN);
        String upgrade = openWebSocketAndCaptureUpgrade();
        assertTrue("this must be the upgrade request", upgrade.toLowerCase().contains("upgrade: websocket"));
        assertFalse("no Approov token may travel on an unpinned upgrade", upgrade.contains("Approov-Token"));
        verify(service, never()).fetchApproovTokenAndWait(anyString());
    }

    @Test
    public void webSocketUpgradeCarriesNoTokenWithMatchingPins() throws Exception {
        pin(matchingPin);
        String upgrade = openWebSocketAndCaptureUpgrade();
        assertFalse(upgrade.contains("Approov-Token"));
        verify(service, never()).fetchApproovTokenAndWait(anyString());
    }
}
