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

import CommonCrypto
import Foundation
import os.log

/**
 * Provides a base implementation of message signing for Approov when using
 * URLSession requests. This class provides mechanisms to configure and apply
 * message signatures to HTTP requests based on specified parameters and
 * algorithms.
 */
public class ApproovDefaultMessageSigning: ApproovServiceMutator, CustomStringConvertible {

    /**
     * Constant for the SHA-256 digest algorithm (used for body digests).
     */
    public static let DIGEST_SHA256 = "sha-256"

    /**
     * Constant for the SHA-512 digest algorithm (used for body digests).
     */
    public static let DIGEST_SHA512 = "sha-512"

    /**
     * Constant for the ECDSA P-256 with SHA-256 algorithm (used when signing with install private key).
     */
    public static let ALG_ES256 = "ecdsa-p256-sha256"

    /**
     * Constant for the HMAC with SHA-256 algorithm (used when signing with the account signing key).
     */
    public static let ALG_HS256 = "hmac-sha256"

    /**
     * Default factory for generating signature parameters.
     */
    private var defaultFactory: SignatureParametersFactory?

    /**
     * Host-specific factories for generating signature parameters.
     */
    private var hostFactories: [String: SignatureParametersFactory]

    /**
     * Initializer
     */
    public init() {
        hostFactories = [:]
    }

    public var description: String {
        return "ApproovDefaultMessageSigning"
    }

    /**
     * Sets the default factory for generating signature parameters.
     *
     * - Parameter factory: The factory to set as the default.
     * - Returns: The current instance for method chaining.
     */
    public func setDefaultFactory(_ factory: SignatureParametersFactory) -> ApproovDefaultMessageSigning {
        defaultFactory = factory
        return self
    }

    /**
     * Associates a specific host with a factory for generating signature parameters.
     *
     * - Parameters:
     *   - hostName: The host name.
     *   - factory: The factory to associate with the host.
     * - Returns: The current instance for method chaining.
     */
    public func putHostFactory(hostName: String, factory: SignatureParametersFactory) -> ApproovDefaultMessageSigning {
        hostFactories[hostName] = factory
        return self
    }

    /**
     * Builds the default message signer with install (ES256) message signing enabled — the signer that
     * is installed by default at initialization. Message signing is an opt-out feature: this signer is
     * applied after the service mutator so the signature can cover any headers the mutator added.
     *
     * - Returns: A new `ApproovDefaultMessageSigning` configured with the default factory.
     */
    public static func makeDefault() -> ApproovDefaultMessageSigning {
        return ApproovDefaultMessageSigning().setDefaultFactory(generateDefaultSignatureParametersFactory())
    }

    /**
     * Adds a header to be covered by the signature **only when it is present** on the request — when
     * absent, the request is still signed (without that header) rather than being blocked, so this
     * never fails closed. Amends the active default factory without redeclaring its configuration,
     * creating a default factory first if none is set. Intended to be called at startup.
     *
     * - Parameter header: The header name to add to the signature.
     * - Returns: The current instance for method chaining.
     */
    @discardableResult
    public func addSignedHeader(_ header: String) -> ApproovDefaultMessageSigning {
        let factory = defaultFactory ?? ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
        _ = factory.addOptionalHeaders([header])
        defaultFactory = factory
        return self
    }

    /**
     * Builds the signature parameters for a given request.
     *
     * - Parameters:
     *   - provider: The component provider for the request.
     *   - changes: The request mutations to apply.
     * - Returns: The generated `SignatureParameters`, or `nil` if no factory is available.
     */
    private func buildSignatureParameters(provider: ApproovURLSessionComponentProvider, changes: ApproovRequestMutations) throws -> SignatureParameters? {
        let factory = hostFactories[provider.getAuthority()] ?? defaultFactory
        return try factory?.buildSignatureParameters(provider: provider, changes: changes)
    }

    /**
     * Processes a request to add message signature headers.
     *
     * - Parameters:
     *   - request: The original HTTP request.
     *   - changes: The request mutations that were applied by the Approov interceptor.
     * - Returns: The processed HTTP request with the signature headers added.
     * - Throws: An `ApproovError` if an error occurs during processing.
     */
    public func handleInterceptorProcessedRequest(_ request: URLRequest,
                                                  changes: ApproovRequestMutations) throws -> URLRequest {
        return try processedRequest(request, changes: changes)
    }

