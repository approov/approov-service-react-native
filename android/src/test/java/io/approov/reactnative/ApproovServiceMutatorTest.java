package io.approov.reactnative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.criticalblue.approovsdk.Approov;

import java.util.HashMap;
import java.util.regex.Pattern;

import okhttp3.Request;

import org.junit.Test;

public class ApproovServiceMutatorTest {

    private final ApproovServiceMutator mutator = ApproovServiceMutator.DEFAULT;

    private Approov.TokenFetchResult mockResult(Approov.TokenFetchStatus status) {
        Approov.TokenFetchResult result = mock(Approov.TokenFetchResult.class);
        when(result.getStatus()).thenReturn(status);
        when(result.getARC()).thenReturn("ARC123");
        when(result.getRejectionReasons()).thenReturn("rooted,hooked");
        return result;
    }

    @Test
    public void handlePrecheckAllowsSuccessAndUnknownKey() throws Exception {
        mutator.handlePrecheckResult(mockResult(Approov.TokenFetchStatus.SUCCESS));
        mutator.handlePrecheckResult(mockResult(Approov.TokenFetchStatus.UNKNOWN_KEY));
    }

    @Test
    public void handlePrecheckThrowsRejectionExceptionForRejectedStatus() {
        ApproovRejectionException error = assertThrows(
            ApproovRejectionException.class,
            () -> mutator.handlePrecheckResult(mockResult(Approov.TokenFetchStatus.REJECTED))
        );

        assertTrue(error.getMessage().contains("REJECTED"));
        assertTrue(error.getMessage().contains("ARC123"));
    }

    @Test
    public void handlePrecheckThrowsNetworkExceptionForNetworkFailures() {
        ApproovNetworkException error = assertThrows(
            ApproovNetworkException.class,
            () -> mutator.handlePrecheckResult(mockResult(Approov.TokenFetchStatus.NO_NETWORK))
        );

        assertTrue(error.getMessage().contains("NO_NETWORK"));
    }

    @Test
    public void handleFetchTokenThrowsFetchStatusExceptionForPermanentFailures() {
        ApproovFetchStatusException error = assertThrows(
            ApproovFetchStatusException.class,
            () -> mutator.handleFetchTokenResult(mockResult(Approov.TokenFetchStatus.BAD_URL))
        );

        assertTrue(error.getMessage().contains("BAD_URL"));
    }

    @Test
    public void handleFetchSecureStringAllowsUnknownKeyButRejectsRejectedResults() throws Exception {
        mutator.handleFetchSecureStringResult(
            mockResult(Approov.TokenFetchStatus.UNKNOWN_KEY),
            "lookup",
            "api-key"
        );

        ApproovRejectionException error = assertThrows(
            ApproovRejectionException.class,
            () -> mutator.handleFetchSecureStringResult(
                mockResult(Approov.TokenFetchStatus.REJECTED),
                "lookup",
                "api-key"
            )
        );

        assertTrue(error.getMessage().contains("fetchSecureString lookup for api-key"));
    }

    @Test
    public void handleInterceptorShouldProcessRequestSkipsExcludedUrls() throws Exception {
        ApproovService service = mock(ApproovService.class);
        HashMap<String, Pattern> exclusions = new HashMap<>();
        exclusions.put("^https://example\\.com/private", Pattern.compile("^https://example\\.com/private"));
        when(service.getExclusionURLRegexs()).thenReturn(exclusions);

        Request excluded = new Request.Builder()
            .url("https://example.com/private/data")
            .build();
        Request included = new Request.Builder()
            .url("https://example.com/public/data")
            .build();

        assertFalse(mutator.handleInterceptorShouldProcessRequest(service, excluded));
        assertTrue(mutator.handleInterceptorShouldProcessRequest(service, included));
    }

    @Test
    public void handleInterceptorFetchTokenResultAllowsConfiguredNoApproovServiceFallback() throws Exception {
        ApproovService service = mock(ApproovService.class);
        when(service.getUseApproovStatusIfNoToken()).thenReturn(true);

        assertTrue(
            mutator.handleInterceptorFetchTokenResult(
                service,
                mockResult(Approov.TokenFetchStatus.NO_APPROOV_SERVICE),
                "example.com"
            )
        );
    }

    @Test
    public void handleInterceptorFetchTokenResultSkipsUnknownAndUnprotectedUrls() throws Exception {
        ApproovService service = mock(ApproovService.class);
        when(service.getUseApproovStatusIfNoToken()).thenReturn(false);

        assertFalse(
            mutator.handleInterceptorFetchTokenResult(
                service,
                mockResult(Approov.TokenFetchStatus.UNKNOWN_URL),
                "example.com"
            )
        );
        assertFalse(
            mutator.handleInterceptorFetchTokenResult(
                service,
                mockResult(Approov.TokenFetchStatus.UNPROTECTED_URL),
                "example.com"
            )
        );
    }
}
