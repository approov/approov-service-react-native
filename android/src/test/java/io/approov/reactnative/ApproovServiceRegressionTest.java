package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.AssetManager;

import com.criticalblue.approovsdk.Approov;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.modules.network.NetworkingModule;
import com.facebook.react.modules.network.OkHttpClientFactory;
import com.facebook.react.modules.network.OkHttpClientProvider;
import com.facebook.react.bridge.ReactApplicationContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

import java.util.HashMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

public class ApproovServiceRegressionTest {

    private ReactApplicationContext reactContext;
    private MockedStatic<NetworkingModule> networkingModuleStatic;

    @Before
    public void setUp() throws Exception {
        reactContext = mock(ReactApplicationContext.class);
        AssetManager assetManager = mock(AssetManager.class);
        when(reactContext.getAssets()).thenReturn(assetManager);
        when(assetManager.open(anyString())).thenThrow(new IOException("missing"));

        networkingModuleStatic = mockStatic(NetworkingModule.class);
    }

    @After
    public void tearDown() {
        networkingModuleStatic.close();
    }

    private ApproovService newService() {
        return new ApproovService(reactContext);
    }

    private static String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String readRequestBody(BufferedReader reader, int contentLength) throws IOException {
        char[] chars = new char[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = reader.read(chars, offset, contentLength - offset);
            if (read == -1) {
                break;
            }
            offset += read;
        }
        return new String(chars, 0, offset);
    }

    @Test
    public void fetchWithApproovRejectsNonStringMethods() {
        ApproovService service = newService();
        ReadableMap options = mock(ReadableMap.class);
        Promise promise = mock(Promise.class);

        when(options.hasKey("method")).thenReturn(true);
        when(options.getType("method")).thenReturn(ReadableType.Boolean);

        service.fetchWithApproov("http://localhost/test", options, promise);

        verify(promise, timeout(2000))
            .reject("bad_request", "fetchWithApproov method must be a string when provided");
    }

    @Test
    public void fetchWithApproovRejectsNonStringBodies() {
        ApproovService service = newService();
        ReadableMap options = mock(ReadableMap.class);
        Promise promise = mock(Promise.class);

        when(options.hasKey("body")).thenReturn(true);
        when(options.getType("body")).thenReturn(ReadableType.Boolean);

        service.fetchWithApproov("http://localhost/test", options, promise);

        verify(promise, timeout(2000))
            .reject("bad_request", "fetchWithApproov body must be a string when provided");
    }

    @Test
    public void fetchWithApproovRejectsNonObjectHeaders() {
        ApproovService service = newService();
        ReadableMap options = mock(ReadableMap.class);
        Promise promise = mock(Promise.class);

        when(options.hasKey("headers")).thenReturn(true);
        when(options.getType("headers")).thenReturn(ReadableType.Boolean);

        service.fetchWithApproov("http://localhost/test", options, promise);

        verify(promise, timeout(2000))
            .reject("bad_request", "fetchWithApproov headers must be an object when provided");
    }

    @Test
    public void fetchWithApproovRejectsNonStringHeaderValues() {
        ApproovService service = newService();
        ReadableMap options = mock(ReadableMap.class);
        Promise promise = mock(Promise.class);
        ReadableMap headers = mock(ReadableMap.class);

        when(options.hasKey("headers")).thenReturn(true);
        when(options.getType("headers")).thenReturn(ReadableType.Map);
        when(options.getMap("headers")).thenReturn(headers);

        HashMap<String, Object> headersMap = new HashMap<>();
        headersMap.put("X-Bad-Header", true);
        when(headers.toHashMap()).thenReturn(headersMap);

        service.fetchWithApproov("http://localhost/test", options, promise);

        verify(promise, timeout(2000))
            .reject("bad_request", "fetchWithApproov header values must be strings");
    }

    @Test
    public void fetchWithApproovPreservesPostBodiesForLocalRequests() throws Exception {
        ApproovService service = newService();
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("localhost", 0));
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        CountDownLatch serverLatch = new CountDownLatch(1);
        CountDownLatch serverReady = new CountDownLatch(1);

