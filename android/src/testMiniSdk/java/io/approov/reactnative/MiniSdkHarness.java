package io.approov.reactnative;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.criticalblue.minisdk.testing.AttesterProxyController;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the layer against the Approov mini-SDK (core-service-layers-testing/mini-sdk) instead of a
 * mocked SDK. Test classes that use it need the Robolectric runner, and are excluded from the
 * build when the mini-SDK checkout is not present (android/build.gradle).
 */
final class MiniSdkHarness {
    static final String CONFIG = "#cb-ivol#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";

    private MiniSdkHarness() {
    }

    /** A ReactApplicationContext backed by the Robolectric application context. */
    static ReactApplicationContext reactContext() {
        Context app = ApplicationProvider.getApplicationContext();
        ReactApplicationContext context = mock(ReactApplicationContext.class);
        when(context.getApplicationContext()).thenReturn(app);
        when(context.getPackageName()).thenReturn(app.getPackageName());
        when(context.getAssets()).thenReturn(app.getAssets());
        return context;
    }

    /**
     * Resets the mini-SDK and loads a scenario with one active case. The body is the case's JSON
     * members without braces, for example {@code "protectedDomains": ["api.example.com"]}.
     */
    static void loadScenario(String body) {
        String name = "rn-" + UUID.randomUUID();
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson(
                "{\"activeCase\":\"" + name + "\",\"cases\":{\"" + name + "\":{" + body + "}}}");
    }

    /** Resets the layer's static initialization state so each test starts uninitialized. */
    static void resetServiceState() throws Exception {
        Field initialized = ApproovService.class.getDeclaredField("isInitialized");
        initialized.setAccessible(true);
        initialized.set(null, false);
        Field config = ApproovService.class.getDeclaredField("initialConfig");
        config.setAccessible(true);
        config.set(null, null);
    }

    /**
     * Creates a layer service and initializes it, and so the mini-SDK, with the current scenario.
     * A reinit comment lets every test initialize again.
     */
    static ApproovService initializedService(ReactApplicationContext context) throws Exception {
        resetServiceState();
        ApproovService service = new ApproovService(context);
        initialize(service, CONFIG);
        return service;
    }

    /** Initializes a service with the given config and fails the test if the promise rejects. */
    static void initialize(ApproovService service, String config) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> rejection = new AtomicReference<>();
        Promise promise = mock(Promise.class);
        doAnswer(invocation -> {
            done.countDown();
            return null;
        }).when(promise).resolve(nullable(Object.class));
        doAnswer(invocation -> {
            rejection.set(invocation.getArgument(0) + " " + invocation.getArgument(1));
            done.countDown();
            return null;
        }).when(promise).reject(any(String.class), any(String.class), any(WritableMap.class));
        service.initialize(config, "reinit-" + UUID.randomUUID(), promise);
        assertTrue("initialize did not complete", done.await(5, TimeUnit.SECONDS));
        if (rejection.get() != null)
            fail("initialize rejected: " + rejection.get());
    }

    /** Clears the mini-SDK scenario and the layer's static state after a test. */
    static void tearDown() throws Exception {
        AttesterProxyController.reset();
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        resetServiceState();
    }
}
