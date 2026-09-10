package io.approov.reactnative;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.res.AssetManager;

import com.criticalblue.approovsdk.Approov;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.network.CustomClientBuilder;
import com.facebook.react.modules.network.NetworkingModule;
import com.facebook.react.modules.network.OkHttpClientProvider;

import io.approov.testfixtures.CustomClientBuilderFixtures;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

public class ApproovNetworkingCallbacksTest {
    private ReactApplicationContext context;
    private MockedStatic<Approov> sdk;

    @Before
    public void setUp() throws Exception {
        resetNetworking();
        context = mock(ReactApplicationContext.class);
        AssetManager assets = mock(AssetManager.class);
        when(context.getAssets()).thenReturn(assets);
        when(assets.open(anyString())).thenThrow(new IOException("No bundled configuration"));
        sdk = mockStatic(Approov.class);
        sdk.when(() -> Approov.getPins("public-key-sha256")).thenReturn(Collections.emptyMap());
    }

    @After
    public void tearDown() throws Exception {
        sdk.close();
        resetNetworking();
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

    private void attachClient(OkHttpClient client) throws Exception {
        NetworkingModule module = mock(NetworkingModule.class);
        when(context.getNativeModule(NetworkingModule.class)).thenReturn(module);
        field(NetworkingModule.class, "client", "mClient").set(module, client);
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
