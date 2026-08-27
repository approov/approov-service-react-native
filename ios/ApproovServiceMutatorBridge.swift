import Foundation
import Approov

@objc public class ApproovServiceMutatorBridge: NSObject {
    @objc public static let shared = ApproovServiceMutatorBridge()
    
    private let mutatorLock = NSLock()
    private var _serviceMutator: ApproovServiceMutator

    // Thread-safe accessor: the mutator is read on URLSession/network threads and
    // written from the RN bridge thread (setPolicyMutator/resetToDefault) and the
    // init reset. Mirrors the `volatile` guard the Android side uses for its
    // serviceMutator field. Callers grab the current reference under the lock and
    // then invoke it outside the lock (the mutator instance is immutable).
    public var serviceMutator: ApproovServiceMutator {
        get { mutatorLock.lock(); defer { mutatorLock.unlock() }; return _serviceMutator }
        set { mutatorLock.lock(); defer { mutatorLock.unlock() }; _serviceMutator = newValue }
    }

    private override init() {
        _serviceMutator = ApproovServiceMutatorDefault.shared
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
     *   - useAccountSigning: true for the account signature, false for install.
     */
    @objc public func setPolicyMutator(_ mask: Int32, sign: Bool, useAccountSigning: Bool) {
        self.serviceMutator = PolicyMutator(proceedMask: mask,
                                            sign: sign,
                                            useAccountSigning: useAccountSigning)
    }

    /**
     * Indicates whether the currently installed service mutator is the built-in
     * default. Used by the initialization path to warn when a custom mutator
     * (policy or native) is about to be discarded by a reset.
     */
    @objc public var isDefaultMutator: Bool {
        return serviceMutator is ApproovServiceMutatorDefault
    }

    /**
     * Restores the built-in default service mutator: the standard pass-through
     * mutator installed at construction time, which forwards requests unsigned.
     * HTTP Message Signing is opt-in via `setPolicyMutator(_:sign:)` or by
     * installing `ApproovDefaultMessageSigning` directly. Exposed to
     * Objective-C because the Swift-typed `serviceMutator` property cannot be
     * assigned directly from Objective-C.
     */
    @objc public func resetToDefault() {
        self.serviceMutator = ApproovServiceMutatorDefault.shared
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
