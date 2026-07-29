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

import okhttp3.Request;

/**
 * A mutator whose per-status proceed/forward/block decision during interceptor
 * token-fetch handling is driven by an {@code int} bitmask.
 *
 * <p>{@code PolicyMutator} extends {@link ApproovDefaultMessageSigning} so that,
 * by default, HTTP Message Signing of the outbound request is preserved. When
 * constructed with {@code sign == true} (the default) it inherits the signing
 * behaviour of
 * {@link ApproovDefaultMessageSigning#handleInterceptorProcessedRequest}. When
 * constructed with {@code sign == false} it overrides that hook to forward the
 * request unmodified (unsigned) while the proceed/forward/block policy below
 * still applies.
 *
 * <p>{@link #handleInterceptorFetchTokenResult} is overridden to apply the
 * bitmask policy. Three outcomes are possible per
 * {@link Approov.TokenFetchStatus}:
 * <ul>
 *   <li>PROCEED — continue interceptor processing ({@code return true}).</li>
 *   <li>FORWARD — issue the request unmodified ({@code return false}).</li>
 *   <li>BLOCK — throw {@link ApproovFetchStatusException}.</li>
 * </ul>
 *
 * <p>A non-maskable baseline always applies:
 * <ul>
 *   <li>{@code SUCCESS} -&gt; PROCEED</li>
 *   <li>{@code UNKNOWN_URL} -&gt; FORWARD</li>
 *   <li>{@code UNPROTECTED_URL} -&gt; FORWARD</li>
 * </ul>
 * For every other (failure) status the mask decides: if the status's canonical
 * bit is set in the mask the request PROCEEDs, otherwise it is BLOCKed.
 *
 * <p>The canonical bit assignment below is name-based and stable; it is mirrored
 * by the JavaScript layer, so the values MUST NOT change.
 */
public class PolicyMutator extends ApproovDefaultMessageSigning {

    /** Canonical mask bit for {@code NO_APPROOV_SERVICE}. */
    public static final int BIT_NO_APPROOV_SERVICE     = 1 << 0;
    /** Canonical mask bit for {@code BAD_URL}. */
    public static final int BIT_BAD_URL                = 1 << 1;
    /** Canonical mask bit for {@code MITM_DETECTED}. */
    public static final int BIT_MITM_DETECTED          = 1 << 2;
    /** Canonical mask bit for {@code NO_NETWORK}. */
    public static final int BIT_NO_NETWORK             = 1 << 3;
    /** Canonical mask bit for {@code POOR_NETWORK}. */
    public static final int BIT_POOR_NETWORK           = 1 << 4;
    /** Canonical mask bit for {@code REJECTED}. */
    public static final int BIT_REJECTED               = 1 << 5;
    /** Canonical mask bit for {@code UNKNOWN_KEY}. */
    public static final int BIT_UNKNOWN_KEY            = 1 << 6;
    /** Canonical mask bit for {@code INTERNAL_ERROR}. */
    public static final int BIT_INTERNAL_ERROR         = 1 << 7;
    /** Canonical mask bit for {@code NO_NETWORK_PERMISSION}. */
    public static final int BIT_NO_NETWORK_PERMISSION  = 1 << 8;
    /** Canonical mask bit for {@code MISSING_LIB_DEPENDENCY}. */
    public static final int BIT_MISSING_LIB_DEPENDENCY = 1 << 9;
    /** Canonical mask bit for {@code DISABLED}. */
    public static final int BIT_DISABLED               = 1 << 10;

    /**
     * Every defined mask bit ORed together — the set of bits a caller may legally
     * supply. A mask containing any bit outside this set names no token-fetch
     * status, so it cannot grant PROCEED to anything; it would install a policy
     * that silently BLOCKs every failure status. Callers taking a mask from
     * outside this class (notably the JavaScript bridge) must reject such masks
     * rather than install them.
     */
    public static final int ALL_BITS =
            BIT_NO_APPROOV_SERVICE | BIT_BAD_URL | BIT_MITM_DETECTED | BIT_NO_NETWORK
            | BIT_POOR_NETWORK | BIT_REJECTED | BIT_UNKNOWN_KEY | BIT_INTERNAL_ERROR
            | BIT_NO_NETWORK_PERMISSION | BIT_MISSING_LIB_DEPENDENCY | BIT_DISABLED;

    /**
     * The outcome of a policy decision for a single token-fetch status.
     */
    enum Decision {
        /** Continue interceptor processing (the interceptor callback returns true). */
        PROCEED,
        /** Issue the request unmodified (the interceptor callback returns false). */
        FORWARD,
        /** Reject the request (the interceptor callback throws). */
        BLOCK
    }

    // Bitmask of failure statuses that are permitted to PROCEED.
    private final int proceedMask;

    // Whether the processed request should be HTTP Message Signed (true, the
    // default) or forwarded unmodified/unsigned (false).
    private final boolean sign;

    /**
     * Constructs a signing {@code PolicyMutator} driven by the supplied proceed
     * bitmask. Equivalent to {@code PolicyMutator(proceedMask, true)}.
     *
     * @param proceedMask bitmask of failure-status bits (see the {@code BIT_*}
     *                    constants) that should PROCEED rather than BLOCK
     */
    public PolicyMutator(int proceedMask) {
        this(proceedMask, true);
    }

