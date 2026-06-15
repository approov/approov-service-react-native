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

import Approov
import Foundation

/**
 * ApproovServiceMutator provides an interface for modifying the behavior of
 * the ApproovService class by overriding the default implementations of the
 * defined callbacks. Opportunities to modify behavior are offered at key
 * points in the service and attestation flows.
 *
 * The protocol provides default implementations for all methods, so
 * implementing classes can choose to override only the methods they are
 * interested in. The default implementations provide standard behavior
 * that is suitable for most use cases and provides backwards compatibility
 * with previous versions of this Approov service layer.
 */
public protocol ApproovServiceMutator {
    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.precheck() operation.
     */
    func handlePrecheckResult(_ approovResults: ApproovTokenFetchResult) throws

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.fetchToken() operation.
     */
    func handleFetchTokenResult(_ approovResults: ApproovTokenFetchResult) throws

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.fetchSecureString() operation.
     */
    func handleFetchSecureStringResult(_ approovResults: ApproovTokenFetchResult,
                                       operation: String,
                                       key: String) throws

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.fetchCustomJWT() operation.
     */
    func handleFetchCustomJWTResult(_ approovResults: ApproovTokenFetchResult) throws

    /**
     * Decides whether a request should be processed in the interceptor or not.
     * Called at the start of the ApproovService interceptor processing.
     */
    func handleInterceptorShouldProcessRequest(_ request: URLRequest) throws -> Bool

    /**
     * Decides how to handle the token fetch result from a call to
     * Approov.fetchTokenAndWait() from within the interceptor.
     */
    func handleInterceptorFetchTokenResult(_ approovResults: ApproovTokenFetchResult,
                                           url: String) throws -> Bool

    /**
     * Decides how to handle the token fetch result while substituting headers
     * from within the interceptor.
     */
    func handleInterceptorHeaderSubstitutionResult(_ approovResults: ApproovTokenFetchResult,
                                                   header: String) throws -> Bool

    /**
     * Decides how to handle the token fetch result while substituting query params
     * from within the interceptor.
     */
    func handleInterceptorQueryParamSubstitutionResult(_ approovResults: ApproovTokenFetchResult,
                                                       queryKey: String) throws -> Bool

    /**
     * Called after Approov has processed a network request, allowing further
     * modifications.
     */
    func handleInterceptorProcessedRequest(_ request: URLRequest,
                                           changes: ApproovRequestMutations) throws -> URLRequest

    /**
     * Decides whether certificate pinning should be applied to a request or not.
     * Called at the start of the ApproovService pinning processing.
     */
    func handlePinningShouldProcessRequest(_ request: URLRequest) -> Bool
}

public extension ApproovServiceMutator {
    func handlePrecheckResult(_ approovResults: ApproovTokenFetchResult) throws {
        let status = approovResults.status
        switch status {
        case .rejected:
            var reasons: [String] = []
            // rejectionReasons is a String (comma separated), not Optional
            reasons = approovResults.rejectionReasons.components(separatedBy: ",")
            throw ApproovServiceError.rejectionError(message: "precheck: rejected",
                                              ARC: approovResults.arc,
                                              rejectionReasons: reasons)
        case .noNetwork,
             .poorNetwork,
             .mitmDetected:
            throw ApproovServiceError.networkingError(message: "precheck network error: " + Approov.string(from: status))
        case .success,
             .unknownKey:
            return
        default:
            throw ApproovServiceError.permanentError(message: "precheck: " + Approov.string(from: status))
        }
    }

    func handleFetchTokenResult(_ approovResults: ApproovTokenFetchResult) throws {
        let status = approovResults.status
        switch status {
        case .success:
            return
        case .noNetwork,
             .poorNetwork,
             .mitmDetected:
            throw ApproovServiceError.networkingError(message: "fetchToken network error: " + Approov.string(from: status))
        default:
            throw ApproovServiceError.permanentError(message: "fetchToken: " + Approov.string(from: status))
        }
    }

    func handleFetchSecureStringResult(_ approovResults: ApproovTokenFetchResult,
                                       operation: String,
                                       key: String) throws {
        let status = approovResults.status
        switch status {
        case .rejected:
            var reasons: [String] = []
            reasons = approovResults.rejectionReasons.components(separatedBy: ",")
            throw ApproovServiceError.rejectionError(message: "fetchSecureString \(operation) for \(key): rejected",
                                              ARC: approovResults.arc,
                                              rejectionReasons: reasons)
        case .noNetwork,
             .poorNetwork,
             .mitmDetected:
            throw ApproovServiceError.networkingError(message: "fetchSecureString \(operation) for \(key): " +
                                               Approov.string(from: status))
        case .success,
             .unknownKey:
            return
        default:
            throw ApproovServiceError.permanentError(message: "fetchSecureString \(operation) for \(key): " +
                                              Approov.string(from: status))
        }
    }

