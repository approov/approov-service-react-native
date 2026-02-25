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
    
    @objc public func processRequest(_ request: NSMutableURLRequest) {
        let urlRequest = request as URLRequest
        let changes = ApproovRequestMutations()
        changes.setTokenHeaderKey("Approov-Token")
        
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