    /**
     * Constructs a {@code PolicyMutator} driven by the supplied proceed bitmask,
     * optionally disabling HTTP Message Signing.
     *
     * @param proceedMask bitmask of failure-status bits (see the {@code BIT_*}
     *                    constants) that should PROCEED rather than BLOCK
     * @param sign        {@code true} to sign the processed request (the default,
     *                    inheriting {@link ApproovDefaultMessageSigning}),
     *                    {@code false} to forward the request unmodified and
     *                    unsigned
     */
    public PolicyMutator(int proceedMask, boolean sign) {
        setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
        this.proceedMask = proceedMask;
        this.sign = sign;
    }

    /**
     * Returns the canonical mask bit for the supplied status.
     *
     * @param status the token fetch status
     * @return the canonical bit for a maskable failure status, or {@code 0} for
     *         the non-maskable baseline statuses, {@code null}, or any status
     *         with no assigned bit
     */
    static int bitFor(Approov.TokenFetchStatus status) {
        if (status == null) {
            return 0;
        }
        switch (status) {
            case NO_APPROOV_SERVICE:
                return BIT_NO_APPROOV_SERVICE;
            case BAD_URL:
                return BIT_BAD_URL;
            case MITM_DETECTED:
                return BIT_MITM_DETECTED;
            case NO_NETWORK:
                return BIT_NO_NETWORK;
            case POOR_NETWORK:
                return BIT_POOR_NETWORK;
            case REJECTED:
                return BIT_REJECTED;
            case UNKNOWN_KEY:
                return BIT_UNKNOWN_KEY;
            case INTERNAL_ERROR:
                return BIT_INTERNAL_ERROR;
            case NO_NETWORK_PERMISSION:
                return BIT_NO_NETWORK_PERMISSION;
            case MISSING_LIB_DEPENDENCY:
                return BIT_MISSING_LIB_DEPENDENCY;
            case DISABLED:
                return BIT_DISABLED;
            default:
                // SUCCESS, UNKNOWN_URL, UNPROTECTED_URL and anything unrecognised
                // have no maskable bit.
                return 0;
        }
    }

    /**
     * Computes the policy decision for a status under the supplied proceed mask.
     *
     * <p>The baseline ({@code SUCCESS}, {@code UNKNOWN_URL},
     * {@code UNPROTECTED_URL}) is non-maskable. Every other status PROCEEDs only
     * when its canonical bit is set in {@code proceedMask}, otherwise it is
     * BLOCKed. This fails closed for a {@code null} or unrecognised status.
     *
     * @param proceedMask bitmask of failure-status bits permitted to PROCEED
     * @param status      the token fetch status to evaluate
     * @return the resulting {@link Decision}
     */
    static Decision decideFor(int proceedMask, Approov.TokenFetchStatus status) {
        if (status == null) {
            return Decision.BLOCK;
        }
        switch (status) {
            case SUCCESS:
                return Decision.PROCEED;
            case UNKNOWN_URL:
            case UNPROTECTED_URL:
                return Decision.FORWARD;
            default:
                int bit = bitFor(status);
                if (bit != 0 && (proceedMask & bit) != 0) {
                    return Decision.PROCEED;
                }
                return Decision.BLOCK;
        }
    }

    /**
     * Applies the bitmask policy to the interceptor token-fetch result.
     *
     * @param service        the ApproovService instance (unused; the decision is
     *                       driven solely by the mask)
     * @param approovResults the TokenFetchResult from Approov
     * @param url            the URL string for which the token was requested
     * @return true if processing should continue, false if the request should be
     *         forwarded unmodified
     * @throws ApproovFetchStatusException if the policy blocks the status
     */
    @Override
    @SuppressWarnings("deprecation")
    public boolean handleInterceptorFetchTokenResult(ApproovService service,
            Approov.TokenFetchResult approovResults, String url) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (decideFor(proceedMask, status)) {
            case PROCEED:
                return true;
            case FORWARD:
                return false;
            case BLOCK:
            default:
                throw new ApproovFetchStatusException(status, "PolicyMutator blocked: " + status);
        }
    }

    /**
     * Applies the signing policy to the interceptor-processed request. When this
     * mutator was constructed to sign (the default), the request is signed by the
     * inherited {@link ApproovDefaultMessageSigning} implementation. When
     * constructed with {@code sign == false}, the request is forwarded unmodified
     * and unsigned.
     *
     * @param service the ApproovService instance
     * @param request the processed request
     * @param changes the mutations applied to the request by Approov
     * @return the request to complete the interceptor step: the original request
     *         when signing is disabled, otherwise the signed request
     * @throws ApproovException if an error occurs while signing
     */
    @Override
    public Request handleInterceptorProcessedRequest(ApproovService service, Request request,
            ApproovRequestMutations changes) throws ApproovException {
        if (!sign) {
            // unsigned: forward the request unmodified
            return request;
        }
        // signed (default): delegate to the ApproovDefaultMessageSigning implementation
        return super.handleInterceptorProcessedRequest(service, request, changes);
    }

    @Override
    public String toString() {
        return "PolicyMutator{proceedMask=" + proceedMask + ", sign=" + sign + "}";
    }
}
