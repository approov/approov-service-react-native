import Foundation
import Approov

@objc public class ApproovServiceMutatorBridge: NSObject {
    @objc public static let shared = ApproovServiceMutatorBridge()
    
    private let signer = ApproovDefaultMessageSigning()
    private var isConfigured = false
    
    private override init() {
        super.init()
    }
    
    @objc public func configure(_ config: NSDictionary) {
        // Create a new factory based on the config dictionary
        let factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
        
        // Parsing Algorithm
        if let alg = config["alg"] as? String {
            if alg == "sha-256" {
                 try? _ = factory.setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, required: true)
            } else if alg == "sha-512" {
                 try? _ = factory.setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA512, required: true)
            }
        }
        
        // Parsing Headers
        if let headers = config["headers"] as? [String] {
             _ = factory.addOptionalHeaders(headers)
        }
        
        // Parsing Header Configs
        if let tokenHeader = config["addApproovTokenHeader"] as? Bool {
            _ = factory.setAddApproovTokenHeader(tokenHeader)
        }
        
        // Clean start with new factory
        _ = signer.setDefaultFactory(factory)
        isConfigured = true
        NSLog("[ApproovServiceMutatorBridge] Configured with: %@", config)
    }
    
    @objc public func signRequest(_ request: NSMutableURLRequest) {
        // If not configured, do nothing (or use defaults if preferred)
        guard isConfigured else { return }
        
        // Convert NSMutableURLRequest -> URLRequest
        let urlRequest = request as URLRequest
        
        // We simulate the "changes" object since we are not fully using the interceptor stack here yet
        let changes = ApproovRequestMutations()
        changes.setTokenHeaderKey("Approov-Token")
        
        do {
            // Check if we should process
            if signer.handlePinningShouldProcessRequest(urlRequest) {
                // Determine if we need to Mutate
                let signedRequest = try signer.handleInterceptorProcessedRequest(urlRequest, changes: changes)
                
                // Copy headers back to mutable request
                if let allHeaders = signedRequest.allHTTPHeaderFields {
                    for (header, value) in allHeaders {
                        request.setValue(value, forHTTPHeaderField: header)
                    }
                }
            }
        } catch {
            NSLog("[ApproovServiceMutatorBridge] Error signing request: %@", error.localizedDescription)
        }
    }
}
