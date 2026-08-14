package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.criticalblue.approovsdk.Approov;

import okhttp3.Request;

import org.junit.Test;

/**
 * Unit tests for {@link PolicyMutator}.
 *
 * The decision logic lives in the pure, package-visible static helpers
 * {@link PolicyMutator#decideFor(int, Approov.TokenFetchStatus)} and
 * {@link PolicyMutator#bitFor(Approov.TokenFetchStatus)}, so the bulk of the
 * coverage needs no instance construction and no Android runtime. A single
 * constructed-instance test verifies the PROCEED/FORWARD/BLOCK to
 * return-value/throw mapping, and a group of signing-composition tests verify
 * that {@code sign == true} (including the 1-arg constructor default) delegates
 * to the inherited {@link ApproovDefaultMessageSigning} signing hook while
 * {@code sign == false} forwards the request unmodified and unsigned.
 */
public class PolicyMutatorTest {

    // Canonical bit assignment (name-based). These MUST be mirrored by the
    // Task 2 JS layer, so they are pinned here as literals independent of the
    // production constant names.
    private static final int NO_APPROOV_SERVICE     = 1 << 0;
    private static final int BAD_URL                = 1 << 1;
    private static final int MITM_DETECTED          = 1 << 2;
    private static final int NO_NETWORK             = 1 << 3;
    private static final int POOR_NETWORK           = 1 << 4;
    private static final int REJECTED               = 1 << 5;
    private static final int UNKNOWN_KEY            = 1 << 6;
    private static final int INTERNAL_ERROR         = 1 << 7;
    private static final int NO_NETWORK_PERMISSION  = 1 << 8;
    private static final int MISSING_LIB_DEPENDENCY = 1 << 9;
    private static final int DISABLED               = 1 << 10;

    private static final Approov.TokenFetchStatus[] FAILURE_STATUSES = {
        Approov.TokenFetchStatus.NO_APPROOV_SERVICE,
        Approov.TokenFetchStatus.BAD_URL,
        Approov.TokenFetchStatus.MITM_DETECTED,
        Approov.TokenFetchStatus.NO_NETWORK,
        Approov.TokenFetchStatus.POOR_NETWORK,
        Approov.TokenFetchStatus.REJECTED,
        Approov.TokenFetchStatus.UNKNOWN_KEY,
        Approov.TokenFetchStatus.INTERNAL_ERROR,
        Approov.TokenFetchStatus.NO_NETWORK_PERMISSION,
        Approov.TokenFetchStatus.MISSING_LIB_DEPENDENCY,
        Approov.TokenFetchStatus.DISABLED,
    };

    private Approov.TokenFetchResult mockResult(Approov.TokenFetchStatus status) {
        Approov.TokenFetchResult result = mock(Approov.TokenFetchResult.class);
        when(result.getStatus()).thenReturn(status);
        return result;
    }

    // --- pure static helper: decideFor ---

