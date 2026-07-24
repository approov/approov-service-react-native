// MIT License
//
// Copyright (c) 2025-present, Approov Ltd.
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

import Approov
import Foundation

/**
 * A mutator whose per-status proceed/forward/block decision during interceptor
 * token-fetch handling is driven by an `Int32` bitmask.
 *
 * `PolicyMutator` *composes* an `ApproovDefaultMessageSigning` signer rather than
 * subclassing it. On iOS `ApproovServiceMutator` supplies a protocol-default
 * token-fetch decision, so composition keeps the bitmask policy fully under this
 * type's control while still delegating HTTP Message Signing to the default
 * signer. When constructed with `sign == true` (the default) the processed
 * request is signed by the composed `ApproovDefaultMessageSigning`. When
 * constructed with `sign == false` the request is forwarded unmodified
 * (unsigned) while the proceed/forward/block policy below still applies.
 *
 * `handleInterceptorFetchTokenResult(_:url:)` applies the bitmask policy. Three
 * outcomes are possible per `ApproovTokenFetchStatus`:
 *   - PROCEED — continue interceptor processing (`return true`).
 *   - FORWARD — issue the request unmodified (`return false`).
 *   - BLOCK   — reject the request by throwing `ApproovServiceError.permanentError`.
 *
 * A non-maskable baseline always applies:
 *   - `.success`         -> PROCEED
 *   - `.unknownURL`      -> FORWARD
 *   - `.unprotectedURL`  -> FORWARD
 * For every other (failure) status the mask decides: if the status's canonical
 * bit is set in the mask the request PROCEEDs, otherwise it is BLOCKed.
 *
 * A blocked status MUST throw `.permanentError` (userInfo type "general"), which
 * the Objective-C interceptor maps to a hard Fail — the exact analogue of the
 * Android `IOException` hard-fail. Returning `false` for a blocked status would
 * instead reach the interceptor's Retry/proceed fallback and diverge from
 * Android; this is a locked design decision.
 *
 * The canonical bit assignment below is name-based and stable; it is mirrored by
 * the Android `PolicyMutator.BIT_*` constants and by the JavaScript layer, so the
 * values MUST NOT change. Bits `1 << 8` (NO_NETWORK_PERMISSION) and `1 << 9`
 * (MISSING_LIB_DEPENDENCY) have no matching status on iOS and are therefore never
 * matched; their numeric values are kept consistent with the other platforms.
 */
public final class PolicyMutator: ApproovServiceMutator, CustomStringConvertible {

    /** Canonical mask bit for `NO_APPROOV_SERVICE`. */
    public static let BIT_NO_APPROOV_SERVICE:     Int32 = 1 << 0
    /** Canonical mask bit for `BAD_URL`. */
    public static let BIT_BAD_URL:                Int32 = 1 << 1
    /** Canonical mask bit for `MITM_DETECTED`. */
    public static let BIT_MITM_DETECTED:          Int32 = 1 << 2
    /** Canonical mask bit for `NO_NETWORK`. */
    public static let BIT_NO_NETWORK:             Int32 = 1 << 3
    /** Canonical mask bit for `POOR_NETWORK`. */
    public static let BIT_POOR_NETWORK:           Int32 = 1 << 4
    /** Canonical mask bit for `REJECTED`. */
    public static let BIT_REJECTED:               Int32 = 1 << 5
    /** Canonical mask bit for `UNKNOWN_KEY`. */
    public static let BIT_UNKNOWN_KEY:            Int32 = 1 << 6
    /** Canonical mask bit for `INTERNAL_ERROR`. */
    public static let BIT_INTERNAL_ERROR:         Int32 = 1 << 7
    /** Canonical mask bit for `NO_NETWORK_PERMISSION` (no matching status on iOS). */
    public static let BIT_NO_NETWORK_PERMISSION:  Int32 = 1 << 8
    /** Canonical mask bit for `MISSING_LIB_DEPENDENCY` (no matching status on iOS). */
    public static let BIT_MISSING_LIB_DEPENDENCY: Int32 = 1 << 9
    /** Canonical mask bit for `DISABLED`. */
    public static let BIT_DISABLED:               Int32 = 1 << 10

    /**
     * The outcome of a policy decision for a single token-fetch status.
     */
    enum Decision {
        /** Continue interceptor processing (the interceptor callback returns true). */
        case proceed
        /** Issue the request unmodified (the interceptor callback returns false). */
        case forward
        /** Reject the request (the interceptor callback throws). */
        case block
    }

    /** Bitmask of failure statuses that are permitted to PROCEED. */
    let proceedMask: Int32

    /**
     * Whether the processed request should be HTTP Message Signed (`true`, the
     * default) or forwarded unmodified/unsigned (`false`).
     */
    let sign: Bool

