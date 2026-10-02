package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;


import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.network.NetworkingModule;
import com.facebook.react.modules.network.OkHttpClientProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.mockito.MockedStatic;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.CertificatePinner;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ApproovServicePublicApiTest {
    private static final String PIN_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String PIN_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=";

    private ReactApplicationContext reactContext;
    private MockedStatic<NetworkingModule> networkingModuleStatic;

    @Before
    public void setUp() throws Exception {
        reactContext = MiniSdkHarness.reactContext();
        MiniSdkHarness.resetServiceState();
        networkingModuleStatic = mockStatic(NetworkingModule.class);
    }

    @After
    public void tearDown() throws Exception {
        networkingModuleStatic.close();
        MiniSdkHarness.tearDown();
    }

    private ApproovService newService() {
        return new ApproovService(reactContext);
    }

    // a mini-SDK scenario protecting example.com with a single leaf pin
    private static void loadExampleScenario(String pin) {
        MiniSdkHarness.loadScenario("\"protectedDomains\": [\"example.com\"],"
            + "\"pins\": {\"public-key-sha256\": {\"example.com\": [\"" + pin + "\"]}}");
    }

    @Test
    public void getLastArcReturnsArcOnlyAfterSuccessfulFetch() throws Exception {
        loadExampleScenario(PIN_B);
        ApproovService service = MiniSdkHarness.initializedService(reactContext);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> arc = new AtomicReference<>();
        Promise promise = mock(Promise.class);
        doAnswer(invocation -> {
            arc.set(invocation.getArgument(0));
            done.countDown();
            return null;
        }).when(promise).resolve(any());

        service.getLastARC(promise);

        assertTrue("getLastARC did not resolve", done.await(5, TimeUnit.SECONDS));
        // the ARC the mini-SDK puts on a successful token fetch for the pinned host
        assertEquals("IXPSB7TRK26LXE3M", arc.get());
    }

    @Test
    public void getPinningDiagnosticsResolvesExpectedFlagsAndInterceptorNames() throws Exception {
        loadExampleScenario(PIN_A);
        ApproovService service = MiniSdkHarness.initializedService(reactContext);
        try (MockedStatic<OkHttpClientProvider> okHttpClientProvider = mockStatic(OkHttpClientProvider.class)) {
            Promise promise = mock(Promise.class);
            AtomicReference<Object> resolvedValue = new AtomicReference<>();
            doAnswer(invocation -> {
                resolvedValue.set(invocation.getArgument(0));
                return null;
            }).when(promise).resolve(any());

            Interceptor extraInterceptor = chain -> chain.proceed(chain.request());
            OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new ApproovInterceptor(service))
                .addInterceptor(extraInterceptor)
                .addNetworkInterceptor(service.getPinningInterceptor())
                .build();
            okHttpClientProvider.when(OkHttpClientProvider::getOkHttpClient).thenReturn(client);

            service.getPinningDiagnostics(promise);

            assertTrue(resolvedValue.get() instanceof ReadableMap);
            ReadableMap diagnostics = (ReadableMap) resolvedValue.get();
            assertTrue(diagnostics.hasKey("isInterceptorPresent"));
            assertTrue(diagnostics.getBoolean("isInterceptorPresent"));
            assertTrue(diagnostics.hasKey("isPinnerPresent"));
            assertTrue(diagnostics.getBoolean("isPinnerPresent"));

            ReadableArray interceptors = diagnostics.getArray("interceptors");
            assertNotNull(interceptors);
            boolean foundApproovInterceptor = false;
            for (int index = 0; index < interceptors.size(); index++) {
                if ("io.approov.reactnative.ApproovInterceptor".equals(interceptors.getString(index))) {
                    foundApproovInterceptor = true;
                    break;
                }
            }
            assertTrue(foundApproovInterceptor);
        }
    }

    @Test
    public void existingClientRefreshesCertificatePinsWhenConfigurationChanges() throws Exception {
        loadExampleScenario(PIN_B);
        ApproovService service = MiniSdkHarness.initializedService(reactContext);
        ApproovClientBuilder clientBuilder = new ApproovClientBuilder(service, null);
        OkHttpClient.Builder firstBuilder = new OkHttpClient.Builder();
        clientBuilder.apply(firstBuilder);
        ApproovPinningInterceptor installed = (ApproovPinningInterceptor) firstBuilder.build().networkInterceptors().get(0);
        CertificatePinner firstPinner = installed.getCertificatePinner();

        assertTrue("expected initial pinner to contain BBB... pin but was " + firstPinner.getPins(),
            hasPinHash(firstPinner, PIN_B));

        // a token fetch delivers a dynamic configuration update carrying new pins
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"fetchApproovToken\","
            + "\"response\": {\"status\": \"SUCCESS\", \"configChanged\": true},"
            + "\"attesterConfig\": {\"protectedDomains\": [\"example.com\"],"
            + "\"pins\": {\"public-key-sha256\": {\"example.com\": [\"" + PIN_A + "\"]}}}}");
        Approov.TokenFetchResult update = Approov.fetchApproovTokenAndWait("https://example.com/");
        assertTrue("the fetch should report the configuration change", update.isConfigChanged());
        service.rebuildPins();

        CertificatePinner secondPinner = installed.getCertificatePinner();

        assertTrue("expected refreshed pinner to contain AAA... pin but was " + secondPinner.getPins(),
            hasPinHash(secondPinner, PIN_A));
    }

    @Test
    public void exclusionRegexDoesNotDisableCertificatePinning() throws Exception {
        loadExampleScenario(PIN_B);
        ApproovService service = MiniSdkHarness.initializedService(reactContext);
        // runtime configuration is reset by initialize, so apply it afterwards
        service.addExclusionURLRegex("^.*excluded.*$");

        ApproovClientBuilder clientBuilder = new ApproovClientBuilder(service, null);
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        clientBuilder.apply(builder);
        CertificatePinner pinner = ((ApproovPinningInterceptor) builder.build().networkInterceptors().get(0)).getCertificatePinner();

        assertTrue("expected exclusion regex to leave certificate pinning active",
            hasPinHash(pinner, PIN_B));
    }

    @Test
    public void logMessageDoesNotCrashAtAnyLevel() {
        {
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
        try (MockedStatic<OkHttpClientProvider> okProvider = mockStatic(OkHttpClientProvider.class)) {
            ApproovService service = newService();
            Promise promise = mock(Promise.class);

            OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new ApproovInterceptor(service))
                .build();
            okProvider.when(OkHttpClientProvider::getOkHttpClient).thenReturn(client);

            service.isInterceptorActive(promise);

            org.mockito.Mockito.verify(promise).resolve(true);
        }
    }

    @Test
    public void isInterceptorActiveReturnsFalseWhenNoApproovInterceptorIsPresent() {
        try (MockedStatic<OkHttpClientProvider> okProvider = mockStatic(OkHttpClientProvider.class)) {
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

    // Regression for the setServiceMutatorType mask validation (Copilot review, PR #32).
    // The proceed bitmask is bridged from JavaScript as a double; a non-finite, fractional,
    // or out-of-32-bit-range value must be rejected before it is narrowed to int, so it can
    // never be silently coerced into an unintended (security-relevant) proceed policy.
    @Test
    public void setServiceMutatorTypeRejectsNonFiniteOrNonIntegralMasks() {
        double[] invalidMasks = {
            1.5,                              // fractional
            Double.NaN,                       // not a number
            Double.POSITIVE_INFINITY,         // +infinity
            Double.NEGATIVE_INFINITY,         // -infinity
            (double) Integer.MAX_VALUE + 1.0, // above the signed 32-bit range
            (double) Integer.MIN_VALUE - 1.0, // below the signed 32-bit range
        };
        {
            for (double mask : invalidMasks) {
                Promise promise = mock(Promise.class);

                newService().setServiceMutatorType(mask, true, "install", promise);

                org.mockito.Mockito.verify(promise)
                    .reject(org.mockito.ArgumentMatchers.eq("setServiceMutatorType"), anyString());
                org.mockito.Mockito.verify(promise, org.mockito.Mockito.never()).resolve(any());
            }
        }
    }

    // Regression for the undefined-bit guard (charlesoj6205 review, PR #32). Only bits 0-10
    // name a token-fetch status. A mask carrying any bit above BIT_DISABLED grants PROCEED to
    // nothing, so it would install a silent block-everything policy that looks deliberate.
    // It must be rejected rather than applied.
    @Test
    public void setServiceMutatorTypeRejectsMasksWithUndefinedBits() {
        double[] invalidMasks = {
            (double) (1 << 11),                                   // first bit above BIT_DISABLED
            (double) (1 << 30),                                   // far above the defined range
            (double) (PolicyMutator.BIT_NO_NETWORK | (1 << 11)),  // valid bit plus an undefined one
            (double) Integer.MAX_VALUE,                           // in 32-bit range, mostly undefined bits
            -2.0,                                                 // negative, but not the DEFAULT sentinel
        };
        {
            for (double mask : invalidMasks) {
                Promise promise = mock(Promise.class);

                newService().setServiceMutatorType(mask, true, "install", promise);

                org.mockito.Mockito.verify(promise)
                    .reject(org.mockito.ArgumentMatchers.eq("setServiceMutatorType"), anyString());
                org.mockito.Mockito.verify(promise, org.mockito.Mockito.never()).resolve(any());
            }
        }
    }

    // The guard must not reject a well-formed mask: the DEFAULT sentinel (-1), an all-block
    // mask (0), a composed in-range bitmask, and the full set of defined bits all pass
    // validation and resolve.
    @Test
    public void setServiceMutatorTypeAcceptsValidIntegralMasks() {
        double[] validMasks = {
            -1.0, // MUTATOR_PRESET_DEFAULT — restore the built-in signing default
            0.0,  // block every maskable failure status
            (double) (PolicyMutator.BIT_NO_NETWORK | PolicyMutator.BIT_POOR_NETWORK),
            (double) PolicyMutator.ALL_BITS, // every defined bit — the permissive extreme
        };
        {
            for (double mask : validMasks) {
                Promise promise = mock(Promise.class);

                newService().setServiceMutatorType(mask, true, "install", promise);

                org.mockito.Mockito.verify(promise).resolve(any());
                org.mockito.Mockito.verify(promise, org.mockito.Mockito.never())
                    .reject(org.mockito.ArgumentMatchers.eq("setServiceMutatorType"), anyString());
            }
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
