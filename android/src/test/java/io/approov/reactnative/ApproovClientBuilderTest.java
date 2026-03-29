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
}
