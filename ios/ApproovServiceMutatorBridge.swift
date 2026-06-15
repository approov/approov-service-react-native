import Foundation
import Approov

@objc public class ApproovServiceMutatorBridge: NSObject {
    @objc public static let shared = ApproovServiceMutatorBridge()
    
    // The service mutator decides token/substitution policy. The three off-the-shelf policies are
    // ApproovServiceMutatorDefault (standard fail-closed), ApproovServiceMutatorAlwaysProceed
    // (fail-open) and ApproovServiceMutatorRequireAttestation (strict fail-closed). See USAGE.md.
    public var serviceMutator: ApproovServiceMutator

    // The active message signer, or nil when message signing is disabled. Message signing is decoupled
    // from the mutator: it is an opt-out feature, installed by default and applied AFTER the mutator in
    // processRequest so the signature can cover any headers the mutator added.
    public var messageSigner: ApproovDefaultMessageSigning?

    private override init() {
        self.serviceMutator = ApproovServiceMutatorDefault.shared
        self.messageSigner = ApproovDefaultMessageSigning.makeDefault()
        super.init()
    }

    private func headerValue(forHTTPHeaderField header: String,
                             in request: URLRequest) -> String? {
        if let value = request.value(forHTTPHeaderField: header) {
            return value
        }
        guard let headers = request.allHTTPHeaderFields else {
            return nil
        }
        for (key, value) in headers where key.caseInsensitiveCompare(header) == .orderedSame {
            return value
        }
        return nil
    }
    
    @objc public func processRequest(_ request: NSMutableURLRequest, tokenHeader: String?, traceIDHeader: String?) {
        let urlRequest = request as URLRequest
        let changes = ApproovRequestMutations()
        if let th = tokenHeader,
           let tokenValue = headerValue(forHTTPHeaderField: th, in: urlRequest),
           !tokenValue.isEmpty {
            // Only sign token header when the request actually carries one.
            changes.setTokenHeaderKey(th)
        }
        if let traceTh = traceIDHeader,
           let traceValue = headerValue(forHTTPHeaderField: traceTh, in: urlRequest),
           !traceValue.isEmpty {
            // Avoid requiring a trace component when the trace header is absent.
            changes.setTraceIDHeaderKey(traceTh)
        }
        
        do {
            var processedRequest = try serviceMutator.handleInterceptorProcessedRequest(urlRequest, changes: changes)

            // Message signing is decoupled from the mutator and applied after it so the signature can
            // cover any headers the mutator added; it only signs when the request carries a token.
            if let signer = messageSigner {
                processedRequest = try signer.processedRequest(processedRequest, changes: changes)
            }

            // Copy back the mutable URLRequest state so custom mutators can change
            // request behavior, not just add or update headers.
            if let url = processedRequest.url {
                request.url = url
            }
            request.cachePolicy = processedRequest.cachePolicy
            request.mainDocumentURL = processedRequest.mainDocumentURL
            request.networkServiceType = processedRequest.networkServiceType
            request.allowsCellularAccess = processedRequest.allowsCellularAccess
            request.httpShouldHandleCookies = processedRequest.httpShouldHandleCookies
            request.httpShouldUsePipelining = processedRequest.httpShouldUsePipelining
            if #available(iOS 13.0, *) {
                request.allowsExpensiveNetworkAccess = processedRequest.allowsExpensiveNetworkAccess
                request.allowsConstrainedNetworkAccess = processedRequest.allowsConstrainedNetworkAccess
            }
            if let method = processedRequest.httpMethod {
                request.httpMethod = method
            }
            // Setting httpBodyStream, even to nil, can clear an existing httpBody
            // on NSMutableURLRequest. Only set the active body representation.
            if let bodyData = processedRequest.httpBody {
                request.httpBody = bodyData
            } else if let stream = processedRequest.httpBodyStream {
                request.httpBodyStream = stream
            } else {
                request.httpBody = nil
                request.httpBodyStream = nil
            }
            request.timeoutInterval = processedRequest.timeoutInterval
            request.allHTTPHeaderFields = processedRequest.allHTTPHeaderFields
        } catch {
            NSLog("[ApproovServiceMutatorBridge] Error processing request: %@", error.localizedDescription)
        }
    }
    
    @objc public func handleInterceptorFetchTokenResult(_ result: Any, url: String, errorPointer: NSErrorPointer) -> Bool {
        guard let fetchResult = result as? ApproovTokenFetchResult else {
            NSLog("[ApproovServiceMutatorBridge] Invalid result type passed to handleInterceptorFetchTokenResult")
            return false
        }
        
        do {
            return try serviceMutator.handleInterceptorFetchTokenResult(fetchResult, url: url)
        } catch {
            if errorPointer != nil {
                errorPointer?.pointee = error as NSError
            }
            return false
        }
    }

    // MARK: - Off-the-shelf mutator selection and message-signing control (exposed to React)

    /// Selects one of the off-the-shelf service mutators by type identifier ("DEFAULT",
    /// "ALWAYS_PROCEED", "REQUIRE_ATTESTATION"). An unrecognised type leaves the current mutator
    /// unchanged.
    @objc public func setServiceMutator(byType type: String) {
        switch type {
        case "DEFAULT":
            serviceMutator = ApproovServiceMutatorDefault.shared
        case "ALWAYS_PROCEED":
            serviceMutator = ApproovServiceMutatorAlwaysProceed.shared
        case "REQUIRE_ATTESTATION":
            serviceMutator = ApproovServiceMutatorRequireAttestation.shared
        default:
            NSLog("[ApproovServiceMutatorBridge] setServiceMutator: unknown mutator type '%@' (expected DEFAULT, ALWAYS_PROCEED or REQUIRE_ATTESTATION); leaving current mutator unchanged", type)
        }
    }

    /// The JS-facing type identifier of the active service mutator: one of "DEFAULT", "ALWAYS_PROCEED",
    /// "REQUIRE_ATTESTATION", or "CUSTOM".
    @objc public func getServiceMutatorType() -> String {
        if serviceMutator is ApproovServiceMutatorAlwaysProceed {
            return "ALWAYS_PROCEED"
        }
        if serviceMutator is ApproovServiceMutatorRequireAttestation {
            return "REQUIRE_ATTESTATION"
        }
        if serviceMutator is ApproovServiceMutatorDefault {
            return "DEFAULT"
        }
        return "CUSTOM"
    }

    /// Enables or disables message signing (opt-out; on by default). Enabling installs the default
    /// signer if signing was disabled; disabling removes the signer entirely.
    @objc public func setMessageSigningEnabled(_ enabled: Bool) {
        if enabled {
            if messageSigner == nil {
                messageSigner = ApproovDefaultMessageSigning.makeDefault()
            }
        } else {
            messageSigner = nil
        }
    }

    /// Reports whether message signing is currently enabled.
    @objc public func isMessageSigningEnabled() -> Bool {
        return messageSigner != nil
    }

    /// Adds a header to be covered by the message signature only when present on the request (never
    /// fails closed when absent), (re)enabling the default signer first if signing was disabled.
    @objc public func addSignedHeader(_ header: String) {
        let signer = messageSigner ?? ApproovDefaultMessageSigning.makeDefault()
        signer.addSignedHeader(header)
        messageSigner = signer
    }
}
