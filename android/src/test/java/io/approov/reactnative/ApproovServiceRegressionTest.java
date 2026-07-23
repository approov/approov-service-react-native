package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
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
import org.mockito.Mockito;

public class ApproovServiceRegressionTest {

    private ReactApplicationContext reactContext;
    private MockedStatic<NetworkingModule> networkingModuleStatic;
    private MockedStatic<Approov> approovStatic;

    @Before
    public void setUp() throws Exception {
        resetServiceStaticState();
        reactContext = mock(ReactApplicationContext.class);
        AssetManager assetManager = mock(AssetManager.class);
        when(reactContext.getAssets()).thenReturn(assetManager);
        when(assetManager.open(anyString())).thenThrow(new IOException("missing"));

        networkingModuleStatic = mockStatic(NetworkingModule.class);
        approovStatic = mockStatic(Approov.class);
        approovStatic.when(() -> Approov.getPins("public-key-sha256")).thenReturn(java.util.Collections.emptyMap());
    }

    @After
    public void tearDown() {
        resetServiceStaticState();
        approovStatic.close();
        networkingModuleStatic.close();
    }

    private ApproovService newService() {
        return new ApproovService(reactContext);
    }

    private void resetServiceStaticState() {
        try {
            java.lang.reflect.Field initializedField = ApproovService.class.getDeclaredField("isInitialized");
            initializedField.setAccessible(true);
            initializedField.set(null, false);

            java.lang.reflect.Field configField = ApproovService.class.getDeclaredField("initialConfig");
            configField.setAccessible(true);
            configField.set(null, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset ApproovService static state", e);
        }
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

        approovStatic.reset();
        approovStatic.when(() -> Approov.getPins("public-key-sha256")).thenReturn(java.util.Collections.emptyMap());

        service.initialize("", null, promise);

        verify(promise, timeout(2000)).resolve(null);
        assertTrue(service.isInitialized());
        assertFalse(service.isApproovEnabled());
        approovStatic.verifyNoInteractions();
    }

    @Test
    public void initializeWithSameConfigPreservesRuntimeConfiguration() {
        ApproovService service = newService();
        String config = "valid-config";

        // Approov.initialize returns false here (mockStatic default) meaning the native
        // SDK is treated as already initialized — no exception, so the service layer
        // commits the configuration successfully.
        Promise firstInit = mock(Promise.class);
        service.initialize(config, null, firstInit);
        verify(firstInit, timeout(2000)).resolve(null);
        assertTrue(service.isApproovEnabled());

        // Configure runtime state after the first initialization, exactly as an app
        // would after ApproovService.initialize() resolves.
        service.addSubstitutionHeader("Authorization", "Bearer ");
        service.addExclusionURLRegex("https://example.com/excluded/.*");
        service.setTokenHeader("X-Custom-Token", "Bearer ");
        service.setBindingHeader("Authorization");

        // Re-initialize with the SAME config (e.g. a provider remount / StrictMode).
        Promise secondInit = mock(Promise.class);
        service.initialize(config, null, secondInit);
        verify(secondInit, timeout(2000)).resolve(null);

        // The runtime configuration must survive the same-config re-initialization.
        assertTrue("substitution header should be preserved",
            service.getSubstitutionHeaders().containsKey("Authorization"));
        assertTrue("exclusion URL regex should be preserved",
            service.getExclusionURLRegexs().containsKey("https://example.com/excluded/.*"));
        assertEquals("token header should be preserved", "X-Custom-Token", service.getTokenHeader());
        assertEquals("binding header should be preserved", "Authorization", service.getBindingHeader());
        assertTrue(service.isApproovEnabled());
    }

    @Test
    public void initializeWithDifferentConfigResetsRuntimeConfiguration() {
        ApproovService service = newService();

        Promise firstInit = mock(Promise.class);
        service.initialize("config-one", null, firstInit);
        verify(firstInit, timeout(2000)).resolve(null);
        service.addSubstitutionHeader("Authorization", "Bearer ");

        // A genuinely different config still resets the runtime configuration.
        Promise secondInit = mock(Promise.class);
        service.initialize("config-two", null, secondInit);
        verify(secondInit, timeout(2000)).resolve(null);

        assertFalse("substitution header should be cleared on a different config",
            service.getSubstitutionHeaders().containsKey("Authorization"));
    }

    @Test
    public void initializeWithDifferentConfigResetsCustomServiceMutator() {
        ApproovService service = newService();

        Promise firstInit = mock(Promise.class);
        service.initialize("config-one", null, firstInit);
        verify(firstInit, timeout(2000)).resolve(null);

        // Install a custom, non-signing mutator (as an app would via setServiceMutator,
        // or the JS setServiceMutatorType wrapper).
        ApproovServiceMutator custom = ApproovServiceMutator.DEFAULT;
        ApproovService.setServiceMutator(custom);
        assertSame(custom, ApproovService.getServiceMutator());

        // A genuinely different config must reset the mutator so a custom override does
        // not persist across an initialization boundary (root TESTING_REQUIREMENTS.md
        // section 2, "Service Mutator Reset").
        Promise secondInit = mock(Promise.class);
        service.initialize("config-two", null, secondInit);
        verify(secondInit, timeout(2000)).resolve(null);

        ApproovServiceMutator afterReset = ApproovService.getServiceMutator();
        assertNotSame("custom mutator must not persist across a config change", custom, afterReset);
        assertTrue("re-init must restore the default message-signing mutator",
            afterReset instanceof ApproovDefaultMessageSigning);
    }

    @Test
    public void statusMethodsReflectServiceLayerAndApproovEnabledStates() {
        ApproovService service = newService();
        Promise initializedPromise = mock(Promise.class);
        Promise enabledPromise = mock(Promise.class);

        service.isInitialized(initializedPromise);
        service.isApproovEnabled(enabledPromise);

        verify(initializedPromise).resolve(false);
        verify(enabledPromise).resolve(false);

        Promise initializePromise = mock(Promise.class);
        service.initialize("", null, initializePromise);
        verify(initializePromise, timeout(2000)).resolve(null);

        Promise initializedAfterEmptyConfig = mock(Promise.class);
        Promise enabledAfterEmptyConfig = mock(Promise.class);
        service.isInitialized(initializedAfterEmptyConfig);
        service.isApproovEnabled(enabledAfterEmptyConfig);

        verify(initializedAfterEmptyConfig).resolve(true);
        verify(enabledAfterEmptyConfig).resolve(false);
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

}