    /**
     * @deprecated Use ApproovServiceMutator.handleInterceptorProcessedRequest instead.
     *
     *             Currently the method is implemented to maintain backwards
     *             compatibility. A future release will move the implementation
     *             to the ApproovServiceMutator.handleInterceptorProcessedRequest
     *             method.
     */
    @available(*, deprecated, message: "Use handleInterceptorProcessedRequest instead.")
    public func processedRequest(_ request: URLRequest, changes: ApproovRequestMutations) throws -> URLRequest {
        // If the interceptor did not add an Approov token, there is nothing
        // for the signer to authenticate.
        if changes.getTokenHeaderKey() != nil {
            // Generate and add a message signature
            let provider = ApproovURLSessionComponentProvider(request: request)
            guard let params = try buildSignatureParameters(provider: provider, changes: changes) else {
                // No signature to be added; proceed with the original request
                return request
            }

            // Determine the signing algorithm. An unsupported algorithm is a configuration error and
            // fails CLOSED (the request is aborted). Every other signing failure below fails OPEN: the
            // request proceeds unsigned and the reason is logged at error level, because the backend is
            // the enforcement point for message signatures.
            let alg = params.getAlg()
            let sigId: String
            switch alg {
            case ApproovDefaultMessageSigning.ALG_ES256:
                sigId = "install"
            case ApproovDefaultMessageSigning.ALG_HS256:
                sigId = "account"
            default:
                throw ApproovServiceError.permanentError(message: "Unsupported algorithm identifier: \(alg ?? "unknown")")
            }

            do {
                // Build the signature base.
                // WARNING never log the message as it contains an Approov token which provides access to your API.
                let baseBuilder = SignatureBaseBuilder(sigParams: params, ctx: provider)
                let message = try baseBuilder.createSignatureBase()

                let signature: Data
                if alg == ApproovDefaultMessageSigning.ALG_ES256 {
                    guard let base64Signature = ApproovService.getInstallMessageSignature(message),
                          let decodedSignature = Data(base64Encoded: base64Signature) else {
                        os_log("ApproovService: install message signature unavailable - proceeding unsigned", type: .error)
                        return request
                    }
                    // The backend verifier expects the raw IEEE-P1363 r||s form.
                    signature = try ApproovDefaultMessageSigning.decodeASN_1_DER_ES256_Signature(decodedSignature)
                } else {
                    guard let base64Signature = ApproovService.getAccountMessageSignature(message),
                          let decodedSignature = Data(base64Encoded: base64Signature) else {
                        os_log("ApproovService: account message signature unavailable - proceeding unsigned", type: .error)
                        return request
                    }
                    signature = decodedSignature
                }

                // Create signature headers
                guard let sigHeader = try SFV.serializeDictionary(key: sigId, data: signature),
                      let sigInputHeader = try SFV.serializeDictionary(key: sigId, innerList: params.toComponentValue()) else {
                    os_log("ApproovService: failed to serialize signature headers - proceeding unsigned", type: .error)
                    return request
                }

                // Replace any previous signing headers so re-processing the same
                // request stays idempotent.
                var signedRequest = provider.getRequest()
                signedRequest.setValue(sigHeader, forHTTPHeaderField: "Signature")
                signedRequest.setValue(sigInputHeader, forHTTPHeaderField: "Signature-Input")

                if params.isDebugMode() {
                    let digest = ApproovDefaultMessageSigning.sha256(data: Data(message.utf8))
                    if let sigBaseDigestHeader = try SFV.serializeDictionary(key: "sha-256", data: digest) {
                        signedRequest.setValue(sigBaseDigestHeader, forHTTPHeaderField: "Signature-Base-Digest")
                    } else {
                        os_log("ApproovService: Failed to get digest algorithm - no debug entry", type: .debug)
                    }
                } else {
                    signedRequest.setValue(nil, forHTTPHeaderField: "Signature-Base-Digest")
                }

                // WARNING never log the full request as it contains an Approov token which provides access to your API
                return signedRequest
            } catch {
                // base64/ASN.1-DER decode, header serialization, or signature-base build failure - fail open
                os_log("ApproovService: %{public}@ message signing failed - proceeding unsigned: %{public}@", type: .error, sigId, "\(error)")
                return request
            }
        }

        return request
    }

