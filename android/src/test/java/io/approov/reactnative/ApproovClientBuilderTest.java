package io.approov.reactnative;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import okhttp3.OkHttpClient;

import org.junit.Test;

public class ApproovClientBuilderTest {

    @Test
    public void longLivedBuildersRegisterTheirPinningInterceptor() {
        ApproovService service = mock(ApproovService.class);
        when(service.isInitialized()).thenReturn(false);

        ApproovClientBuilder builder = new ApproovClientBuilder(service, null, false);
        builder.apply(new OkHttpClient.Builder());

        verify(service).registerPinningInterceptor(any(ApproovPinningInterceptor.class));
    }

    @Test
    public void ephemeralBuildersSkipPinningInterceptorRegistration() {
        ApproovService service = mock(ApproovService.class);
        when(service.isInitialized()).thenReturn(false);

        ApproovClientBuilder builder = new ApproovClientBuilder(service, null, true);
        builder.apply(new OkHttpClient.Builder());

        verify(service, never()).registerPinningInterceptor(any());
    }
}
