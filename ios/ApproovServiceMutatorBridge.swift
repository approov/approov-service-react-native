import Foundation
import Approov

@objc public class ApproovServiceMutatorBridge: NSObject {
    @objc public static let shared = ApproovServiceMutatorBridge()
    
    public var serviceMutator: ApproovServiceMutator
    
    private override init() {
        let factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
        let signer = ApproovDefaultMessageSigning()
        _ = signer.setDefaultFactory(factory)
        self.serviceMutator = signer
        super.init()
    }

    /**
     * Installs a `PolicyMutator` as the active service mutator. Exposed to
     * Objective-C because the Swift-typed `serviceMutator` property cannot be
     * assigned directly from Objective-C.
     *
     * - Parameters:
     *   - mask: the proceed bitmask (see `PolicyMutator.BIT_*`).
     *   - sign: whether the processed request should be HTTP Message Signed.
     */
    @objc public func setPolicyMutator(_ mask: Int32, sign: Bool) {
        self.serviceMutator = PolicyMutator(proceedMask: mask, sign: sign)
    }

    /**
     * Indicates whether the currently installed service mutator is the built-in
     * default signer. Used by the initialization path to warn when a custom
     * mutator (policy or native) is about to be discarded by a reset.
     */
    @objc public var isDefaultMutator: Bool {
        return type(of: serviceMutator) == ApproovDefaultMessageSigning.self
    }

    /**
     * Restores the built-in default service mutator: a freshly configured
     * `ApproovDefaultMessageSigning` signer identical to the one installed at
     * construction time. Exposed to Objective-C because the Swift-typed
     * `serviceMutator` property cannot be assigned directly from Objective-C.
     */
    @objc public func resetToDefault() {
        let factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
        let signer = ApproovDefaultMessageSigning()
        _ = signer.setDefaultFactory(factory)
        self.serviceMutator = signer
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
        _ = processRequest(request, tokenHeader: tokenHeader, traceIDHeader: traceIDHeader, errorPointer: nil)
    }

    @objc public func processRequest(_ request: NSMutableURLRequest,
                                     tokenHeader: String?,
                                     traceIDHeader: String?,
                                     errorPointer: NSErrorPointer) -> Bool {
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
            let processedRequest = try serviceMutator.handleInterceptorProcessedRequest(urlRequest, changes: changes)
            
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
            return true
        } catch {
            NSLog("[ApproovServiceMutatorBridge] Error processing request: %@", error.localizedDescription)
            if errorPointer != nil {
                errorPointer?.pointee = error as NSError
            }
            return false
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

    @objc public func handleInterceptorHeaderSubstitutionResult(_ result: Any,
                                                                header: String,
                                                                errorPointer: NSErrorPointer) -> Bool {
        guard let fetchResult = result as? ApproovTokenFetchResult else {
            NSLog("[ApproovServiceMutatorBridge] Invalid result type passed to handleInterceptorHeaderSubstitutionResult")
            return false
        }

        do {
            return try serviceMutator.handleInterceptorHeaderSubstitutionResult(fetchResult, header: header)
        } catch {
            if errorPointer != nil {
                errorPointer?.pointee = error as NSError
            }
            return false
        }
    }

    @objc public func handleInterceptorQueryParamSubstitutionResult(_ result: Any,
                                                                    queryKey: String,
                                                                    errorPointer: NSErrorPointer) -> Bool {
        guard let fetchResult = result as? ApproovTokenFetchResult else {
            NSLog("[ApproovServiceMutatorBridge] Invalid result type passed to handleInterceptorQueryParamSubstitutionResult")
            return false
        }

        do {
            return try serviceMutator.handleInterceptorQueryParamSubstitutionResult(fetchResult, queryKey: queryKey)
        } catch {
            if errorPointer != nil {
                errorPointer?.pointee = error as NSError
            }
            return false
        }
    }
}