        Thread serverThread = new Thread(() -> {
            try (ServerSocket ignored = serverSocket; Socket socket = serverSocket.accept()) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                String requestLine = reader.readLine();
                requestMethod.set(requestLine.split(" ")[0]);
                int contentLength = 0;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                    }
                }
                requestBody.set(readRequestBody(reader, contentLength));

                byte[] response = "native-ok".getBytes(StandardCharsets.UTF_8);
                String headers = "HTTP/1.1 201 Created\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + response.length + "\r\n"
                    + "Connection: close\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().write(response);
                socket.getOutputStream().flush();
                serverLatch.countDown();
            } catch (IOException ignored) {
            }
        });
        serverThread.start();
        serverReady.countDown();

        try {
            assertTrue(serverReady.await(1, TimeUnit.SECONDS));
            ReadableMap options = mock(ReadableMap.class);
            when(options.hasKey("method")).thenReturn(true);
            when(options.getType("method")).thenReturn(ReadableType.String);
            when(options.getString("method")).thenReturn("POST");
            when(options.hasKey("body")).thenReturn(true);
            when(options.getType("body")).thenReturn(ReadableType.String);
            when(options.getString("body")).thenReturn("{\"hello\":\"world\"}");
            when(options.hasKey("headers")).thenReturn(false);

            Promise promise = mock(Promise.class);
            doAnswer(invocation -> {
                return null;
            }).when(promise).resolve(any());

            service.fetchWithApproov(
                "http://localhost:" + serverSocket.getLocalPort() + "/reply",
                options,
                promise
            );

            assertTrue("Request should reach the local test server",
                serverLatch.await(5, TimeUnit.SECONDS));
            assertEquals("POST", requestMethod.get());
            assertEquals("{\"hello\":\"world\"}", requestBody.get());
        } finally {
            serverSocket.close();
            serverThread.join(5000);
        }
    }

    @Test
    public void initializeWithEmptyConfigMarksLayerInitializedWithoutApproovSdkCalls() {
        ApproovService service = newService();
        Promise promise = mock(Promise.class);

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            service.initialize("", promise);

            verify(promise, timeout(2000)).resolve(null);
            assertTrue(service.isInitialized());
            assertFalse(service.isApproovEnabled());
            approov.verifyNoInteractions();
        }
    }

    @Test
    public void updateClientFactoryWrapExistingStripsDuplicateApproovInterceptors() {
        ApproovService service = newService();
        Promise promise = mock(Promise.class);
        NetworkingModule networkingModule = mock(NetworkingModule.class);
        when(reactContext.getNativeModule(NetworkingModule.class)).thenReturn(networkingModule);

        Interceptor extraInterceptor = chain -> chain.proceed(chain.request());
        OkHttpClient existingClient = new OkHttpClient.Builder()
            .addInterceptor(new ApproovInterceptor(service))
            .addInterceptor(extraInterceptor)
            .build();

        AtomicReference<OkHttpClientFactory> capturedFactory = new AtomicReference<>();
        try (MockedStatic<OkHttpClientProvider> okHttpClientProvider = mockStatic(OkHttpClientProvider.class)) {
            okHttpClientProvider.when(OkHttpClientProvider::getOkHttpClient).thenReturn(existingClient);
            okHttpClientProvider
                .when(() -> OkHttpClientProvider.setOkHttpClientFactory((OkHttpClientFactory) any()))
                .thenAnswer(invocation -> {
                    capturedFactory.set(invocation.getArgument(0));
                    return null;
                });

            service.updateClientFactory(true, promise);

            verify(promise).resolve(true);
            assertNotNull("updateClientFactory should publish a recovered client factory", capturedFactory.get());

            OkHttpClient recoveredClient = capturedFactory.get().createNewNetworkModuleClient();
            long approovInterceptors = recoveredClient.interceptors().stream()
                .filter(interceptor -> interceptor instanceof ApproovInterceptor)
                .count();

            assertEquals(1, approovInterceptors);
            assertTrue(recoveredClient.interceptors().contains(extraInterceptor));
        }
    }

    @Test
    public void fetchExposesStatusHeaderWhenTokenMissingAndAllowed() throws Exception {
        ApproovService service = newService();
        service.setUseApproovStatusIfNoTokenInternal(true);

        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        AtomicReference<String> tokenHeader = new AtomicReference<>();
        CountDownLatch serverLatch = new CountDownLatch(1);

        Thread serverThread = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colonIndex = line.indexOf(":");
                    if (colonIndex != -1) {
                        String headerName = line.substring(0, colonIndex).trim();
                        if (headerName.equalsIgnoreCase("Approov-Token")) {
                            tokenHeader.set(line.substring(colonIndex + 1).trim());
                        }
                    }
                }

                byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
                String headers = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + response.length + "\r\n"
                    + "Connection: close\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().write(response);
                socket.getOutputStream().flush();
                serverLatch.countDown();
            } catch (IOException ignored) {
            }
        });
        serverThread.start();

        try (MockedStatic<Approov> approov = mockStatic(Approov.class);
             MockedStatic<OkHttpClientProvider> okHttpClientProvider = mockStatic(OkHttpClientProvider.class)) {
            
            // Synchronously initialize the static state for the service
            java.lang.reflect.Field initField = ApproovService.class.getDeclaredField("isInitialized");
            initField.setAccessible(true);
            initField.set(null, true);

            java.lang.reflect.Field configField = ApproovService.class.getDeclaredField("initialConfig");
            configField.setAccessible(true);
            configField.set(null, "dummy-config");

            Approov.TokenFetchResult mitmResult = mock(Approov.TokenFetchResult.class);
            when(mitmResult.getStatus()).thenReturn(Approov.TokenFetchStatus.MITM_DETECTED);
            when(mitmResult.getToken()).thenReturn("");
            when(mitmResult.getLoggableToken()).thenReturn("MITM_DETECTED");
            when(mitmResult.isConfigChanged()).thenReturn(false);
            when(mitmResult.isForceApplyPins()).thenReturn(false);

            final String url1 = "http://127.0.0.1:" + serverSocket.getLocalPort() + "/data";
            approov.when(() -> Approov.fetchApproovTokenAndWait(url1)).thenReturn(mitmResult);

            // Configure OkHttpClient to resolve example.com to 127.0.0.1 for the test
            OkHttpClient testClient = new OkHttpClient.Builder()
                .dns(hostname -> {
                    if (hostname.equals("api.example.com")) {
                        return java.util.Collections.singletonList(java.net.InetAddress.getByName("127.0.0.1"));
                    }
                    return okhttp3.Dns.SYSTEM.lookup(hostname);
                })
                .addInterceptor(new ApproovInterceptor(service))
                .build();
            okHttpClientProvider.when(OkHttpClientProvider::getOkHttpClient).thenReturn(testClient);
            
            final String url2 = "http://api.example.com:" + serverSocket.getLocalPort() + "/data";
            approov.when(() -> Approov.fetchApproovTokenAndWait(url2)).thenReturn(mitmResult);

            // Configure a custom mutator that allows proceeding on MITM
            ApproovService.setServiceMutator(new ApproovServiceMutator() {
                @Override
                public boolean handleInterceptorFetchTokenResult(ApproovService service,
                        Approov.TokenFetchResult approovResults, String url) throws ApproovException {
                    if (approovResults.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED) {
                        return true;
                    }
                    return ApproovServiceMutator.super.handleInterceptorFetchTokenResult(service, approovResults, url);
                }
            });

            ReadableMap options = mock(ReadableMap.class);
            when(options.hasKey("method")).thenReturn(false);
            when(options.hasKey("headers")).thenReturn(false);
            when(options.hasKey("body")).thenReturn(false);

            Promise promise = mock(Promise.class);
            service.fetchWithApproov(url2, options, promise);

            assertTrue("Request should reach the local test server",
                serverLatch.await(5, TimeUnit.SECONDS));
            assertEquals("Bearer MITM_DETECTED", tokenHeader.get());
        } finally {
            serverSocket.close();
            serverThread.join(5000);
            ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        }
    }
}
