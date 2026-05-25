package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import okhttp3.OkHttpClient;

import org.junit.Test;

public class ApproovClientBuilderTest {

    @Test
    public void applyAddsApproovInterceptorToApplicationInterceptors() {
        ApproovService service = mock(ApproovService.class);
        when(service.isInitialized()).thenReturn(false);

        ApproovClientBuilder builder = new ApproovClientBuilder(service, null);
        OkHttpClient.Builder okBuilder = new OkHttpClient.Builder();
        builder.apply(okBuilder);
        OkHttpClient client = okBuilder.build();

        boolean found = false;
        for (okhttp3.Interceptor interceptor : client.interceptors()) {
            if (interceptor instanceof ApproovInterceptor) {
                found = true;
                break;
            }
        }
        assertTrue("ApproovInterceptor should be in application interceptors", found);
    }

    @Test
    public void applyAddsApproovPinningInterceptorToNetworkInterceptors() {
        ApproovService service = mock(ApproovService.class);
        when(service.isInitialized()).thenReturn(false);

        ApproovClientBuilder builder = new ApproovClientBuilder(service, null);
        OkHttpClient.Builder okBuilder = new OkHttpClient.Builder();
        builder.apply(okBuilder);
        OkHttpClient client = okBuilder.build();

        boolean found = false;
        for (okhttp3.Interceptor interceptor : client.networkInterceptors()) {
            if (interceptor instanceof ApproovPinningInterceptor) {
                found = true;
                break;
            }
        }
        assertTrue("ApproovPinningInterceptor should be in network interceptors", found);
    }

    @Test
    public void applyIsIdempotentAndDoesNotStackDuplicateInterceptors() {
        ApproovService service = mock(ApproovService.class);
        when(service.isInitialized()).thenReturn(false);

        ApproovClientBuilder builder = new ApproovClientBuilder(service, null);
        OkHttpClient.Builder okBuilder = new OkHttpClient.Builder();
        // simulate both OkHttpClientFactory and setCustomClientBuilder paths firing
        builder.apply(okBuilder);
        builder.apply(okBuilder);
        OkHttpClient client = okBuilder.build();

        long approovCount = client.interceptors().stream()
                .filter(i -> i instanceof ApproovInterceptor)
                .count();
        assertEquals("apply() called twice must not stack ApproovInterceptor", 1, approovCount);

        long pinningCount = client.networkInterceptors().stream()
                .filter(i -> i instanceof ApproovPinningInterceptor)
                .count();
        assertEquals("apply() called twice must not stack ApproovPinningInterceptor", 1, pinningCount);
    }

    @Test
    public void getPinningInterceptorReturnsTheInstalledInstance() {
        ApproovService service = mock(ApproovService.class);
        when(service.isInitialized()).thenReturn(false);

        ApproovClientBuilder builder = new ApproovClientBuilder(service, null);
        OkHttpClient.Builder okBuilder = new OkHttpClient.Builder();
        builder.apply(okBuilder);

        assertNotNull("getPinningInterceptor() should return a non-null instance",
                builder.getPinningInterceptor());
    }
}