    /** The composed signer used when `sign == true`. */
    let signer: ApproovDefaultMessageSigning

    /**
     * Constructs a `PolicyMutator` driven by the supplied proceed bitmask,
     * optionally disabling HTTP Message Signing.
     *
     * - Parameters:
     *   - proceedMask: bitmask of failure-status bits (see the `BIT_*` constants)
     *                  that should PROCEED rather than BLOCK.
     *   - sign:        `true` to sign the processed request (the default,
     *                  delegating to `ApproovDefaultMessageSigning`), `false` to
     *                  forward the request unmodified and unsigned.
     */
    public init(proceedMask: Int32, sign: Bool = true) {
        self.proceedMask = proceedMask
        self.sign = sign
        self.signer = ApproovDefaultMessageSigning()
            .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory())
    }

    public var description: String {
        return "PolicyMutator{proceedMask=\(proceedMask), sign=\(sign)}"
    }

    /**
     * Returns the canonical mask bit for the supplied status.
     *
     * - Parameter status: the token fetch status.
     * - Returns: the canonical bit for a maskable failure status that exists on
     *            iOS, or `0` for the non-maskable baseline statuses and anything
     *            else.
     */
    static func bitFor(_ status: ApproovTokenFetchStatus) -> Int32 {
        switch status {
        case .noApproovService:
            return BIT_NO_APPROOV_SERVICE
        case .badURL:
            return BIT_BAD_URL
        case .mitmDetected:
            return BIT_MITM_DETECTED
        case .noNetwork:
            return BIT_NO_NETWORK
        case .poorNetwork:
            return BIT_POOR_NETWORK
        case .rejected:
            return BIT_REJECTED
        case .unknownKey:
            return BIT_UNKNOWN_KEY
        case .internalError:
            return BIT_INTERNAL_ERROR
        case .disabled:
            return BIT_DISABLED
        default:
            // .success, .unknownURL, .unprotectedURL and anything unrecognised
            // have no maskable bit.
            return 0
        }
    }

    /**
     * Computes the policy decision for a status under the supplied proceed mask.
     *
     * The baseline (`.success`, `.unknownURL`, `.unprotectedURL`) is
     * non-maskable. Every other status PROCEEDs only when its canonical bit is
     * set in `proceedMask`, otherwise it is BLOCKed (fails closed).
     *
     * - Parameters:
     *   - proceedMask: bitmask of failure-status bits permitted to PROCEED.
     *   - status:      the token fetch status to evaluate.
     * - Returns: the resulting `Decision`.
     */
    static func decideFor(_ proceedMask: Int32, _ status: ApproovTokenFetchStatus) -> Decision {
        switch status {
        case .success:
            return .proceed
        case .unknownURL, .unprotectedURL:
            return .forward
        default:
            let bit = bitFor(status)
            if bit != 0 && (proceedMask & bit) != 0 {
                return .proceed
            }
            return .block
        }
    }

    /**
     * Applies the bitmask policy to the interceptor token-fetch result.
     *
     * - Parameters:
     *   - approovResults: the token fetch result from Approov.
     *   - url:            the URL string for which the token was requested.
     * - Returns: `true` if processing should continue (PROCEED), `false` if the
     *            request should be forwarded unmodified (FORWARD).
     * - Throws: `ApproovServiceError.permanentError` if the policy blocks the
     *           status (BLOCK). This is a permanent (type "general") error so the
     *           Objective-C interceptor hard-fails the request rather than
     *           retrying or proceeding.
     */
    public func handleInterceptorFetchTokenResult(_ approovResults: ApproovTokenFetchResult,
                                                  url: String) throws -> Bool {
        let status = approovResults.status
        switch PolicyMutator.decideFor(proceedMask, status) {
        case .proceed:
            return true
        case .forward:
            return false
        case .block:
            throw ApproovServiceError.permanentError(message: "PolicyMutator blocked: " + Approov.string(from: status))
        }
    }

    /**
     * Applies the signing policy to the interceptor-processed request. When this
     * mutator was constructed to sign (the default), the request is signed by the
     * composed `ApproovDefaultMessageSigning`. When constructed with
     * `sign == false`, the request is forwarded unmodified and unsigned.
     *
     * - Parameters:
     *   - request: the processed request.
     *   - changes: the mutations applied to the request by Approov.
     * - Returns: the signed request when signing is enabled, otherwise the
     *            original request unchanged.
     * - Throws: `ApproovServiceError` if an error occurs while signing.
     */
    public func handleInterceptorProcessedRequest(_ request: URLRequest,
                                                  changes: ApproovRequestMutations) throws -> URLRequest {
        return sign ? try signer.handleInterceptorProcessedRequest(request, changes: changes) : request
    }
}