    @Test
    public void decideForRepresentativeMaskProceedsForwardsAndBlocks() {
        int mask = NO_APPROOV_SERVICE | BAD_URL;

        // Non-maskable baseline.
        assertEquals(PolicyMutator.Decision.PROCEED,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.SUCCESS));
        assertEquals(PolicyMutator.Decision.FORWARD,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.UNKNOWN_URL));
        assertEquals(PolicyMutator.Decision.FORWARD,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.UNPROTECTED_URL));

        // Failures whose bit is set proceed.
        assertEquals(PolicyMutator.Decision.PROCEED,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.NO_APPROOV_SERVICE));
        assertEquals(PolicyMutator.Decision.PROCEED,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.BAD_URL));

        // Failures whose bit is clear block.
        assertEquals(PolicyMutator.Decision.BLOCK,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.MITM_DETECTED));
        assertEquals(PolicyMutator.Decision.BLOCK,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.REJECTED));
    }

    @Test
    public void decideForZeroMaskBlocksEveryFailureButKeepsBaseline() {
        int mask = 0;

        assertEquals(PolicyMutator.Decision.PROCEED,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.SUCCESS));
        assertEquals(PolicyMutator.Decision.FORWARD,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.UNKNOWN_URL));
        assertEquals(PolicyMutator.Decision.FORWARD,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.UNPROTECTED_URL));

        for (Approov.TokenFetchStatus status : FAILURE_STATUSES) {
            assertEquals("expected BLOCK for " + status,
                PolicyMutator.Decision.BLOCK, PolicyMutator.decideFor(mask, status));
        }
    }

    @Test
    public void decideForFullMaskProceedsForEveryFailureAndKeepsBaseline() {
        int mask = NO_APPROOV_SERVICE | BAD_URL | MITM_DETECTED | NO_NETWORK | POOR_NETWORK
            | REJECTED | UNKNOWN_KEY | INTERNAL_ERROR | NO_NETWORK_PERMISSION
            | MISSING_LIB_DEPENDENCY | DISABLED;

        for (Approov.TokenFetchStatus status : FAILURE_STATUSES) {
            assertEquals("expected PROCEED for " + status,
                PolicyMutator.Decision.PROCEED, PolicyMutator.decideFor(mask, status));
        }

        assertEquals(PolicyMutator.Decision.PROCEED,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.SUCCESS));
        assertEquals(PolicyMutator.Decision.FORWARD,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.UNKNOWN_URL));
        assertEquals(PolicyMutator.Decision.FORWARD,
            PolicyMutator.decideFor(mask, Approov.TokenFetchStatus.UNPROTECTED_URL));
    }

    @Test
    public void decideForFullMaskFailsClosedForStatusWithoutBit() {
        // A status with no proceed bit blocks even under a full mask (ALL_BITS): decideFor's
        // default branch grants PROCEED only when bitFor(status) is set in the mask, so an
        // unrecognised status routes to BLOCK. This is the fail-closed guarantee that keeps
        // ALWAYS_PROCEED from proceeding on something the policy does not recognise, and it is
        // what will block any status a later SDK adds without a bit: it takes the same default
        // path. We assert it with a null status because a status not defined by the SDK this
        // layer compiles against (3.5.3) cannot be named here. Matches okhttp/urlsession, whose
        // default token-fetch handling blocks (default: throw / .ShouldFail) any status not
        // explicitly allowed.
        int fullMask = NO_APPROOV_SERVICE | BAD_URL | MITM_DETECTED | NO_NETWORK | POOR_NETWORK
            | REJECTED | UNKNOWN_KEY | INTERNAL_ERROR | NO_NETWORK_PERMISSION
            | MISSING_LIB_DEPENDENCY | DISABLED;

        assertEquals("a status with no proceed bit must BLOCK even with a full mask",
            PolicyMutator.Decision.BLOCK,
            PolicyMutator.decideFor(fullMask, null));
    }

    // --- pure static helper: bitFor ---

    @Test
    public void bitForMatchesCanonicalAssignment() {
        assertEquals(1 << 0, PolicyMutator.bitFor(Approov.TokenFetchStatus.NO_APPROOV_SERVICE));
        assertEquals(1 << 1, PolicyMutator.bitFor(Approov.TokenFetchStatus.BAD_URL));
        assertEquals(1 << 2, PolicyMutator.bitFor(Approov.TokenFetchStatus.MITM_DETECTED));
        assertEquals(1 << 3, PolicyMutator.bitFor(Approov.TokenFetchStatus.NO_NETWORK));
        assertEquals(1 << 4, PolicyMutator.bitFor(Approov.TokenFetchStatus.POOR_NETWORK));
        assertEquals(1 << 5, PolicyMutator.bitFor(Approov.TokenFetchStatus.REJECTED));
        assertEquals(1 << 6, PolicyMutator.bitFor(Approov.TokenFetchStatus.UNKNOWN_KEY));
        assertEquals(1 << 7, PolicyMutator.bitFor(Approov.TokenFetchStatus.INTERNAL_ERROR));
        assertEquals(1 << 8, PolicyMutator.bitFor(Approov.TokenFetchStatus.NO_NETWORK_PERMISSION));
        assertEquals(1 << 9, PolicyMutator.bitFor(Approov.TokenFetchStatus.MISSING_LIB_DEPENDENCY));
        assertEquals(1 << 10, PolicyMutator.bitFor(Approov.TokenFetchStatus.DISABLED));
    }

    @Test
    public void bitForReturnsZeroForNonMaskableStatuses() {
        assertEquals(0, PolicyMutator.bitFor(Approov.TokenFetchStatus.SUCCESS));
        assertEquals(0, PolicyMutator.bitFor(Approov.TokenFetchStatus.UNKNOWN_URL));
        assertEquals(0, PolicyMutator.bitFor(Approov.TokenFetchStatus.UNPROTECTED_URL));
    }

    // --- constructed instance: decision to return-value / throw mapping ---

    @Test
    public void instanceMapsDecisionsToReturnValuesAndThrows() throws Exception {
        ApproovService service = mock(ApproovService.class);
        PolicyMutator mutator = new PolicyMutator(NO_APPROOV_SERVICE | BAD_URL);
        String url = "https://api.example.com/resource";

        // PROCEED -> true
        assertTrue(mutator.handleInterceptorFetchTokenResult(
            service, mockResult(Approov.TokenFetchStatus.SUCCESS), url));
        assertTrue(mutator.handleInterceptorFetchTokenResult(
            service, mockResult(Approov.TokenFetchStatus.NO_APPROOV_SERVICE), url));

        // FORWARD -> false
        assertFalse(mutator.handleInterceptorFetchTokenResult(
            service, mockResult(Approov.TokenFetchStatus.UNKNOWN_URL), url));
        assertFalse(mutator.handleInterceptorFetchTokenResult(
            service, mockResult(Approov.TokenFetchStatus.UNPROTECTED_URL), url));

        // BLOCK -> throws ApproovFetchStatusException carrying the status + message
        ApproovFetchStatusException error = assertThrows(
            ApproovFetchStatusException.class,
            () -> mutator.handleInterceptorFetchTokenResult(
                service, mockResult(Approov.TokenFetchStatus.MITM_DETECTED), url));
        assertEquals(Approov.TokenFetchStatus.MITM_DETECTED, error.getTokenFetchStatus());
        assertTrue(error.getMessage().contains("PolicyMutator blocked"));
        assertTrue(error.getMessage().contains("MITM_DETECTED"));
    }

    @Test
    public void substitutionHandlersSkipMaskedFailuresAndBlockUnmaskedFailures() throws Exception {
        ApproovService service = mock(ApproovService.class);
        PolicyMutator mutator = new PolicyMutator(NO_APPROOV_SERVICE);

        assertTrue(mutator.handleInterceptorHeaderSubstitutionResult(
            service, mockResult(Approov.TokenFetchStatus.SUCCESS), "Api-Key"));
        assertFalse(mutator.handleInterceptorHeaderSubstitutionResult(
            service, mockResult(Approov.TokenFetchStatus.NO_APPROOV_SERVICE), "Api-Key"));
        assertFalse(mutator.handleInterceptorQueryParamSubstitutionResult(
            service, mockResult(Approov.TokenFetchStatus.NO_APPROOV_SERVICE), "api_key"));

        ApproovFetchStatusException error = assertThrows(
            ApproovFetchStatusException.class,
            () -> mutator.handleInterceptorHeaderSubstitutionResult(
                service, mockResult(Approov.TokenFetchStatus.NO_NETWORK), "Api-Key"));
        assertEquals(Approov.TokenFetchStatus.NO_NETWORK, error.getTokenFetchStatus());
        assertTrue(error.getMessage().contains("PolicyMutator blocked header substitution"));
    }

    // --- signing composition: the sign flag drives the processed-request hook ---

    @Test
    @SuppressWarnings("deprecation")
    public void signFalseForwardsRequestUnsignedWithoutDelegatingToSigner() throws Exception {
        ApproovService service = mock(ApproovService.class);
        Request request = new Request.Builder().url("https://api.example.com/resource").build();
        ApproovRequestMutations changes = new ApproovRequestMutations();

        // Spy so we can prove the inherited signing hook (processedRequest) is not reached.
        PolicyMutator mutator = spy(new PolicyMutator(NO_APPROOV_SERVICE, false));

        Request result = mutator.handleInterceptorProcessedRequest(service, request, changes);

        // sign:false forwards the exact same request instance, unmodified...
        assertSame(request, result);
        // ...and never delegates to the ApproovDefaultMessageSigning signing path.
        verify(mutator, never()).processedRequest(request, changes);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void signTrueDelegatesToInheritedMessageSigning() throws Exception {
        ApproovService service = mock(ApproovService.class);
        Request request = new Request.Builder().url("https://api.example.com/resource").build();
        Request signed = new Request.Builder().url("https://api.example.com/resource")
            .header("Signature", "install=:sig:").build();
        ApproovRequestMutations changes = new ApproovRequestMutations();

        PolicyMutator mutator = spy(new PolicyMutator(NO_APPROOV_SERVICE, true));
        assertTrue(mutator instanceof ApproovDefaultMessageSigning);
        // super.handleInterceptorProcessedRequest delegates to the inherited
        // processedRequest hook; stub it to stand in for a fully signed request.
        doReturn(signed).when(mutator).processedRequest(request, changes);

        Request result = mutator.handleInterceptorProcessedRequest(service, request, changes);

        // sign:true returns the signer's (distinct, signed) output, not the input.
        assertSame(signed, result);
        verify(mutator).processedRequest(request, changes);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void oneArgConstructorSignsByDefault() throws Exception {
        ApproovService service = mock(ApproovService.class);
        Request request = new Request.Builder().url("https://api.example.com/resource").build();
        Request signed = new Request.Builder().url("https://api.example.com/resource")
            .header("Signature", "install=:sig:").build();
        ApproovRequestMutations changes = new ApproovRequestMutations();

        // The 1-arg constructor must behave identically to sign == true.
        PolicyMutator mutator = spy(new PolicyMutator(NO_APPROOV_SERVICE));
        doReturn(signed).when(mutator).processedRequest(request, changes);

        Request result = mutator.handleInterceptorProcessedRequest(service, request, changes);

        assertSame(signed, result);
        verify(mutator).processedRequest(request, changes);
    }
}