    /**
     * SHA256 of given input bytes.
     *
     * @param data is the input data
     * @return the hash data
     */
    static func sha256(data: Data) -> Data {
        var hash = [UInt8](repeating: 0,  count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes {
            _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &hash)
        }
        return Data(hash)
    }

    // Decode ASN.1 DER encoded ES256 signature into "raw" signature format
    private static func decodeASN_1_DER_ES256_Signature(_ signature: Data) throws -> Data {
        var offset = 0

        // Ensure the signature starts with a valid ASN.1 sequence
        guard signature[offset] == 0x30 else {
            throw ApproovServiceError.permanentError(message: "Invalid ASN.1 DER sequence")
        }
        offset += 1

        // Read the total length of the sequence
        let sequenceLength = Int(signature[offset])
        offset += 1

        guard sequenceLength == signature.count - 2 else {
            throw ApproovServiceError.permanentError(message: "Invalid ASN.1 DER sequence length")
        }

        // Decode the first integer (r)
        guard signature[offset] == 0x02 else {
            throw ApproovServiceError.permanentError(message: "Invalid ASN.1 DER integer for r")
        }
        offset += 1

        let rLength = Int(signature[offset])
        offset += 1

        let rBytes = signature[offset..<(offset + rLength)]
        offset += rLength

        // Decode the second integer (s)
        guard signature[offset] == 0x02 else {
            throw ApproovServiceError.permanentError(message: "Invalid ASN.1 DER integer for s")
        }
        offset += 1

        let sLength = Int(signature[offset])
        offset += 1

        let sBytes = signature[offset..<(offset + sLength)]
        offset += sLength

        // Ensure the entire signature has been processed
        guard offset == signature.count else {
            throw ApproovServiceError.permanentError(message: "Extra data in ASN.1 DER signature")
        }

        return try to32ByteData(bytes: rBytes) + to32ByteData(bytes: sBytes)
    }

    /**
     * Converts one part, encoded as an ASN1Integer, of an ASN.1 DER encoded ES256 signature to a byte array of
     * exactly 32 bytes. Throws IllegalArgumentException if this is not possible.
     *
     * @param bytesAsASN1Integer The ASN1Integer to convert.
     * @return A byte array of length 32, containing the raw bytes of the signature part.
     * @throws IllegalArgumentException if the ASN1Integer is not representing a 32 byte array.
     */
    private static func to32ByteData(bytes: Data) throws -> Data {
        if bytes.count < 32 {
            let padding = Data(repeating: 0, count: 32 - bytes.count)
            return padding + bytes
        } else if bytes.count == 32 {
            // Return as-is if the byte array is exactly 32 bytes
            return bytes
        } else if bytes.count == 33 && bytes.first == 0 {
            // Remove the leading zero if the byte array is 33 bytes and starts with 0
            return bytes.dropFirst()
        } else {
            // Throw an error if the byte array cannot be represented as 32 bytes
            throw ApproovServiceError.permanentError(message: "Not an ASN.1 DER ES256 signature part")
        }
    }

    /**
     * Generates a default `SignatureParametersFactory` with predefined settings and optional base parameters.
     *
     * - Parameter baseParametersOverride: The base parameters to override, or `nil` to use defaults.
     * - Returns: A new instance of `SignatureParametersFactory`.
     */
    public static func generateDefaultSignatureParametersFactory(baseParametersOverride: SignatureParameters? = nil) -> SignatureParametersFactory {
        // Default expiry seconds - must encompass worst-case request retry time and clock skew
        let defaultExpiresLifetime: Int64 = 15
        let baseParameters: SignatureParameters

        if let override = baseParametersOverride {
            baseParameters = override
        } else {
            baseParameters = SignatureParameters()
                .addComponentIdentifier(ApproovURLSessionComponentProvider.DC_METHOD)
                .addComponentIdentifier(ApproovURLSessionComponentProvider.DC_TARGET_URI)
        }

        let defaultSignatureParametersFactory = SignatureParametersFactory()
            .setBaseParameters(baseParameters)
            .setUseInstallMessageSigning()
            .setAddCreated(true)
            .setExpiresLifetime(defaultExpiresLifetime)
            .setAddApproovTokenHeader(true)
            .setAddApproovTraceIDHeader(true)
            .addOptionalHeaders(["Authorization", "Content-Length", "Content-Type"])
        do {
            try defaultSignatureParametersFactory.setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, required: false)
        } catch {
            // ApproovDefaultMessageSigning.DIGEST_SHA256 is a supported body digest algorithm - will never throw
            os_log("ApproovDefaultMessageSigning - generateDefaultSignatureParametersFactory: Failed to set default body digest algorithm", type: .error)
        }
        return defaultSignatureParametersFactory
    }
}

