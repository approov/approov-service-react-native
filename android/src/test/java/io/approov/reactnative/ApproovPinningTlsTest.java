package io.approov.reactnative;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.criticalblue.approovsdk.Approov;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

/** Uses a local TLS server and a test-only certificate; no external service or permissive trust manager. */
public class ApproovPinningTlsTest {
    private SSLServerSocket server;
    private final ExecutorService serverThreads = Executors.newCachedThreadPool();
    private final List<SSLSocket> connections = Collections.synchronizedList(new ArrayList<>());
    private OkHttpClient client;
    private ApproovService service;
    private ApproovPinningInterceptor pinning;
    private MockedStatic<Approov> sdk;
    private String matchingPin;
    private final List<Integer> remotePorts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger targetRequests = new AtomicInteger();
    private static final String WRONG_PIN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Before
    public void setUp() throws Exception {
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        sdk = mockStatic(Approov.class);
        service = mock(ApproovService.class);
        when(service.isApproovEnabled()).thenReturn(true);
        pinning = new ApproovPinningInterceptor();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = getClass().getResourceAsStream("/pinning-test.p12")) {
            assertNotNull(input);
            keys.load(input, "test-password".toCharArray());
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
        serverThreads.execute(() -> {
            while (!server.isClosed()) {
                try {
                    SSLSocket socket = (SSLSocket) server.accept();
                    connections.add(socket);
                    serverThreads.execute(() -> serve(socket));
                } catch (IOException stopped) {
                    return;
                }
            }
        });
        client = new OkHttpClient.Builder()
                .sslSocketFactory(tls.getSocketFactory(), (X509TrustManager) tmf.getTrustManagers()[0])
                .callTimeout(5, TimeUnit.SECONDS)
                .addNetworkInterceptor(pinning)
                .build();
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
        if (server != null) server.close();
        synchronized (connections) {
            for (SSLSocket socket : connections) socket.close();
        }
        serverThreads.shutdownNow();
        assertTrue(serverThreads.awaitTermination(5, TimeUnit.SECONDS));
        if (sdk != null) sdk.close();
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
    }

    // Minimal HTTP/1.1 responder retaining TLS sockets for the connection-reuse regression.
    private void serve(SSLSocket socket) {
        try (SSLSocket connection = socket) {
            socket.setSoTimeout(5000);
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream output = socket.getOutputStream();
            String requestLine;
            while ((requestLine = input.readLine()) != null) {
                String header;
                while ((header = input.readLine()) != null && !header.isEmpty()) { }
                remotePorts.add(socket.getPort());
                String response;
                if (requestLine.contains(" /redirect ")) {
                    response = "HTTP/1.1 302 Found\r\nLocation: " + url("127.0.0.1", "/target")
                            + "\r\nContent-Length: 0\r\n\r\n";
                } else {
                    targetRequests.incrementAndGet();
                    response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok";
                }
                output.write(response.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (IOException closed) {
            // A pinning mismatch closes the connection before sending an HTTP request.
        }
    }

    private String url(String host, String path) {
        return "https://" + host + ":" + server.getLocalPort() + path;
    }

    private void pins(Map<String, List<String>> pins) {
        sdk.when(() -> Approov.getPins("public-key-sha256")).thenReturn(pins);
        pinning.rebuildPins(service);
    }

    private void get(String host, String path) throws Exception {
        try (Response response = client.newCall(new Request.Builder().url(url(host, path)).build()).execute()) {
            assertEquals(200, response.code());
            assertEquals("ok", response.body().string());
        }
    }

    @Test
    public void existingClientAndPooledConnectionUseNewPins() throws Exception {
        pins(Collections.singletonMap("localhost", Collections.singletonList(matchingPin)));
        get("localhost", "/target");
        get("localhost", "/target");
        assertEquals("test must exercise connection reuse", remotePorts.get(0), remotePorts.get(1));
        pins(Collections.singletonMap("localhost", Collections.singletonList(WRONG_PIN)));
        assertThrows(SSLPeerUnverifiedException.class, () -> get("localhost", "/target"));
        assertEquals("pin mismatch must block before HTTP is sent", 2, targetRequests.get());
        pins(Collections.emptyMap());
        get("localhost", "/target");
        assertEquals(3, targetRequests.get());
    }

    @Test
    public void redirectedHostMustPassItsOwnPinPolicy() throws Exception {
        Map<String, List<String>> pins = new HashMap<>();
        pins.put("localhost", Collections.singletonList(matchingPin));
        pins.put("127.0.0.1", Collections.singletonList(WRONG_PIN));
        pins(pins);
        assertThrows(SSLPeerUnverifiedException.class, () -> get("localhost", "/redirect"));
        assertEquals("only the redirecting request reaches the server", 1, remotePorts.size());
        assertEquals(0, targetRequests.get());
    }
}
