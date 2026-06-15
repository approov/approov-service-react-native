package io.approov.reactnative;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.res.AssetManager;

import com.criticalblue.approovsdk.Approov;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.network.NetworkingModule;
import com.facebook.react.modules.network.OkHttpClientProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.CertificatePinner;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

public class ApproovServicePublicApiTest {

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
        // restore production defaults for the global mutator/signer state mutated by some tests
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        ApproovService.setMessageSigner(ApproovDefaultMessageSigning.makeDefault());
    }

    private ApproovService newService() {
        return new ApproovService(reactContext);
    }

    private void setStaticField(String name, Object value) throws Exception {
        Field field = ApproovService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @Test
    public void getLastArcReturnsArcOnlyAfterSuccessfulFetch() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovService service = newService();
            setStaticField("isInitialized", true);
            setStaticField("initialConfig", "test-config");
            Promise promise = mock(Promise.class);

            approov.when(() -> Approov.getPins("public-key-sha256"))
                .thenReturn(Collections.singletonMap("example.com", Collections.singletonList("pin")));

            Approov.TokenFetchResult success = mock(Approov.TokenFetchResult.class);
            when(success.getToken()).thenReturn("jwt-token");
            when(success.getARC()).thenReturn("ARC123");
            approov.when(() -> Approov.fetchApproovToken(any(Approov.TokenFetchCallback.class), anyString()))
                .thenAnswer(invocation -> {
                    Approov.TokenFetchCallback callback = invocation.getArgument(0);
                    callback.approovCallback(success);
                    return null;
                });

            service.getLastARC(promise);

            org.mockito.Mockito.verify(promise).resolve("ARC123");
        }
    }

    @Test
    public void getPinningDiagnosticsResolvesExpectedFlagsAndInterceptorNames() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class);
             MockedStatic<OkHttpClientProvider> okHttpClientProvider = mockStatic(OkHttpClientProvider.class)) {
            ApproovService service = newService();
            Promise promise = mock(Promise.class);
            AtomicReference<Object> resolvedValue = new AtomicReference<>();

            approov.when(() -> Approov.getPins(anyString())).thenReturn(Collections.emptyMap());
            doAnswer(invocation -> {
                resolvedValue.set(invocation.getArgument(0));
                return null;
            }).when(promise).resolve(any());

            Interceptor extraInterceptor = chain -> chain.proceed(chain.request());
            OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new ApproovTokenInterceptor(service))
                .addInterceptor(extraInterceptor)
                .addNetworkInterceptor(new ApproovPinningInterceptor(service))
                .build();
            okHttpClientProvider.when(OkHttpClientProvider::getOkHttpClient).thenReturn(client);

            service.getPinningDiagnostics(promise);

            assertTrue(resolvedValue.get() instanceof ReadableMap);
            ReadableMap diagnostics = (ReadableMap) resolvedValue.get();
            assertTrue(diagnostics.hasKey("isInterceptorPresent"));
            assertTrue(diagnostics.getBoolean("isInterceptorPresent"));
            // pinning is enforced by the ApproovPinningInterceptor network interceptor, so its
            // presence in the chain is what isPinnerPresent reflects
            assertTrue(diagnostics.hasKey("isPinnerPresent"));
            assertTrue(diagnostics.getBoolean("isPinnerPresent"));

            ReadableArray interceptors = diagnostics.getArray("interceptors");
            assertNotNull(interceptors);
            boolean foundApproovInterceptor = false;
            for (int index = 0; index < interceptors.size(); index++) {
                if ("io.approov.reactnative.ApproovTokenInterceptor".equals(interceptors.getString(index))) {
                    foundApproovInterceptor = true;
                    break;
                }
            }
            assertTrue(foundApproovInterceptor);
        }
    }

    @Test
    public void rebuildPinsRefreshesRegisteredPinningInterceptors() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovService service = newService();
            setStaticField("isInitialized", true);
            setStaticField("initialConfig", "test-config");

            Map<String, List<String>> initialPins = Collections.singletonMap(
                "example.com",
                Collections.singletonList("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=")
            );
            Map<String, List<String>> updatedPins = Collections.singletonMap(
                "example.com",
                Collections.singletonList("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            );

            approov.when(() -> Approov.getPins("public-key-sha256"))
                .thenReturn(initialPins)
                .thenReturn(updatedPins);

            // a registered pinning interceptor builds its initial pins on construction
            ApproovPinningInterceptor pinning = new ApproovPinningInterceptor(service);
            service.registerPinningInterceptor(pinning);
            CertificatePinner firstPinner = pinning.getCertificatePinner();

            assertTrue("expected initial pinner to contain BBB... pin but was " + firstPinner.getPins(),
                hasPinHash(firstPinner, "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA="));

            // a dynamic config change triggers rebuildPins, which rebuilds registered interceptors
            // in place so already-installed clients pick up the new pins
            service.rebuildPins();
            CertificatePinner secondPinner = pinning.getCertificatePinner();

            assertTrue("expected refreshed pinner to contain AAA... pin but was " + secondPinner.getPins(),
                hasPinHash(secondPinner, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
        }
    }

    @Test
    public void exclusionRegexDoesNotDisableCertificatePinning() throws Exception {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovService service = newService();
            setStaticField("isInitialized", true);
            setStaticField("initialConfig", "test-config");
            service.addExclusionURLRegex("^.*excluded.*$");

            approov.when(() -> Approov.getPins("public-key-sha256"))
                .thenReturn(Collections.singletonMap(
                    "example.com",
                    Collections.singletonList("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=")
                ));

            // pinning runs as a network interceptor; an exclusion regex affects token/header
            // processing only and must not disable certificate pinning
            ApproovPinningInterceptor pinning = new ApproovPinningInterceptor(service);
            CertificatePinner pinner = pinning.getCertificatePinner();

            assertTrue("expected exclusion regex to leave certificate pinning active",
                hasPinHash(pinner, "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA="));
        }
    }

    @Test
    public void logMessageDoesNotCrashAtAnyLevel() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovService service = newService();

            // All defined levels: EXTREME(0), DEBUG(1), INFO(2), WARN(3), ERROR(4)
            service.logMessage("test extreme", 0);
            service.logMessage("test debug", 1);
            service.logMessage("test info", 2);
            service.logMessage("test warn", 3);
            service.logMessage("test error", 4);

            // Edge cases: null message, null level, unknown level
            service.logMessage(null, 2);
            service.logMessage("test null-level", null);
            service.logMessage("test unknown-level", 99);
        }
    }

    @Test
    public void isInterceptorActiveReturnsTrueWhenApproovInterceptorIsPresent() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class);
             MockedStatic<OkHttpClientProvider> okProvider = mockStatic(OkHttpClientProvider.class)) {
            ApproovService service = newService();
            Promise promise = mock(Promise.class);

            OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new ApproovTokenInterceptor(service))
                .build();
            okProvider.when(OkHttpClientProvider::getOkHttpClient).thenReturn(client);

            service.isInterceptorActive(promise);

            org.mockito.Mockito.verify(promise).resolve(true);
        }
    }

    @Test
    public void isInterceptorActiveReturnsFalseWhenNoApproovInterceptorIsPresent() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class);
             MockedStatic<OkHttpClientProvider> okProvider = mockStatic(OkHttpClientProvider.class)) {
            ApproovService service = newService();
            Promise promise = mock(Promise.class);

            OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> chain.proceed(chain.request()))
                .build();
            okProvider.when(OkHttpClientProvider::getOkHttpClient).thenReturn(client);

            service.isInterceptorActive(promise);

            org.mockito.Mockito.verify(promise).resolve(false);
        }
    }

    @Test
    public void setServiceMutatorSelectsOffTheShelfPoliciesByType() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovService service = newService();

            service.setServiceMutator("ALWAYS_PROCEED");
            Promise p1 = mock(Promise.class);
            service.getServiceMutatorType(p1);
            org.mockito.Mockito.verify(p1).resolve("ALWAYS_PROCEED");

            service.setServiceMutator("REQUIRE_ATTESTATION");
            Promise p2 = mock(Promise.class);
            service.getServiceMutatorType(p2);
            org.mockito.Mockito.verify(p2).resolve("REQUIRE_ATTESTATION");

            service.setServiceMutator("DEFAULT");
            Promise p3 = mock(Promise.class);
            service.getServiceMutatorType(p3);
            org.mockito.Mockito.verify(p3).resolve("DEFAULT");

            // an unknown type leaves the current mutator unchanged
            service.setServiceMutator("NOPE");
            Promise p4 = mock(Promise.class);
            service.getServiceMutatorType(p4);
            org.mockito.Mockito.verify(p4).resolve("DEFAULT");
        }
    }

    @Test
    public void messageSigningCanBeToggledFromTheBridge() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovService service = newService();

            service.setMessageSigningEnabled(true, mock(Promise.class));
            Promise enabled = mock(Promise.class);
            service.isMessageSigningEnabled(enabled);
            org.mockito.Mockito.verify(enabled).resolve(true);

            service.setMessageSigningEnabled(false, mock(Promise.class));
            Promise disabled = mock(Promise.class);
            service.isMessageSigningEnabled(disabled);
            org.mockito.Mockito.verify(disabled).resolve(false);

            service.setMessageSigningEnabled(true, mock(Promise.class));
            Promise reEnabled = mock(Promise.class);
            service.isMessageSigningEnabled(reEnabled);
            org.mockito.Mockito.verify(reEnabled).resolve(true);
        }
    }

    @Test
    public void addSignedHeaderReenablesSigningWhenDisabled() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovService service = newService();

            service.setMessageSigningEnabled(false, mock(Promise.class));
            service.addSignedHeader("X-Custom-Header");

            Promise enabled = mock(Promise.class);
            service.isMessageSigningEnabled(enabled);
            org.mockito.Mockito.verify(enabled).resolve(true);
        }
    }

    private boolean hasPinHash(CertificatePinner pinner, String expectedHashBase64) throws Exception {
        for (Object pin : pinner.getPins()) {
            Object hash = pin.getClass().getMethod("getHash").invoke(pin);
            String actualHashBase64 = (String) hash.getClass().getMethod("base64").invoke(hash);
            if (expectedHashBase64.equals(actualHashBase64)) {
                return true;
            }
        }
        return false;
    }
}