/**
 * Factory class for creating pre-request `SignatureParameters` with configurable settings.
 * Each request passed to the factory builds a new `SignatureParameters` instance based on
 * the configured settings and specific for the request.
 */
public class SignatureParametersFactory {
    private var baseParameters: SignatureParameters?
    private var bodyDigestAlgorithm: String?
    private var bodyDigestRequired: Bool = false
    private var useAccountMessageSigning: Bool = false
    private var addCreated: Bool = false
    private var expiresLifetime: Int64 = 0
    private var addApproovTokenHeader: Bool = false
    private var addApproovTraceIDHeader: Bool = false
    private var optionalHeaders: [String] = []

    /**
     * Sets the base parameters for the factory.
     *
     * - Parameter baseParameters: The base parameters to set.
     * - Returns: The current instance for method chaining.
     */
    public func setBaseParameters(_ baseParameters: SignatureParameters) -> SignatureParametersFactory {
        self.baseParameters = baseParameters
        return self
    }

    /**
     * Configures the body digest settings for the factory.
     *
     * - Parameters:
     *   - bodyDigestAlgorithm: The digest algorithm to use, or `nil` to disable.
     *   - required: Whether the body digest is required.
     * - Returns: The current instance for method chaining.
     * - Throws: An error if an unsupported algorithm is specified.
     */
    public func setBodyDigestConfig(_ bodyDigestAlgorithm: String?, required: Bool) throws -> SignatureParametersFactory {
        if let algorithm = bodyDigestAlgorithm {
            guard algorithm == ApproovDefaultMessageSigning.DIGEST_SHA256 ||
                  algorithm == ApproovDefaultMessageSigning.DIGEST_SHA512 else {
                throw ApproovServiceError.permanentError(message: "Unsupported body digest algorithm: \(algorithm)")
            }
            self.bodyDigestAlgorithm = algorithm
            self.bodyDigestRequired = required
        } else {
            self.bodyDigestRequired = false
        }
        return self
    }

    /**
     * Configures the factory to use device message signing.
     *
     * - Returns: The current instance for method chaining.
     */
    public func setUseInstallMessageSigning() -> SignatureParametersFactory {
        self.useAccountMessageSigning = false
        return self
    }

    /**
     * Configures the factory to use account message signing.
     *
     * - Returns: The current instance for method chaining.
     */
    public func setUseAccountMessageSigning() -> SignatureParametersFactory {
        self.useAccountMessageSigning = true
        return self
    }

    /**
     * Sets whether the "created" field should be added to the signature parameters.
     *
     * - Parameter addCreated: Whether to add the "created" field.
     * - Returns: The current instance for method chaining.
     */
    public func setAddCreated(_ addCreated: Bool) -> SignatureParametersFactory {
        self.addCreated = addCreated
        return self
    }

    /**
     * Sets the expiration lifetime for the signature parameters.
     *
     * - Parameter expiresLifetime: The expiration lifetime in seconds. If <=0, no expiration is added.
     * - Returns: The current instance for method chaining.
     */
    public func setExpiresLifetime(_ expiresLifetime: Int64) -> SignatureParametersFactory {
        self.expiresLifetime = expiresLifetime
        return self
    }

    /**
     * Sets whether the Approov token header should be added to the signature parameters.
     *
     * - Parameter addApproovTokenHeader: Whether to add the Approov token header.
     * - Returns: The current instance for method chaining.
     */
    public func setAddApproovTokenHeader(_ addApproovTokenHeader: Bool) -> SignatureParametersFactory {
        self.addApproovTokenHeader = addApproovTokenHeader
        return self
    }