    func handleFetchCustomJWTResult(_ approovResults: ApproovTokenFetchResult) throws {
        let status = approovResults.status
        switch status {
        case .rejected:
            var reasons: [String] = []
           reasons = approovResults.rejectionReasons.components(separatedBy: ",")
            throw ApproovServiceError.rejectionError(message: "fetchCustomJWT: rejected",
                                              ARC: approovResults.arc,
                                              rejectionReasons: reasons)
        case .noNetwork,
             .poorNetwork,
             .mitmDetected:
            throw ApproovServiceError.networkingError(message: "fetchCustomJWT network error: " + Approov.string(from: status))
        case .success:
            return
        default:
            throw ApproovServiceError.permanentError(message: "fetchCustomJWT: " + Approov.string(from: status))
        }
    }

    func handleInterceptorShouldProcessRequest(_ request: URLRequest) throws -> Bool {
        guard let url = request.url else {
            throw ApproovServiceError.permanentError(message: "handleInterceptorShouldProcessRequest received a request with no URL")
        }
        let urlString = url.absoluteString
        let urlStringRange = NSRange(urlString.startIndex..<urlString.endIndex, in: urlString)
        if let exclusionRegexs = ApproovService.sharedExclusionURLRegexs() as? Set<String> {
            for regexString in exclusionRegexs {
                do {
                    let regex = try NSRegularExpression(pattern: regexString, options: [])
                    if regex.firstMatch(in: urlString, options: [], range: urlStringRange) != nil {
                        return false
                    }
                } catch {
                    // ignore invalid regex
                }
            }
        }
        return true
    }

    func handleInterceptorFetchTokenResult(_ approovResults: ApproovTokenFetchResult,
                                           url: String) throws -> Bool {
        let status = approovResults.status
        switch status {
        case .success:
            return true
        case .noNetwork,
             .poorNetwork,
             .mitmDetected:
            return false
        case .noApproovService:
            if ApproovService.sharedUseApproovStatusIfNoToken() {
                return true
            }
            return false
        case .unknownURL,
             .unprotectedURL:
            return false
        default:
            throw ApproovServiceError.permanentError(message: "Approov token fetch for \(url): " +
                                              Approov.string(from: status))
        }
    }

    func handleInterceptorHeaderSubstitutionResult(_ approovResults: ApproovTokenFetchResult,
                                                   header: String) throws -> Bool {
        let status = approovResults.status
        switch status {
        case .success:
            return true
        case .rejected:
            var reasons: [String] = []
            reasons = approovResults.rejectionReasons.components(separatedBy: ",")
            throw ApproovServiceError.rejectionError(message: "Header substitution for \(header): rejected",
                                              ARC: approovResults.arc,
                                              rejectionReasons: reasons)
        case .noNetwork,
             .poorNetwork,
             .mitmDetected:
            return true
        case .unknownKey:
            return false
        default:
            throw ApproovServiceError.permanentError(message: "Header substitution for \(header): " +
                                              Approov.string(from: status))
        }
    }

    func handleInterceptorQueryParamSubstitutionResult(_ approovResults: ApproovTokenFetchResult,
                                                       queryKey: String) throws -> Bool {
        let status = approovResults.status
        switch status {
        case .success:
            return true
        case .rejected:
            var reasons: [String] = []
            reasons = approovResults.rejectionReasons.components(separatedBy: ",")
            throw ApproovServiceError.rejectionError(message: "Query parameter substitution for \(queryKey): rejected",
                                              ARC: approovResults.arc,
                                              rejectionReasons: reasons)
        case .noNetwork,
             .poorNetwork,
             .mitmDetected:
            return true
        case .unknownKey:
            return false
        default:
            throw ApproovServiceError.permanentError(message: "Query parameter substitution for \(queryKey): " +
                                              Approov.string(from: status))
        }
    }

    func handleInterceptorProcessedRequest(_ request: URLRequest,
                                           changes: ApproovRequestMutations) throws -> URLRequest {
        return request
    }

    func handlePinningShouldProcessRequest(_ request: URLRequest) -> Bool {
        return true
    }
}

/**
 * The standard, off-the-shelf **fail-closed** service mutator (the default policy). It applies the
 * standard Approov behavior from the protocol extension above: proceed with a token on `success`;
 * proceed *without* a token when the URL is not Approov-protected (`unknownURL` / `unprotectedURL`, or
 * `noApproovService` when `setUseApproovStatusIfNoToken(true)` is configured); and block on network
 * failures (`noNetwork` / `poorNetwork` / `mitmDetected`) and `rejected`.
 *
 * Select it from React with `ApproovService.setServiceMutator(ApproovService.Mutator.DEFAULT)`. For the
 * full list of policies and message-signing configuration see USAGE.md ("Service Mutators" and
 * "Message Signing") and README.md. Compare with `ApproovServiceMutatorAlwaysProceed` (fail-open) and
 * `ApproovServiceMutatorRequireAttestation` (strict fail-closed).
 */
