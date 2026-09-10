package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    public void replacesCustomerPinsIncludingOverlappingHosts() {
        ApproovService service = mock(ApproovService.class);
        when(service.isApproovEnabled()).thenReturn(true);
        CertificatePinner customer = new CertificatePinner.Builder()
                .add("api.example.com", "sha256/" + PIN_B)
                .add("customer.example.com", "sha256/" + PIN_B).build();
        CustomClientBuilder callback = builder -> builder.certificatePinner(customer);

        try (MockedStatic<Approov> sdk = mockStatic(Approov.class)) {
            sdk.when(() -> Approov.getPins("public-key-sha256")).thenReturn(
                    Collections.singletonMap("api.example.com", Collections.singletonList(PIN_A)));
            ApproovClientBuilder approov = new ApproovClientBuilder(service, callback, true);
            OkHttpClient.Builder request = new OkHttpClient.Builder();
            approov.apply(request);
            CertificatePinner expected = new CertificatePinner.Builder()
                    .add("api.example.com", "sha256/" + PIN_A).build();
            assertEquals(expected.getPins(), request.build().certificatePinner().getPins());
        }
    }

    @Test
    public void requestHookDropsRotatedAndRemovedPinsFromBaseClient() {
        ApproovService service = mock(ApproovService.class);
        when(service.isApproovEnabled()).thenReturn(true);
        try (MockedStatic<Approov> sdk = mockStatic(Approov.class)) {
            sdk.when(() -> Approov.getPins("public-key-sha256")).thenReturn(
                    Collections.singletonMap("api.example.com", Collections.singletonList(PIN_A)));
            ApproovClientBuilder factoryBuilder = new ApproovClientBuilder(service, null);
            ApproovClientBuilder hook = ApproovClientBuilder.wrapping(service, null, factoryBuilder);
            OkHttpClient.Builder baseBuilder = new OkHttpClient.Builder();
            factoryBuilder.apply(baseBuilder);
            OkHttpClient base = baseBuilder.build();

            sdk.when(() -> Approov.getPins("public-key-sha256")).thenReturn(
                    Collections.singletonMap("api.example.com", Collections.singletonList(PIN_B)));
            factoryBuilder.approovPinsUpdated();
            OkHttpClient.Builder request = base.newBuilder();
            hook.apply(request);
            CertificatePinner expected = new CertificatePinner.Builder()
                    .add("api.example.com", "sha256/" + PIN_B).build();
            assertEquals(expected.getPins(), request.build().certificatePinner().getPins());

            sdk.when(() -> Approov.getPins("public-key-sha256")).thenReturn(Collections.emptyMap());
            factoryBuilder.approovPinsUpdated();
            request = base.newBuilder();
            hook.apply(request);
            assertTrue(request.build().certificatePinner().getPins().isEmpty());
        }
    }

    @Test
    public void longLivedBuildersRegisterForPinChangeNotifications() {
        ApproovService service = mock(ApproovService.class);
        when(service.isInitialized()).thenReturn(false);

        ApproovClientBuilder builder = new ApproovClientBuilder(service, null, false);
        builder.apply(new OkHttpClient.Builder());

        verify(service).addPinChangeListener(builder);
    }

    @Test
    public void ephemeralBuildersSkipPinChangeListenerRegistration() {
        ApproovService service = mock(ApproovService.class);
        when(service.isInitialized()).thenReturn(false);

        ApproovClientBuilder builder = new ApproovClientBuilder(service, null, true);
        builder.apply(new OkHttpClient.Builder());

        verify(service, never()).addPinChangeListener(any());
    }

    // Regression: when the RN context is recreated (e.g. an Expo OTA reload) a new
    // ApproovService is built and its factory wraps the previous one, so a STALE
    // ApproovInterceptor bound to the old service is added to the client first. The old
    // code checked "is an ApproovInterceptor already present?" by class and skipped adding
    // the live one, leaving the client fetching tokens through a dead service (blank/stale
    // tokens). apply() must instead remove any ApproovInterceptor and install the live one.
    @Test
    public void newBuilderReplacesStaleApproovInterceptor() {
        ApproovService oldService = mock(ApproovService.class);
        ApproovService newService = mock(ApproovService.class);
        when(oldService.isInitialized()).thenReturn(false);
        when(newService.isInitialized()).thenReturn(false);

        ApproovClientBuilder oldBuilder = new ApproovClientBuilder(oldService, null, true);
        ApproovClientBuilder newBuilder = new ApproovClientBuilder(newService, null, true);

        OkHttpClient.Builder client = new OkHttpClient.Builder();
        oldBuilder.apply(client);   // stale interceptor added first (simulates the wrapped old factory)
        newBuilder.apply(client);   // must replace it, not skip

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