    /**
     * Sets whether the Approov TraceID header should be added to the signature parameters.
     *
     * - Parameter addApproovTraceIDHeader: Whether to add the Approov TraceID header.
     * - Returns: The current instance for method chaining.
     */
    public func setAddApproovTraceIDHeader(_ addApproovTraceIDHeader: Bool) -> SignatureParametersFactory {
        self.addApproovTraceIDHeader = addApproovTraceIDHeader
        return self
    }

    /**
     * Adds optional headers to the signature parameters.
     *
     * - Parameter headers: The headers to add.
     * - Returns: The current instance for method chaining.
     */
    public func addOptionalHeaders(_ headers: [String]) -> SignatureParametersFactory {
        self.optionalHeaders.append(contentsOf: headers)
        return self
    }

    /**
     * Builds the signature parameters for a given request.
     *
     * - Parameters:
     *   - provider: The component provider for the request.
     *   - changes: The request mutations to apply.
     * - Returns: The generated `SignatureParameters`.
     * - Throws: An error if required parameters cannot be generated.
     */
    func buildSignatureParameters(provider: ApproovURLSessionComponentProvider, changes: ApproovRequestMutations) throws -> SignatureParameters {
        var requestParameters: SignatureParameters
        if baseParameters == nil {
            requestParameters = SignatureParameters()
        } else {
            requestParameters = SignatureParameters(base: baseParameters!) // Safe to unwrap, cannot be nil
        }
        requestParameters.setAlg(useAccountMessageSigning ? ApproovDefaultMessageSigning.ALG_HS256 : ApproovDefaultMessageSigning.ALG_ES256)

        if addCreated || expiresLifetime > 0 {
            let currentTime = Int64(Date().timeIntervalSince1970)
            if addCreated {
                requestParameters.setCreated(currentTime)
            }
            if expiresLifetime > 0 {
                requestParameters.setExpires(currentTime + expiresLifetime)
            }
        }

        if addApproovTokenHeader, let tokenHeaderKey = changes.getTokenHeaderKey() {
            requestParameters.addComponentIdentifier(tokenHeaderKey)
        }

        if addApproovTraceIDHeader, let traceIDHeaderKey = changes.getTraceIDHeaderKey() {
            requestParameters.addComponentIdentifier(traceIDHeaderKey)
        }

        for headerName in optionalHeaders {
            if provider.hasField(name: headerName) {
                requestParameters.addComponentIdentifier(headerName)
            }
        }

        if bodyDigestAlgorithm != nil {
            let bodyDigestCreated = try generateBodyDigest(provider: provider, requestParameters: requestParameters)
            if !bodyDigestCreated && bodyDigestRequired {
                throw ApproovServiceError.permanentError(message: "Failed to create required body digest")
            }
        }

        return requestParameters
    }

    /**
     * Generates a body digest for the request if possible.
     *
     * Warning: This method updates the provider's request with the generated body digest in the header "Content-Digest".
     *
     * - Parameters:
     *   - provider: The component provider for the request.
     *   - requestParameters: The signature parameters to update.
     * - Returns: `true` if the body digest was successfully generated, `false` otherwise.
     */
    private func generateBodyDigest(provider: ApproovURLSessionComponentProvider, requestParameters: SignatureParameters) throws -> Bool {
        var request = provider.getRequest()

        guard let body = SignatureParametersFactory.getHTTPBody(request) else {
            // If there is no replayable body, we can't generate a digest.
            return false
        }

        // If no body digest algorithm has been set, we don't generate a digest
        guard let bodyDigestAlg = bodyDigestAlgorithm else {
            return false
        }

        // Generate the digest
        let digest: Data
        switch bodyDigestAlg {
        case ApproovDefaultMessageSigning.DIGEST_SHA256:
            digest = ApproovDefaultMessageSigning.sha256(data: body)
        case ApproovDefaultMessageSigning.DIGEST_SHA512:
            digest = SignatureParametersFactory.sha512(data: body)
        default:
            throw ApproovServiceError.permanentError(message: "Unsupported body digest algorithm: \(bodyDigestAlg)")
        }

        // Add the digest header to the request
        do {
            guard let digestHeader = try SFV.serializeDictionary(key: bodyDigestAlg, data: digest) else {
                throw ApproovServiceError.permanentError(message: "Failed to serialize Content-Digest header")
            }
            // Replace any existing digest so re-processing the same request
            // does not accumulate duplicate Content-Digest headers.
            request.setValue(digestHeader, forHTTPHeaderField: "Content-Digest")
            provider.setRequest(request)
        } catch let error {
            throw ApproovServiceError.permanentError(message: "Failed to serialize Content-Digest header: \(error)")
        }
        requestParameters.addComponentIdentifier("Content-Digest")
        return true
    }

