package io.approov.reactnative;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;


import com.criticalblue.approovsdk.Approov;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.network.CustomClientBuilder;
import com.facebook.react.modules.network.NetworkingModule;
import com.facebook.react.modules.network.OkHttpClientProvider;
import com.facebook.react.modules.network.OkHttpClientFactory;
import com.facebook.react.modules.network.ForwardingCookieHandler;
import com.facebook.react.modules.network.ReactCookieJarContainer;

import io.approov.testfixtures.CustomClientBuilderFixtures;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.CertificatePinner;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ApproovNetworkingCallbacksTest {
    private ReactApplicationContext context;
    private static final String PIN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private MockedStatic<Approov> sdk;

    @Before
    public void setUp() throws Exception {
        resetNetworking();
        context = MiniSdkHarness.reactContext();
        // the mini-SDK protects and pins api.example.com; calls are recorded but answered by it
        MiniSdkHarness.loadScenario("\"protectedDomains\": [\"api.example.com\"], "
                + "\"pins\": {\"public-key-sha256\": {\"api.example.com\": [\"" + PIN + "\"]}}");
        sdk = mockStatic(Approov.class, CALLS_REAL_METHODS);
    }

    @After
    public void tearDown() throws Exception {
        sdk.close();
        resetNetworking();
        MiniSdkHarness.tearDown();
    }

    private static Field field(Class<?> type, String... names) throws Exception {
        for (String name : names) {
            try {
                Field result = type.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException tryNext) {
                // React Native renamed these fields across supported versions.
            }
        }
        throw new NoSuchFieldException(type.getName());
    }

    private static void resetNetworking() throws Exception {
        field(ApproovService.class, "isInitialized").set(null, false);
        field(ApproovService.class, "initialConfig").set(null, null);
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        field(NetworkingModule.class, "customClientBuilder", "mCustomClientBuilder").set(null, null);
        field(OkHttpClientProvider.class, "factory", "sFactory").set(null, null);
        field(OkHttpClientProvider.class, "client", "sClient").set(null, null);
    }

    private static CustomClientBuilder installedBuilder() throws Exception {
        return (CustomClientBuilder) field(NetworkingModule.class,
                "customClientBuilder", "mCustomClientBuilder").get(null);
    }

    private static OkHttpClientFactory installedFactory() throws Exception {
        return (OkHttpClientFactory) field(OkHttpClientProvider.class, "factory", "sFactory").get(null);
    }

    private static void assertLiveProtection(OkHttpClient client, ApproovService service) throws Exception {
        int count = 0;
        for (Interceptor interceptor : client.interceptors()) {
            if (interceptor instanceof ApproovInterceptor) {
                count++;
                assertSame(service, ((ApproovInterceptor) interceptor).service());
            }
        }
        assertEquals(1, count);
        assertEquals(1, client.networkInterceptors().stream().filter(i -> i instanceof ApproovPinningInterceptor).count());
        assertTrue(client.networkInterceptors().contains(service.getPinningInterceptor()));
    }

    private static void assertBaseConfiguration(OkHttpClient expected, OkHttpClient actual) {
        assertTrue(actual.interceptors().containsAll(expected.interceptors()));
        java.util.List<Interceptor> vendorNetwork = new java.util.ArrayList<>(actual.networkInterceptors());
        vendorNetwork.removeIf(i -> i instanceof ApproovPinningInterceptor);
        assertEquals(expected.networkInterceptors(), vendorNetwork);
        assertEquals(expected.connectTimeoutMillis(), actual.connectTimeoutMillis());
        assertEquals(expected.readTimeoutMillis(), actual.readTimeoutMillis());
        assertSame(expected.connectionPool(), actual.connectionPool());
        assertSame(expected.dispatcher(), actual.dispatcher());
        assertSame(expected.cookieJar(), actual.cookieJar());
    }

    private static OkHttpClient customerClient() {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> chain.proceed(chain.request()))
                .addNetworkInterceptor(chain -> chain.proceed(chain.request()))
                .connectTimeout(37, TimeUnit.SECONDS)
                .readTimeout(43, TimeUnit.SECONDS)
                .certificatePinner(new CertificatePinner.Builder()
                        .add("customer.example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").build())
                .build();
    }

    private OkHttpClientFactory recover(ApproovService service, boolean wrapExisting) throws Exception {
        Promise result = mock(Promise.class);
        service.updateClientFactory(wrapExisting, result);
        verify(result).resolve(true);
        return installedFactory();
    }

    @Test
    public void initializationPublishesPinsBeforeResolvingAndClientCreationDoesNotRefetch() throws Exception {
        ApproovService service = new ApproovService(context);
        Promise initialized = mock(Promise.class);
        sdk.when(() -> Approov.getPins("public-key-sha256")).thenAnswer(invocation -> {
            verify(initialized, never()).resolve(any());
            return invocation.callRealMethod();
        });
        sdk.clearInvocations(); // the stubbing call above is recorded too
        doAnswer(invocation -> {
            assertFalse(service.getPinningInterceptor().getCertificatePinner().getPins().isEmpty());
            return null;
        }).when(initialized).resolve(null);
        service.initialize(MiniSdkHarness.CONFIG, "reinit-publish", initialized);
        verify(initialized).resolve(null);
        for (int i = 0; i < 3; i++) {
            OkHttpClient client = OkHttpClientProvider.createClient();
            attachClient(client);
            installedBuilder().apply(client.newBuilder());
            new ApproovClientBuilder(service, null, true).apply(client.newBuilder());
            assertLiveProtection(recover(service, true).createNewNetworkModuleClient(), service);
        }
        sdk.verify(() -> Approov.getPins("public-key-sha256"), times(1));
    }

    @Test
    public void repeatedRecoveryAndReloadPreserveCustomerConfiguration() throws Exception {
        OkHttpClient customer = customerClient();
        OkHttpClientProvider.setOkHttpClientFactory(() -> customer);
        ApproovService service = new ApproovService(context);
        for (int i = 0; i < 3; i++) {
            attachClient(OkHttpClientProvider.createClient());
            OkHttpClientFactory recovery = recover(service, true);
            assertBaseConfiguration(customer, recovery.createNewNetworkModuleClient());
            assertLiveProtection(recovery.createNewNetworkModuleClient(), service);
            service = new ApproovService(context);
            OkHttpClient next = OkHttpClientProvider.createClient();
            assertBaseConfiguration(customer, next);
            assertLiveProtection(next, service);
            assertTrue(next.certificatePinner().getPins().isEmpty());
            OkHttpClient retired = recovery.createNewNetworkModuleClient();
            assertBaseConfiguration(customer, retired);
            assertFalse(retired.interceptors().stream().anyMatch(ApproovClientBuilder::isApproovInterceptor));
            assertFalse(retired.networkInterceptors().stream().anyMatch(ApproovClientBuilder::isApproovInterceptor));
        }
    }

    @Test
    public void wrappedRecoveryRetiresToBaseWithNoApproovInterceptorOrPins() throws Exception {
        OkHttpClient customer = customerClient();
        OkHttpClientProvider.setOkHttpClientFactory(() -> customer);
        ApproovService old = new ApproovService(context);
        old.initialize(MiniSdkHarness.CONFIG, "reinit-retire", mock(Promise.class));
        attachClient(OkHttpClientProvider.createClient());
        OkHttpClientFactory recovery = recover(old, true);
        assertFalse(old.getPinningInterceptor().getCertificatePinner().getPins().isEmpty());
        Interceptor outerMarker = chain -> chain.proceed(chain.request());
        OkHttpClientProvider.setOkHttpClientFactory(() -> recovery.createNewNetworkModuleClient()
                .newBuilder().addInterceptor(outerMarker).build());

        ApproovService live = new ApproovService(context);
        OkHttpClient next = OkHttpClientProvider.createClient();
        assertBaseConfiguration(customer, next);
        assertTrue(next.interceptors().contains(outerMarker));
        assertLiveProtection(next, live);
        OkHttpClient retired = recovery.createNewNetworkModuleClient();
        assertBaseConfiguration(customer, retired);
        assertEquals(customer.interceptors(), retired.interceptors());
        assertTrue(retired.certificatePinner().getPins().isEmpty());
    }

    @Test
    public void repeatedRecoveryWithoutReloadPreservesConfigurationAndReleasesPreviousProtection() throws Exception {
        OkHttpClient customer = customerClient();
        ApproovService service = new ApproovService(context);
        attachClient(customer);
        OkHttpClientFactory first = recover(service, true);
        OkHttpClientFactory second = recover(service, true);
        assertBaseConfiguration(customer, second.createNewNetworkModuleClient());
        assertLiveProtection(second.createNewNetworkModuleClient(), service);
        assertBaseConfiguration(customer, first.createNewNetworkModuleClient());
        assertEquals(customer.interceptors(), first.createNewNetworkModuleClient().interceptors());
    }

    @Test
    public void recoveryWithoutWrappingStillStartsFromDefaultsAfterReload() throws Exception {
        OkHttpClient customer = customerClient();
        ApproovService service = new ApproovService(context);
        attachClient(customer);
        recover(service, false);
        ApproovService live = new ApproovService(context);
        OkHttpClient next = OkHttpClientProvider.createClient();
        assertFalse(next.interceptors().contains(customer.interceptors().get(0)));
        assertEquals(Collections.singletonList(live.getPinningInterceptor()), next.networkInterceptors());
        assertEquals(new OkHttpClient.Builder().build().connectTimeoutMillis(), next.connectTimeoutMillis());
        assertLiveProtection(next, live);
    }

    private void attachClient(OkHttpClient client) throws Exception {
        NetworkingModule module = mock(NetworkingModule.class);
        when(context.getNativeModule(NetworkingModule.class)).thenReturn(module);
        field(NetworkingModule.class, "client", "mClient").set(module, client);
        // A real NetworkingModule normally retains its own RN cookie container even if
        // another SDK subsequently replaces its client with one using a different jar.
        field(NetworkingModule.class, "cookieJarContainer", "mCookieJarContainer")
                .set(module, new ReactCookieJarContainer());
    }

    private NetworkingModule realNetworkingModule() {
        when(context.getApplicationContext()).thenReturn(context);
        NetworkingModule module = new NetworkingModule(context);
        when(context.getNativeModule(NetworkingModule.class)).thenReturn(module);
        return module;
    }

    private ForwardingCookieHandler installCookieHandler(NetworkingModule module) throws Exception {
        ForwardingCookieHandler handler = mock(ForwardingCookieHandler.class);
        when(handler.get(any(URI.class), anyMap())).thenReturn(
                Collections.singletonMap("Cookie", Collections.singletonList("session=abc123")));
        field(NetworkingModule.class, "cookieHandler", "mCookieHandler").set(module, handler);
        return handler;
    }

    private static void assertCookieBridge(OkHttpClient client, ForwardingCookieHandler handler) throws Exception {
        HttpUrl url = HttpUrl.get("https://api.example.com/me");
        assertEquals("abc123", client.cookieJar().loadForRequest(url).get(0).value());
        client.cookieJar().saveFromResponse(url, Collections.singletonList(new Cookie.Builder()
                .name("session").value("updated").domain("api.example.com").build()));
        verify(handler).get(eq(url.uri()), anyMap());
        verify(handler).put(eq(url.uri()), anyMap());
    }

    @Test
    public void resetPreservesInitializedCookiesAndPlainTimeoutsAcrossReload() throws Exception {
        ApproovService service = new ApproovService(context);
        NetworkingModule module = realNetworkingModule();
        ForwardingCookieHandler handler = installCookieHandler(module);
        module.initialize();
        CookieJar initializedJar = ((OkHttpClient) field(NetworkingModule.class, "client", "mClient")
                .get(module)).cookieJar();
        // The active module's container is authoritative even after a client replacement.
        field(NetworkingModule.class, "client", "mClient").set(module, customerClient());
        OkHttpClient recovered = recover(service, false).createNewNetworkModuleClient();
        assertSame(initializedJar, recovered.cookieJar());
        assertSame(recovered, field(NetworkingModule.class, "client", "mClient").get(module));
        assertCookieBridge(recovered, handler);
        OkHttpClient defaults = new OkHttpClient.Builder().build();
        assertEquals(defaults.connectTimeoutMillis(), recovered.connectTimeoutMillis());
        assertEquals(defaults.readTimeoutMillis(), recovered.readTimeoutMillis());
        assertEquals(defaults.writeTimeoutMillis(), recovered.writeTimeoutMillis());
        assertEquals(Collections.singletonList(service.getPinningInterceptor()), recovered.networkInterceptors());

        module.invalidate();
        ApproovService live = new ApproovService(context);
        NetworkingModule reloaded = realNetworkingModule();
        ForwardingCookieHandler nextHandler = installCookieHandler(reloaded);
        reloaded.initialize();
        OkHttpClient next = (OkHttpClient) field(NetworkingModule.class, "client", "mClient").get(reloaded);
        assertCookieBridge(next, nextHandler);
        assertLiveProtection(next, live);
        assertEquals(defaults.connectTimeoutMillis(), next.connectTimeoutMillis());
        assertEquals(defaults.readTimeoutMillis(), next.readTimeoutMillis());
        assertEquals(defaults.writeTimeoutMillis(), next.writeTimeoutMillis());
    }

    @Test
    public void resetBeforeModuleCreationSuppliesCookieContainer() throws Exception {
        ApproovService service = new ApproovService(context);
        OkHttpClient recovered = recover(service, false).createNewNetworkModuleClient();
        assertTrue(recovered.cookieJar() instanceof ReactCookieJarContainer);
        NetworkingModule module = realNetworkingModule();
        ForwardingCookieHandler handler = installCookieHandler(module);
        module.initialize();
        assertCookieBridge(recovered, handler);
    }

    @Test
    public void resetConnectsMissingContainerToExistingModuleHandler() throws Exception {
        ApproovService service = new ApproovService(context);
        NetworkingModule module = realNetworkingModule();
        ForwardingCookieHandler handler = installCookieHandler(module);
        // RN 0.81 permits a null container when its initial client uses a non-RN jar.
        field(NetworkingModule.class, "cookieJarContainer", "mCookieJarContainer").set(module, null);
        field(NetworkingModule.class, "client", "mClient").set(module, customerClient());
        OkHttpClient recovered = recover(service, false).createNewNetworkModuleClient();
        assertCookieBridge(recovered, handler);
        assertSame(recovered.cookieJar(), field(NetworkingModule.class,
                "cookieJarContainer", "mCookieJarContainer").get(module));
        module.invalidate();
        assertTrue(recovered.cookieJar().loadForRequest(HttpUrl.get("https://api.example.com/me")).isEmpty());
    }

    private static ReadableMap diagnostics(Promise promise) {
        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(promise).resolve(result.capture());
        return (ReadableMap) result.getValue();
    }

    private void assertCallbackPreserved(CustomClientBuilder callback, Interceptor marker) throws Exception {
        OkHttpClient.Builder control = new OkHttpClient.Builder();
        callback.apply(control);
        assertTrue(control.interceptors().contains(marker));

        NetworkingModule.setCustomClientBuilder(callback);
        new ApproovService(context);
        OkHttpClient.Builder request = OkHttpClientProvider.createClient().newBuilder();
        installedBuilder().apply(request);
        assertTrue(request.interceptors().contains(marker));
    }

    @Test
    public void preservesExternalAnonymousCallback() throws Exception {
        Interceptor marker = chain -> chain.proceed(chain.request());
        assertCallbackPreserved(CustomClientBuilderFixtures.anonymous(marker), marker);
    }

    @Test
    public void preservesExternalLambdaCallback() throws Exception {
        Interceptor marker = chain -> chain.proceed(chain.request());
        assertCallbackPreserved(CustomClientBuilderFixtures.lambda(marker), marker);
    }

    @Test
    public void preservesExternalNestedInterfaceCallback() throws Exception {
        Interceptor marker = chain -> chain.proceed(chain.request());
        assertCallbackPreserved(CustomClientBuilderFixtures.nested(marker), marker);
    }

    @Test
    public void propagatesOriginalCallbackExceptionBeforeAddingApproov() throws Exception {
        IllegalStateException failure = new IllegalStateException("Configuration unavailable");
        NetworkingModule.setCustomClientBuilder(new CustomClientBuilderFixtures.Failing(failure));
        new ApproovService(context);
        OkHttpClient.Builder request = new OkHttpClient.Builder();
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> installedBuilder().apply(request)));
        assertTrue(request.interceptors().isEmpty());
    }

    @Test
    public void propagatesOriginalCallbackError() throws Exception {
        AssertionError failure = new AssertionError("Callback failed");
        NetworkingModule.setCustomClientBuilder(new CustomClientBuilderFixtures.Failing(failure));
        new ApproovService(context);
        assertSame(failure, assertThrows(AssertionError.class,
                () -> installedBuilder().apply(new OkHttpClient.Builder())));
    }

    @Test
    public void diagnosticsApplyExternalCallbackToTheEffectiveClient() throws Exception {
        ApproovService service = new ApproovService(context);
        attachClient(OkHttpClientProvider.createClient());
        NetworkingModule.setCustomClientBuilder(CustomClientBuilderFixtures.removingProtection());

        Promise active = mock(Promise.class);
        service.isInterceptorActive(active);
        verify(active).resolve(false);
        Promise pinning = mock(Promise.class);
        service.getPinningDiagnostics(pinning);
        ReadableMap result = diagnostics(pinning);
        assertFalse(result.getBoolean("isInterceptorPresent"));
        assertFalse(result.getBoolean("isPinnerPresent"));
    }

    @Test
    public void diagnosticsRejectWhenTheCallbackFails() throws Exception {
        ApproovService service = new ApproovService(context);
        attachClient(OkHttpClientProvider.createClient());
        NetworkingModule.setCustomClientBuilder(new CustomClientBuilderFixtures.Failing(
                new IllegalStateException("Configuration unavailable")));

        Promise active = mock(Promise.class);
        service.isInterceptorActive(active);
        verify(active).reject(eq("isInterceptorActive"), contains("Configuration unavailable"), any(WritableMap.class));
        verify(active, never()).resolve(any());
        Promise pinning = mock(Promise.class);
        service.getPinningDiagnostics(pinning);
        verify(pinning).reject(eq("getPinningDiagnostics"), contains("Configuration unavailable"), any(WritableMap.class));
        verify(pinning, never()).resolve(any());
    }

    @Test
    public void diagnosticsDoNotReportAnEmptyTlsPinnerAsProtection() throws Exception {
        ApproovService service = new ApproovService(context);
        OkHttpClient unpinned = new OkHttpClient.Builder().build();
        assertTrue(unpinned.certificatePinner().getPins().isEmpty());
        attachClient(unpinned);
        NetworkingModule.setCustomClientBuilder(null);
        Promise promise = mock(Promise.class);
        service.getPinningDiagnostics(promise);
        assertFalse(diagnostics(promise).getBoolean("isPinnerPresent"));
    }

    @Test
    public void diagnosticsDoNotReportAPinningInterceptorWithoutPinsAsProtection() throws Exception {
        ApproovService service = new ApproovService(context);
        // not initialized, so the shared pinning interceptor holds no pins
        service.getPinningInterceptor().installPins(CertificatePinner.DEFAULT);
        OkHttpClient client = new OkHttpClient.Builder()
                .addNetworkInterceptor(service.getPinningInterceptor())
                .build();
        attachClient(client);
        NetworkingModule.setCustomClientBuilder(null);
        Promise promise = mock(Promise.class);
        service.getPinningDiagnostics(promise);
        assertFalse(diagnostics(promise).getBoolean("isPinnerPresent"));
    }

    @Test
    public void diagnosticsRequirePinsOnTheNetworkChainWithoutFetchingPins() throws Exception {
        ApproovService service = new ApproovService(context);
        service.initialize(MiniSdkHarness.CONFIG, "reinit-diagnostics", mock(Promise.class));
        NetworkingModule.setCustomClientBuilder(null);
        attachClient(new OkHttpClient.Builder()
                .certificatePinner(service.getPinningInterceptor().getCertificatePinner())
                .addInterceptor(service.getPinningInterceptor())
                .addNetworkInterceptor(new ApproovInterceptor(service)).build());
        Promise misplaced = mock(Promise.class);
        service.getPinningDiagnostics(misplaced);
        assertFalse(diagnostics(misplaced).getBoolean("isInterceptorPresent"));
        assertFalse(diagnostics(misplaced).getBoolean("isPinnerPresent"));

        attachClient(new OkHttpClient.Builder().addInterceptor(new ApproovInterceptor(service))
                .addNetworkInterceptor(service.getPinningInterceptor()).build());
        Promise correct = mock(Promise.class);
        service.getPinningDiagnostics(correct);
        assertTrue(diagnostics(correct).getBoolean("isInterceptorPresent"));
        assertTrue(diagnostics(correct).getBoolean("isPinnerPresent"));
        sdk.verify(() -> Approov.getPins("public-key-sha256"), times(1));
    }

    @Test
    public void reloadsPreserveOneCallbackInvocationPerRequest() throws Exception {
        CustomClientBuilderFixtures.Counting callback = new CustomClientBuilderFixtures.Counting();
        NetworkingModule.setCustomClientBuilder(callback);
        for (int i = 0; i < 4; i++) {
            new ApproovService(context);
        }
        OkHttpClient base = OkHttpClientProvider.createClient();
        assertEquals(0, callback.calls);
        installedBuilder().apply(base.newBuilder());
        assertEquals(1, callback.calls);
    }
}
