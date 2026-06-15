//
// MIT License
//
// Copyright (c) 2016-present, Approov Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
// (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
// publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
// THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.reactnative;

import com.criticalblue.approovsdk.Approov;

/**
 * Off-the-shelf <b>strict fail-closed</b> service mutator. It behaves like the standard
 * {@link ApproovServiceMutator#DEFAULT} policy except that it does <i>not</i> tolerate
 * {@code NO_APPROOV_SERVICE}: when the SDK cannot reach the Approov service for an otherwise-protected
 * request, the request is blocked rather than sent without a token. Use this for apps that must never
 * let a protected request through unattested, even when the Approov cloud is unreachable.
 *
 * <p>Outcomes:
 * <ul>
 *   <li>{@code SUCCESS} → proceed with a token.</li>
 *   <li>{@code UNKNOWN_URL} / {@code UNPROTECTED_URL} → proceed <b>without</b> a token (these URLs are
 *       simply not Approov-protected, so there is no attestation to require — analytics, images,
 *       third-party hosts, … are unaffected).</li>
 *   <li>{@code NO_APPROOV_SERVICE}, {@code NO_NETWORK}, {@code POOR_NETWORK}, {@code MITM_DETECTED} →
 *       block with {@link ApproovNetworkException} (retryable).</li>
 *   <li>{@code REJECTED} → block with {@link ApproovRejectionException}; any other status →
 *       {@link ApproovFetchStatusException}.</li>
 * </ul>
 *
 * <p>Select it from React with
 * {@code ApproovService.setServiceMutator(ApproovService.Mutator.REQUIRE_ATTESTATION)}, or natively
 * with {@code ApproovService.setServiceMutator(ApproovServiceMutatorRequireAttestation.SHARED)}.
 *
 * <p>For the full list of policies and message-signing configuration see <b>USAGE.md</b> ("Service
 * Mutators" and "Message Signing") and <b>README.md</b>. Compare with the standard fail-closed
 * {@link ApproovServiceMutator#DEFAULT} and the fail-open {@link ApproovServiceMutatorAlwaysProceed}.
 */
public final class ApproovServiceMutatorRequireAttestation implements ApproovServiceMutator {
    /** Shared singleton instance (the mutator is stateless). */
    public static final ApproovServiceMutatorRequireAttestation SHARED = new ApproovServiceMutatorRequireAttestation();

    private ApproovServiceMutatorRequireAttestation() {}

    @Override
    @SuppressWarnings("deprecation")
    public boolean handleInterceptorFetchTokenResult(ApproovService service,
            Approov.TokenFetchResult approovResults, String url) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case SUCCESS:
                return true;
            case UNKNOWN_URL:
            case UNPROTECTED_URL:
                // not an Approov-protected URL — there is no attestation to require; proceed token-less
                return false;
            case REJECTED:
                throw new ApproovRejectionException(
                        "Approov token fetch for " + url + ": " + status.toString() + ": "
                                + approovResults.getARC() + " " + approovResults.getRejectionReasons(),
                        approovResults.getARC(), approovResults.getRejectionReasons());
            case NO_APPROOV_SERVICE:
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                // unlike the default policy, NO_APPROOV_SERVICE is treated as a (retryable) failure
                // rather than a reason to proceed without a token
                throw new ApproovNetworkException(status,
                        "Approov token fetch for " + url + ": " + status.toString());
            default:
                throw new ApproovFetchStatusException(status,
                        "Approov token fetch for " + url + ": " + status.toString());
        }
    }

    @Override
    public String toString() {
        return "ApproovServiceMutator.REQUIRE_ATTESTATION";
    }
}
