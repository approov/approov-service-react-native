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
    public void handleInterceptorFetchTokenResultDefaultsToFailClosedForMitmDetected() {
        ApproovService service = mock(ApproovService.class);
        when(service.getUseApproovStatusIfNoToken()).thenReturn(false);

        ApproovNetworkException error = assertThrows(
            ApproovNetworkException.class,
            () -> mutator.handleInterceptorFetchTokenResult(
                service,
                mockResult(Approov.TokenFetchStatus.MITM_DETECTED),
                "example.com"
            )
        );

        assertTrue(error.getMessage().contains("MITM_DETECTED"));
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

    // ----- off-the-shelf mutator: ALWAYS_PROCEED (fail-open) -----

    @Test
    public void alwaysProceedAttachesTokenOnlyOnSuccessAndNeverThrows() throws Exception {
        ApproovServiceMutator alwaysProceed = ApproovServiceMutatorAlwaysProceed.SHARED;
        ApproovService service = mock(ApproovService.class);

        assertTrue(alwaysProceed.handleInterceptorFetchTokenResult(
            service, mockResult(Approov.TokenFetchStatus.SUCCESS), "example.com"));
        // every non-success status proceeds token-less and never throws (availability preserved)
        assertFalse(alwaysProceed.handleInterceptorFetchTokenResult(
            service, mockResult(Approov.TokenFetchStatus.MITM_DETECTED), "example.com"));
        assertFalse(alwaysProceed.handleInterceptorFetchTokenResult(
            service, mockResult(Approov.TokenFetchStatus.NO_NETWORK), "example.com"));
        assertFalse(alwaysProceed.handleInterceptorFetchTokenResult(
            service, mockResult(Approov.TokenFetchStatus.REJECTED), "example.com"));
        assertFalse(alwaysProceed.handleInterceptorFetchTokenResult(
            service, mockResult(Approov.TokenFetchStatus.NO_APPROOV_SERVICE), "example.com"));
    }

    @Test
    public void alwaysProceedNeverThrowsOnSubstitutionFailures() throws Exception {
        ApproovServiceMutator alwaysProceed = ApproovServiceMutatorAlwaysProceed.SHARED;
        ApproovService service = mock(ApproovService.class);

        assertTrue(alwaysProceed.handleInterceptorHeaderSubstitutionResult(
            service, mockResult(Approov.TokenFetchStatus.SUCCESS), "Authorization"));
        assertFalse(alwaysProceed.handleInterceptorHeaderSubstitutionResult(
            service, mockResult(Approov.TokenFetchStatus.REJECTED), "Authorization"));
        assertFalse(alwaysProceed.handleInterceptorQueryParamSubstitutionResult(
            service, mockResult(Approov.TokenFetchStatus.NO_NETWORK), "tok"));
    }

    // ----- off-the-shelf mutator: REQUIRE_ATTESTATION (strict fail-closed) -----

    @Test
    public void requireAttestationProceedsWithTokenOnSuccessAndTokenlessForUnprotected() throws Exception {
        ApproovServiceMutator strict = ApproovServiceMutatorRequireAttestation.SHARED;
        ApproovService service = mock(ApproovService.class);

        assertTrue(strict.handleInterceptorFetchTokenResult(
            service, mockResult(Approov.TokenFetchStatus.SUCCESS), "example.com"));
        assertFalse(strict.handleInterceptorFetchTokenResult(
            service, mockResult(Approov.TokenFetchStatus.UNKNOWN_URL), "example.com"));
        assertFalse(strict.handleInterceptorFetchTokenResult(
            service, mockResult(Approov.TokenFetchStatus.UNPROTECTED_URL), "example.com"));
    }

    @Test
    public void requireAttestationBlocksNoApproovServiceEvenWhenStatusFallbackConfigured() {
        ApproovServiceMutator strict = ApproovServiceMutatorRequireAttestation.SHARED;
        ApproovService service = mock(ApproovService.class);
        // unlike DEFAULT, the strict policy blocks NO_APPROOV_SERVICE regardless of this setting
        when(service.getUseApproovStatusIfNoToken()).thenReturn(true);

        ApproovNetworkException error = assertThrows(
            ApproovNetworkException.class,
            () -> strict.handleInterceptorFetchTokenResult(
                service, mockResult(Approov.TokenFetchStatus.NO_APPROOV_SERVICE), "example.com"));
        assertTrue(error.getMessage().contains("NO_APPROOV_SERVICE"));
    }

    @Test
    public void requireAttestationThrowsRejectionForRejectedStatus() {
        ApproovServiceMutator strict = ApproovServiceMutatorRequireAttestation.SHARED;
        ApproovService service = mock(ApproovService.class);

        ApproovRejectionException error = assertThrows(
            ApproovRejectionException.class,
            () -> strict.handleInterceptorFetchTokenResult(
                service, mockResult(Approov.TokenFetchStatus.REJECTED), "example.com"));
        assertTrue(error.getMessage().contains("REJECTED"));
    }
}