    /**
     * Gets the HTTP body from the request when it can be replayed safely.
     *
     * Requests backed by a stream are skipped here because reading the stream
     * for signing can consume bytes before URLSession sends the request.
     * 
     * @param request is the URLRequest to extract the body from.
     * @return the HTTP body as Data, or nil if not available or not safely replayable.
     */
    private static func getHTTPBody(_ request: URLRequest) -> Data? {
        if let body = request.httpBody {
            return body.isEmpty ? nil : body
        } else if request.httpBodyStream != nil {
            return nil
        }
        return nil
    }

    /**
     * SHA512 of given input bytes.
     *
     * @param data is the input data
     * @return the hash data
     */
    private static func sha512(data: Data) -> Data {
        var hash = [UInt8](repeating: 0,  count: Int(CC_SHA512_DIGEST_LENGTH))
        data.withUnsafeBytes {
            _ = CC_SHA512($0.baseAddress, CC_LONG(data.count), &hash)
        }
        return Data(hash)
    }
}

/**
 * ApproovURLSessionComponentProvider implements the ComponentProvider protocol for URLSession requests.
 */
class ApproovURLSessionComponentProvider: ComponentProvider {

    private var request: URLRequest

    init(request: URLRequest) {
        self.request = request
    }

    public func getRequest() -> URLRequest {
        return request
    }

    public func setRequest(_ newRequest: URLRequest) {
        self.request = newRequest
    }

    public func getMethod() -> String {
        return request.httpMethod ?? "GET"
    }

    public func getAuthority() -> String {
        return request.url?.host ?? ""
    }

    public func getScheme() -> String {
        return request.url?.scheme ?? "http"
    }

    public func getTargetUri() -> String {
        guard let url = request.url,
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return request.url?.absoluteString ?? ""
        }
        if components.host != nil && components.percentEncodedPath.isEmpty {
            components.percentEncodedPath = "/"
        }
        return components.string ?? url.absoluteString
    }

    public func getRequestTarget() -> String {
        var target = getPath()
        if let query = percentEncodedQuery() {
            target += "?\(query)"
        }
        return target
    }

    public func getPath() -> String {
        let path = urlComponents()?.percentEncodedPath ?? request.url?.path ?? ""
        if path.isEmpty, request.url != nil {
            return "/"
        }
        return path
    }

    public func getQuery() -> String {
        return percentEncodedQuery() ?? ""
    }

    public func getQueryParam(name: String) -> String? {
        guard let url = request.url,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let queryItems = components.queryItems else {
            return nil
        }
        let values = queryItems.filter { $0.name == name }.compactMap { $0.value }
        if values.count > 1 {
            // From Section 2.2.8 of the spec: If a parameter name occurs multiple times in a request, the named query
            // parameter MUST NOT be included.
            // If multiple parameters are common within an application, it is RECOMMENDED to sign the entire query
            // string using the @query component identifier defined in Section 2.2.7.
            // To indicate that a query param must not be included, we return nil.
            return nil
        }
        return values.first
    }

    public func hasField(name: String) -> Bool {
        return request.value(forHTTPHeaderField: name) != nil
    }

    public func getField(name: String) -> String? {
        guard let value = request.value(forHTTPHeaderField: name) else {
            return nil
        }
        return ApproovURLSessionComponentProvider.combineFieldValues(fields: [value])
    }

    public func hasBody() -> Bool {
        return request.httpBody != nil || request.httpBodyStream != nil
    }

    private func urlComponents() -> URLComponents? {
        guard let url = request.url else {
            return nil
        }
        return URLComponents(url: url, resolvingAgainstBaseURL: false)
    }

    private func percentEncodedQuery() -> String? {
        return urlComponents()?.percentEncodedQuery
    }
}

// @available(*, deprecated, message: "Use ApproovServiceMutator instead.")
// extension ApproovDefaultMessageSigning: ApproovInterceptorExtensions {}
