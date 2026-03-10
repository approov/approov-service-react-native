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
    
    @objc public func processRequest(_ request: NSMutableURLRequest, tokenHeader: String?, traceIDHeader: String?) {
        let urlRequest = request as URLRequest
        let changes = ApproovRequestMutations()
        if let th = tokenHeader,
           let tokenValue = request.value(forHTTPHeaderField: th),
           !tokenValue.isEmpty {
            // Only sign token header when the request actually carries one.
            changes.setTokenHeaderKey(th)
        }
        if let traceTh = traceIDHeader,
           let traceValue = request.value(forHTTPHeaderField: traceTh),
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
            request.httpBody = processedRequest.httpBody
            request.httpBodyStream = processedRequest.httpBodyStream
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
}
