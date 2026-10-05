package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import okhttp3.Interceptor;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.network.NetworkingModule.CustomClientBuilder;
import java.util.Collections;
import org.mockito.MockedStatic;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ApproovClientBuilderTest {

    private static final String PIN_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String PIN_B = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB=";

    private ReactApplicationContext context;

    @Before
    public void setUp() throws Exception {
        context = MiniSdkHarness.reactContext();
        MiniSdkHarness.resetServiceState();
    }

    @After
    public void tearDown() throws Exception {
        MiniSdkHarness.tearDown();
    }

    private static String pinsJson(String host, String pin) {
        return "\"pins\": {\"public-key-sha256\": {\"" + host + "\": [\"" + pin + "\"]}}";
    }

    private static String pinsJson(String pin) {
        return pinsJson("api.example.com", pin);
    }

    // a mini-SDK scenario protecting api.example.com, initialized through a real service
    private ApproovService serviceWithPins(String pin) throws Exception {
        MiniSdkHarness.loadScenario("\"protectedDomains\": [\"api.example.com\"], " + pinsJson(pin));
        return MiniSdkHarness.initializedService(context);
    }

    // a token fetch that delivers a dynamic configuration update with new pins, as the SDK does
    private static void deliverPinUpdate(String pins) {
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\": \"fetchApproovToken\","
            + "\"response\": {\"status\": \"SUCCESS\", \"configChanged\": true},"
            + "\"attesterConfig\": {\"protectedDomains\": [\"api.example.com\"], " + pins + "}}");
        assertTrue(Approov.fetchApproovTokenAndWait("https://api.example.com/").isConfigChanged());
    }

    @Test
    public void replacesCustomerPinsIncludingOverlappingHosts() throws Exception {
        ApproovService service = serviceWithPins(PIN_A);
        CertificatePinner customer = new CertificatePinner.Builder()
                .add("api.example.com", "sha256/" + PIN_B)
                .add("customer.example.com", "sha256/" + PIN_B).build();
        CustomClientBuilder callback = builder -> builder.certificatePinner(customer);
        ApproovClientBuilder approov = new ApproovClientBuilder(service, callback, true);
        OkHttpClient.Builder request = new OkHttpClient.Builder();
        approov.apply(request);
        CertificatePinner expected = new CertificatePinner.Builder()
                .add("api.example.com", "sha256/" + PIN_A).build();
        assertTrue(request.build().certificatePinner().getPins().isEmpty());
        assertEquals(expected.getPins(), service.getPinningInterceptor().getCertificatePinner().getPins());
    }

    @Test
    public void existingClientsSeeRotatedAndRemovedPinsWithoutRebuilding() throws Exception {
        ApproovService service = serviceWithPins(PIN_A);
        ApproovClientBuilder factoryBuilder = new ApproovClientBuilder(service, null);
        OkHttpClient.Builder baseBuilder = new OkHttpClient.Builder();
        factoryBuilder.apply(baseBuilder);
        OkHttpClient base = baseBuilder.build();
        ApproovPinningInterceptor installed = (ApproovPinningInterceptor) base.networkInterceptors().get(0);

        deliverPinUpdate(pinsJson(PIN_B));
        service.rebuildPins();
        CertificatePinner expected = new CertificatePinner.Builder()
                .add("api.example.com", "sha256/" + PIN_B).build();
        assertEquals(expected.getPins(), installed.getCertificatePinner().getPins());

        // the host's pins are removed: the mini-SDK merges a dynamic update over the scenario's
        // pins and cannot drop a host, so re-initialize with a configuration that no longer pins it
        MiniSdkHarness.loadScenario("\"protectedDomains\": [\"api.example.com\"], " + pinsJson("other.example.com", PIN_B));
        MiniSdkHarness.initialize(service, MiniSdkHarness.CONFIG);
        assertTrue(installed.getCertificatePinner().findMatchingPins("api.example.com").isEmpty());
    }

    @Test
    public void factoryHookAndEphemeralBuildersNeverFetchPins() throws Exception {
        ApproovService service = serviceWithPins(PIN_A);
        // record every SDK call made from here on, while the mini-SDK still answers them
        try (MockedStatic<Approov> sdk = mockStatic(Approov.class, CALLS_REAL_METHODS)) {
            ApproovClientBuilder factory = new ApproovClientBuilder(service, null);
            ApproovClientBuilder hook = ApproovClientBuilder.wrapping(service, null);
            for (int i = 0; i < 20; i++) {
                OkHttpClient.Builder client = new OkHttpClient.Builder();
                factory.apply(client);
                hook.apply(client);
                new ApproovClientBuilder(service, null, true).apply(client);
                assertEquals(1, client.interceptors().size());
                assertEquals(Collections.singletonList(service.getPinningInterceptor()), client.networkInterceptors());
                client.build();
            }
            sdk.verifyNoInteractions();
        }
    }

    // Regression: when the RN context is recreated (e.g. an Expo OTA reload) a new
    // ApproovService is built and its factory wraps the previous one, so a STALE
    // ApproovInterceptor bound to the old service is added to the client first. The old
    // code checked "is an ApproovInterceptor already present?" by class and skipped adding
    // the live one, leaving the client fetching tokens through a dead service (blank/stale
    // tokens). apply() must instead remove any ApproovInterceptor and install the live one.
    @Test
    public void newBuilderReplacesStaleApproovInterceptor() throws Exception {
        // two uninitialized services, as after a React Native context recreation
        ApproovService oldService = new ApproovService(context);
        ApproovService newService = new ApproovService(context);

        ApproovClientBuilder oldBuilder = new ApproovClientBuilder(oldService, null, true);
        ApproovClientBuilder newBuilder = new ApproovClientBuilder(newService, null, true);

        OkHttpClient.Builder client = new OkHttpClient.Builder();
        oldBuilder.apply(client);   // stale interceptor added first (simulates the wrapped old factory)
        // Simulate SDK wrappers copying either interceptor into the wrong chain too.
        client.addNetworkInterceptor(oldBuilder.getInterceptor());
        client.addInterceptor(oldService.getPinningInterceptor());
        newBuilder.apply(client);   // must replace both chains, not skip
        assertEquals(Collections.singletonList(newService.getPinningInterceptor()), client.networkInterceptors());

        int approovCount = 0;
        for (Interceptor i : client.interceptors())
            if (i instanceof ApproovInterceptor) approovCount++;

        assertEquals("exactly one ApproovInterceptor should remain", 1, approovCount);
        assertTrue("the live interceptor must be installed",
                client.interceptors().contains(newBuilder.getInterceptor()));
        assertFalse("the stale interceptor must be removed",
                client.interceptors().contains(oldBuilder.getInterceptor()));
    }
}
