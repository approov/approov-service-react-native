import Foundation

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
        if let th = tokenHeader {
            changes.setTokenHeaderKey(th)
        }
        if let traceTh = traceIDHeader {
            changes.setTraceIDHeaderKey(traceTh)
        }
        
        do {
            let processedRequest = try serviceMutator.handleInterceptorProcessedRequest(urlRequest, changes: changes)
            
            if let allHeaders = processedRequest.allHTTPHeaderFields {
                for (header, value) in allHeaders {
                    request.setValue(value, forHTTPHeaderField: header)
                }
            }
        } catch {
            NSLog("[ApproovServiceMutatorBridge] Error processing request: %@", error.localizedDescription)
        }
    }
}
