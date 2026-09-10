package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import okhttp3.Interceptor;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;

import com.criticalblue.approovsdk.Approov;
import com.facebook.react.modules.network.NetworkingModule.CustomClientBuilder;
import java.util.Collections;
import org.mockito.MockedStatic;
import org.junit.Test;

public class ApproovClientBuilderTest {

    private static final String PIN_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String PIN_B = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB=";

    private ApproovService serviceWithSharedPins() {
        ApproovService service = mock(ApproovService.class);
        when(service.isApproovEnabled()).thenReturn(true);
        when(service.getPinningInterceptor()).thenReturn(new ApproovPinningInterceptor());
        return service;
    }

    @Test
    public void replacesCustomerPinsIncludingOverlappingHosts() {
        ApproovService service = serviceWithSharedPins();
        CertificatePinner customer = new CertificatePinner.Builder()
                .add("api.example.com", "sha256/" + PIN_B)
                .add("customer.example.com", "sha256/" + PIN_B).build();
        CustomClientBuilder callback = builder -> builder.certificatePinner(customer);
        try (MockedStatic<Approov> sdk = mockStatic(Approov.class)) {
            sdk.when(() -> Approov.getPins("public-key-sha256")).thenReturn(
                    Collections.singletonMap("api.example.com", Collections.singletonList(PIN_A)));
            service.getPinningInterceptor().rebuildPins(service);
            ApproovClientBuilder approov = new ApproovClientBuilder(service, callback, true);
            OkHttpClient.Builder request = new OkHttpClient.Builder();
            approov.apply(request);
            CertificatePinner expected = new CertificatePinner.Builder()
                    .add("api.example.com", "sha256/" + PIN_A).build();
            assertTrue(request.build().certificatePinner().getPins().isEmpty());
            assertEquals(expected.getPins(), service.getPinningInterceptor().getCertificatePinner().getPins());
        }
    }

    @Test
    public void existingClientsSeeRotatedAndRemovedPinsWithoutRebuilding() {
        ApproovService service = serviceWithSharedPins();
        try (MockedStatic<Approov> sdk = mockStatic(Approov.class)) {
            sdk.when(() -> Approov.getPins("public-key-sha256")).thenReturn(
                    Collections.singletonMap("api.example.com", Collections.singletonList(PIN_A)));
            service.getPinningInterceptor().rebuildPins(service);
            ApproovClientBuilder factoryBuilder = new ApproovClientBuilder(service, null);
            OkHttpClient.Builder baseBuilder = new OkHttpClient.Builder();
            factoryBuilder.apply(baseBuilder);
            OkHttpClient base = baseBuilder.build();
            ApproovPinningInterceptor installed = (ApproovPinningInterceptor) base.networkInterceptors().get(0);

            sdk.when(() -> Approov.getPins("public-key-sha256")).thenReturn(
                    Collections.singletonMap("api.example.com", Collections.singletonList(PIN_B)));
            service.getPinningInterceptor().rebuildPins(service);
            CertificatePinner expected = new CertificatePinner.Builder()
                    .add("api.example.com", "sha256/" + PIN_B).build();
            assertEquals(expected.getPins(), installed.getCertificatePinner().getPins());

            sdk.when(() -> Approov.getPins("public-key-sha256")).thenReturn(Collections.emptyMap());
            service.getPinningInterceptor().rebuildPins(service);
            assertTrue(installed.getCertificatePinner().getPins().isEmpty());
        }
    }

    @Test
    public void factoryHookAndEphemeralBuildersNeverFetchPins() {
        ApproovService service = serviceWithSharedPins();
        try (MockedStatic<Approov> sdk = mockStatic(Approov.class)) {
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
    public void newBuilderReplacesStaleApproovInterceptor() {
        ApproovService oldService = serviceWithSharedPins();
        ApproovService newService = serviceWithSharedPins();
        when(oldService.isInitialized()).thenReturn(false);
        when(newService.isInitialized()).thenReturn(false);

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
