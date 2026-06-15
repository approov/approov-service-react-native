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
 * Off-the-shelf <b>fail-open</b> service mutator: the request is <i>always</i> sent, whatever the
 * attestation outcome.
 *
 * <p>An Approov token (and any configured secure-string substitutions) is attached only when
 * attestation succeeds; for every other status — no/poor network, MITM detection, rejection,
 * unknown/unprotected URL — the request simply proceeds <b>without</b> a token rather than being
 * blocked, and the interceptor never throws. Availability is therefore never affected by Approov;
 * use this only when your backend is responsible for enforcing token presence/validity.
 *
 * <p>Select it from React with
 * {@code ApproovService.setServiceMutator(ApproovService.Mutator.ALWAYS_PROCEED)}, or natively with
 * {@code ApproovService.setServiceMutator(ApproovServiceMutatorAlwaysProceed.SHARED)}.
 *
 * <p>For the full list of policies and message-signing configuration see <b>USAGE.md</b> ("Service
 * Mutators" and "Message Signing") and <b>README.md</b>. Compare with the standard fail-closed
 * {@link ApproovServiceMutator#DEFAULT} and the strict {@link ApproovServiceMutatorRequireAttestation}.
 */
public final class ApproovServiceMutatorAlwaysProceed implements ApproovServiceMutator {
    /** Shared singleton instance (the mutator is stateless). */
    public static final ApproovServiceMutatorAlwaysProceed SHARED = new ApproovServiceMutatorAlwaysProceed();

    private ApproovServiceMutatorAlwaysProceed() {}

    @Override
    public boolean handleInterceptorFetchTokenResult(ApproovService service,
            Approov.TokenFetchResult approovResults, String url) throws ApproovException {
        // attach a token only on success; never throw — proceed token-less for any other status
        return approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS;
    }

    @Override
    public boolean handleInterceptorHeaderSubstitutionResult(ApproovService service,
            Approov.TokenFetchResult approovResults, String header) throws ApproovException {
        // substitute only on a successful lookup; never block on a failed/rejected secure string
        return approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS;
    }

    @Override
    public boolean handleInterceptorQueryParamSubstitutionResult(ApproovService service,
            Approov.TokenFetchResult approovResults, String queryKey) throws ApproovException {
        return approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS;
    }

    @Override
    public String toString() {
        return "ApproovServiceMutator.ALWAYS_PROCEED";
    }
}