public struct ApproovServiceMutatorDefault: ApproovServiceMutator, CustomStringConvertible {
    public static let shared = ApproovServiceMutatorDefault()

    public var description: String {
        return "ApproovServiceMutator.DEFAULT"
    }

    private init() {}
}

/**
 * Off-the-shelf **fail-open** service mutator: the request is *always* sent, whatever the attestation
 * outcome. An Approov token (and any configured secure-string substitutions) is attached only when
 * attestation succeeds; for every other status — no/poor network, MITM detection, rejection,
 * unknown/unprotected URL — the request simply proceeds *without* a token rather than being blocked,
 * and the interceptor never throws. Availability is therefore never affected by Approov; use this only
 * when your backend is responsible for enforcing token presence/validity.
 *
 * Select it from React with `ApproovService.setServiceMutator(ApproovService.Mutator.ALWAYS_PROCEED)`.
 * See USAGE.md ("Service Mutators" and "Message Signing") and README.md for details.
 */
public struct ApproovServiceMutatorAlwaysProceed: ApproovServiceMutator, CustomStringConvertible {
    public static let shared = ApproovServiceMutatorAlwaysProceed()

    public var description: String {
        return "ApproovServiceMutator.ALWAYS_PROCEED"
    }

    private init() {}

    public func handleInterceptorFetchTokenResult(_ approovResults: ApproovTokenFetchResult,
                                                  url: String) throws -> Bool {
        // attach a token only on success; never throw — proceed token-less for any other status
        return approovResults.status == .success
    }

    public func handleInterceptorHeaderSubstitutionResult(_ approovResults: ApproovTokenFetchResult,
                                                          header: String) throws -> Bool {
        // substitute only on a successful lookup; never block on a failed/rejected secure string
        return approovResults.status == .success
    }

    public func handleInterceptorQueryParamSubstitutionResult(_ approovResults: ApproovTokenFetchResult,
                                                              queryKey: String) throws -> Bool {
        return approovResults.status == .success
    }
}

/**
 * Off-the-shelf **strict fail-closed** service mutator. It behaves like the standard
 * `ApproovServiceMutatorDefault` policy except that it does *not* tolerate `noApproovService`: when the
 * SDK cannot reach the Approov service for an otherwise-protected request, the request is blocked
 * rather than sent without a token. Use this for apps that must never let a protected request through
 * unattested, even when the Approov cloud is unreachable.
 *
 * Outcomes: `success` → proceed with a token; `unknownURL` / `unprotectedURL` → proceed *without* a
 * token (not Approov-protected); `noApproovService` / `noNetwork` / `poorNetwork` / `mitmDetected` →
 * block (networking error, retryable); `rejected` → block (rejection error); any other status →
 * permanent error.
 *
 * Select it from React with
 * `ApproovService.setServiceMutator(ApproovService.Mutator.REQUIRE_ATTESTATION)`. See USAGE.md
 * ("Service Mutators" and "Message Signing") and README.md for details.
 */
public struct ApproovServiceMutatorRequireAttestation: ApproovServiceMutator, CustomStringConvertible {
    public static let shared = ApproovServiceMutatorRequireAttestation()

    public var description: String {
        return "ApproovServiceMutator.REQUIRE_ATTESTATION"
    }

    private init() {}

    public func handleInterceptorFetchTokenResult(_ approovResults: ApproovTokenFetchResult,
                                                  url: String) throws -> Bool {
        let status = approovResults.status
        switch status {
        case .success:
            return true
        case .unknownURL, .unprotectedURL:
            // not an Approov-protected URL — there is no attestation to require; proceed token-less
            return false
        case .rejected:
            let reasons = approovResults.rejectionReasons.components(separatedBy: ",")
            throw ApproovServiceError.rejectionError(message: "Approov token fetch for \(url): rejected",
                                                     ARC: approovResults.arc,
                                                     rejectionReasons: reasons)
        case .noApproovService, .noNetwork, .poorNetwork, .mitmDetected:
            // unlike the default policy, noApproovService is treated as a (retryable) failure rather
            // than a reason to proceed without a token
            throw ApproovServiceError.networkingError(message: "Approov token fetch for \(url): " +
                                                      Approov.string(from: status))
        default:
            throw ApproovServiceError.permanentError(message: "Approov token fetch for \(url): " +
                                                     Approov.string(from: status))
        }
    }
}
