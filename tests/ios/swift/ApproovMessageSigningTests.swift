import Foundation
import Approov

private var failureCount = 0

private func fail(_ message: String) {
    fputs("FAIL: \(message)\n", stderr)
    failureCount += 1
}

private func assertTrue(_ condition: @autoclosure () -> Bool, _ message: String) {
    if !condition() {
        fail(message)
    }
}

private func assertFalse(_ condition: @autoclosure () -> Bool, _ message: String) {
    if condition() {
        fail(message)
    }
}

private func assertEqual(_ expected: String?, _ actual: String?, _ message: String) {
    if expected != actual {
        fail("\(message) (expected \(expected ?? "nil"), got \(actual ?? "nil"))")
    }
}

private func assertNil(_ actual: String?, _ message: String) {
    if actual != nil {
        fail("\(message) (got \(actual!))")
    }
}

private func countOccurrences(_ needle: String, in haystack: String?) -> Int {
    guard let haystack else {
        return 0
    }

    var count = 0
    var searchStart = haystack.startIndex
    while let range = haystack.range(of: needle, range: searchStart..<haystack.endIndex) {
        count += 1
        searchStart = range.upperBound
    }
    return count
}

private func derSignatureBase64() -> String {
    let der = Data([0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02])
    return der.base64EncodedString()
}

private func rawP1363SignatureBase64() -> String {
    var raw = Data(repeating: 0, count: 64)
    raw[31] = 0x01
    raw[63] = 0x02
    return raw.base64EncodedString()
}

private func targetURL() -> URL {
    if let value = ProcessInfo.processInfo.environment["TESTING_REPLY_URL"],
       let url = URL(string: value) {
        return url
    }
    return URL(string: "https://replay.ivol.workers.dev")!
}

private func defaultSigner() -> ApproovDefaultMessageSigning {
    return ApproovDefaultMessageSigning()
        .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory())
}

private func defaultChanges() -> ApproovRequestMutations {
    let changes = ApproovRequestMutations()
    changes.setTokenHeaderKey("Approov-Token")
    changes.setTraceIDHeaderKey("Approov-TraceID")
    return changes
}

private func signedRequestFixture() -> URLRequest {
    var request = URLRequest(url: URL(string: "https://api.example.com/reply")!)
    request.httpMethod = "POST"
    request.httpBody = Data("{\"hello\":\"world\"}".utf8)
    request.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")
    request.setValue("trace-123", forHTTPHeaderField: "Approov-TraceID")
    request.setValue("Bearer auth-token", forHTTPHeaderField: "Authorization")
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.setValue("stale-signature", forHTTPHeaderField: "Signature")
    request.setValue("stale-input", forHTTPHeaderField: "Signature-Input")
    request.setValue("stale-digest", forHTTPHeaderField: "Signature-Base-Digest")
    return request
}

private func streamBytes(from stream: InputStream?) -> String? {
    guard let stream else {
        return nil
    }

    stream.open()
    defer {
        stream.close()
    }

    var bytes = [UInt8]()
    var buffer = [UInt8](repeating: 0, count: 256)
    while stream.hasBytesAvailable {
        let read = stream.read(&buffer, maxLength: buffer.count)
        if read <= 0 {
            break
        }
        bytes.append(contentsOf: buffer.prefix(read))
    }
    return String(data: Data(bytes), encoding: .utf8)
}

private final class RecordingMutator: ApproovServiceMutator {
    let processRequest: (URLRequest, ApproovRequestMutations) throws -> URLRequest
    let handleFetchToken: ((ApproovTokenFetchResult, String) throws -> Bool)?

    init(processRequest: @escaping (URLRequest, ApproovRequestMutations) throws -> URLRequest = { request, _ in request },
         handleFetchToken: ((ApproovTokenFetchResult, String) throws -> Bool)? = nil) {
        self.processRequest = processRequest
        self.handleFetchToken = handleFetchToken
    }

    func handleInterceptorFetchTokenResult(_ approovResults: ApproovTokenFetchResult,
                                           url: String) throws -> Bool {
        if let handleFetchToken {
            return try handleFetchToken(approovResults, url)
        }
        return try ApproovServiceMutatorDefault.shared.handleInterceptorFetchTokenResult(approovResults, url: url)
    }

    func handleInterceptorProcessedRequest(_ request: URLRequest,
                                           changes: ApproovRequestMutations) throws -> URLRequest {
        return try processRequest(request, changes)
    }
}

private final class ProceedOnSelectedStatusesMutator: ApproovServiceMutator {
    private let signer: ApproovDefaultMessageSigning

    init() {
        self.signer = defaultSigner()
    }

    func handleInterceptorFetchTokenResult(_ approovResults: ApproovTokenFetchResult,
                                           url: String) throws -> Bool {
        switch approovResults.status {
        case .success, .mitmDetected, .noApproovService:
            return true
        default:
            return try ApproovServiceMutatorDefault.shared.handleInterceptorFetchTokenResult(approovResults, url: url)
        }
    }

    func handleInterceptorProcessedRequest(_ request: URLRequest,
                                           changes: ApproovRequestMutations) throws -> URLRequest {
        return try signer.handleInterceptorProcessedRequest(request, changes: changes)
    }
}

private final class UnsupportedAlgorithmFactory: SignatureParametersFactory {
    override init() {
        super.init()
        _ = setBaseParameters(SignatureParameters()
            .addComponentIdentifier(ApproovURLSessionComponentProvider.DC_METHOD)
            .addComponentIdentifier(ApproovURLSessionComponentProvider.DC_TARGET_URI))
            .setAddApproovTokenHeader(true)
    }

    override func buildSignatureParameters(provider: ApproovURLSessionComponentProvider,
                                           changes: ApproovRequestMutations) throws -> SignatureParameters {
        let params = try super.buildSignatureParameters(provider: provider, changes: changes)
        params.setAlg("unsupported-alg")
        return params
    }
}

private func bouncedReply(for request: URLRequest) throws -> [String: Any] {
    let semaphore = DispatchSemaphore(value: 0)
    var reply: [String: Any]?
    var responseError: Error?

    URLSession.shared.dataTask(with: request) { data, _, error in
        responseError = error
        defer { semaphore.signal() }

        guard let data else { return }
        reply = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }.resume()

    if semaphore.wait(timeout: .now() + 10) == .timedOut {
        throw NSError(domain: "ApproovMessageSigningTests",
                      code: 2,
                      userInfo: [NSLocalizedDescriptionKey: "Request timed out after 10 seconds"])
    }

    if let responseError {
        throw responseError
    }

    guard let reply else {
        throw NSError(domain: "ApproovMessageSigningTests",
                      code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "Failed to decode worker reply"])
    }
    return reply
}

private func headerValue(_ key: String, in reply: [String: Any]) -> String? {
    guard let headers = reply["headers"] as? [String: Any] else {
        return nil
    }
    if let value = headers[key.lowercased()] as? String {
        return value
    }
    if let value = headers[key] as? String {
        return value
    }
    if let values = headers[key.lowercased()] as? [String], let first = values.first {
        return first
    }
    if let values = headers[key] as? [String], let first = values.first {
        return first
    }
    return nil
}

private func testDefaultSigningAddsRequiredHeadersAndComputesTheSignature() throws {
    ApproovServiceStubState.reset()
    ApproovServiceStubState.installSignatureBase64 = derSignatureBase64()

    let signed = try defaultSigner().handleInterceptorProcessedRequest(
        signedRequestFixture(),
        changes: defaultChanges()
    )

    assertEqual("Bearer jwt-token",
                signed.value(forHTTPHeaderField: "Approov-Token"),
                "Default signing should preserve the Approov token")
    assertEqual("trace-123",
                signed.value(forHTTPHeaderField: "Approov-TraceID"),
                "Default signing should preserve the Approov trace header")
    assertTrue(ApproovServiceStubState.lastInstallMessage?.isEmpty == false,
               "Default signing should compute a signature base")
    assertTrue(ApproovServiceStubState.lastInstallMessage?.contains("\"@signature-params\"") == true,
               "Signature base should include the signature parameters component")
    assertTrue(signed.value(forHTTPHeaderField: "Content-Digest")?.isEmpty == false,
               "Default signing should add a Content-Digest header when the request has a body")
    assertTrue(signed.value(forHTTPHeaderField: "Signature")?.contains("install=:") == true,
               "Default signing should add the Signature header")
    assertTrue(signed.value(forHTTPHeaderField: "Signature")?.contains(":\(rawP1363SignatureBase64()):") == true,
               "Default signing should encode install signatures as raw IEEE-P1363 bytes")
    assertTrue(signed.value(forHTTPHeaderField: "Signature-Input")?.contains("install=(") == true,
               "Default signing should add the Signature-Input header")
    assertTrue(signed.value(forHTTPHeaderField: "Signature") != "stale-signature",
               "Default signing should replace any stale Signature header")
    assertTrue(signed.value(forHTTPHeaderField: "Signature-Input") != "stale-input",
               "Default signing should replace any stale Signature-Input header")
    assertNil(signed.value(forHTTPHeaderField: "Signature-Base-Digest"),
              "Default signing should clear Signature-Base-Digest when debug mode is disabled")
}

private func testDefaultSigningIsIdempotentAndDoesNotDoubleSign() throws {
    ApproovServiceStubState.reset()
    ApproovServiceStubState.installSignatureBase64 = derSignatureBase64()

    let signer = defaultSigner()
    let signedOnce = try signer.handleInterceptorProcessedRequest(
        signedRequestFixture(),
        changes: defaultChanges()
    )
    let signedTwice = try signer.handleInterceptorProcessedRequest(
        signedOnce,
        changes: defaultChanges()
    )

    assertTrue(countOccurrences("install=:", in: signedTwice.value(forHTTPHeaderField: "Signature")) == 1,
               "Signing the same request twice should not duplicate Signature entries")
    assertTrue(countOccurrences("install=", in: signedTwice.value(forHTTPHeaderField: "Signature-Input")) == 1,
               "Signing the same request twice should not duplicate Signature-Input entries")
    assertTrue(signedTwice.value(forHTTPHeaderField: "Signature") != "stale-signature",
               "Idempotent signing should keep replacing stale Signature values")
    assertTrue(signedTwice.value(forHTTPHeaderField: "Signature-Input") != "stale-input",
               "Idempotent signing should keep replacing stale Signature-Input values")
    assertNil(signedTwice.value(forHTTPHeaderField: "Signature-Base-Digest"),
              "Idempotent signing should not restore Signature-Base-Digest in default mode")
}

private func testDefaultSigningCanonicalizesRootTargetUri() throws {
    ApproovServiceStubState.reset()
    ApproovServiceStubState.installSignatureBase64 = derSignatureBase64()

    var request = URLRequest(url: URL(string: "https://api.example.com?hello=world")!)
    request.httpMethod = "GET"
    request.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")
    request.setValue("trace-123", forHTTPHeaderField: "Approov-TraceID")

    _ = try defaultSigner().handleInterceptorProcessedRequest(
        request,
        changes: defaultChanges()
    )

    assertTrue(ApproovServiceStubState.lastInstallMessage?.contains("https://api.example.com/?hello=world") == true,
               "Root-path target URIs should be canonicalized with an explicit slash")
}

private func testMutatorBridgeSignsWithCustomTokenHeaderAndOptionalTrace() throws {
    ApproovServiceStubState.reset()
    ApproovServiceStubState.installSignatureBase64 = derSignatureBase64()

    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = defaultSigner()

    let request = NSMutableURLRequest(url: URL(string: "https://api.example.com/reply")!)
    request.httpMethod = "POST"
    request.httpBody = Data("{\"ok\":true}".utf8)
    request.setValue("Bearer custom-token", forHTTPHeaderField: "X-Approov-Token")
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")

    bridge.processRequest(request, tokenHeader: "X-Approov-Token", traceIDHeader: "Approov-TraceID")

    assertTrue(request.value(forHTTPHeaderField: "Signature")?.contains("install=:") == true,
               "The mutator bridge should still sign requests when the token header is customized")
    assertTrue(ApproovServiceStubState.lastInstallMessage?.contains("\"x-approov-token\"") == true,
               "The signature base should use the dynamic Approov token header name")
    assertFalse(ApproovServiceStubState.lastInstallMessage?.contains("\"approov-traceid\"") == true,
                "The mutator bridge should not require a trace header when none is present")
}

private func testMutatorBridgeIntegratesMessageSigningEndToEnd() throws {
    ApproovServiceStubState.reset()
    ApproovServiceStubState.installSignatureBase64 = derSignatureBase64()

    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = defaultSigner()

    let request = NSMutableURLRequest(url: URL(string: "https://api.example.com/reply")!)
    request.httpMethod = "POST"
    request.httpBody = Data("{\"hello\":\"world\"}".utf8)
    request.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")
    request.setValue("trace-123", forHTTPHeaderField: "Approov-TraceID")
    request.setValue("Bearer auth-token", forHTTPHeaderField: "Authorization")
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")

    bridge.processRequest(request, tokenHeader: "Approov-Token", traceIDHeader: "Approov-TraceID")

    assertEqual("Bearer jwt-token",
                request.value(forHTTPHeaderField: "Approov-Token"),
                "The mutator bridge should preserve the injected Approov token")
    assertEqual("trace-123",
                request.value(forHTTPHeaderField: "Approov-TraceID"),
                "The mutator bridge should preserve the injected Approov trace header")
    assertTrue(request.value(forHTTPHeaderField: "Content-Digest")?.isEmpty == false,
               "The mutator bridge should add Content-Digest when message signing runs")
    assertTrue(request.value(forHTTPHeaderField: "Signature")?.contains("install=:") == true,
               "The mutator bridge should add the Signature header")
    assertTrue(request.value(forHTTPHeaderField: "Signature-Input")?.contains("install=(") == true,
               "The mutator bridge should add the Signature-Input header")
    assertTrue(ApproovServiceStubState.lastInstallMessage?.contains("\"approov-token\"") == true,
               "The signature base should include the dynamic Approov token header")
    assertTrue(ApproovServiceStubState.lastInstallMessage?.contains("\"approov-traceid\"") == true,
               "The signature base should include the Approov trace header when present")
}

private func testProceedingFailureStatusCanBeSignedAndBounced() throws {
    ApproovServiceStubState.reset()
    ApproovServiceStubState.installSignatureBase64 = derSignatureBase64()

    let bridge = ApproovServiceMutatorBridge.shared
    let mutator = ProceedOnSelectedStatusesMutator()
    bridge.serviceMutator = mutator

    let allowsSuccess = try mutator.handleInterceptorFetchTokenResult(
        ApproovTokenFetchResult(status: .success),
        url: targetURL().absoluteString
    )
    let allowsMitm = try mutator.handleInterceptorFetchTokenResult(
        ApproovTokenFetchResult(status: .mitmDetected),
        url: targetURL().absoluteString
    )
    let allowsNoService = try mutator.handleInterceptorFetchTokenResult(
        ApproovTokenFetchResult(status: .noApproovService),
        url: targetURL().absoluteString
    )
    assertTrue(allowsSuccess, "Custom mutator should allow SUCCESS")
    assertTrue(allowsMitm, "Custom mutator should allow MITM_DETECTED")
    assertTrue(allowsNoService, "Custom mutator should allow NO_APPROOV_SERVICE")

    let request = NSMutableURLRequest(url: targetURL())
    request.httpMethod = "POST"
    request.httpBody = Data("{\"hello\":\"world\"}".utf8)
    request.setValue("MITM_DETECTED", forHTTPHeaderField: "Approov-Token")
    request.setValue("trace-123", forHTTPHeaderField: "Approov-TraceID")
    request.setValue("Bearer auth-token", forHTTPHeaderField: "Authorization")
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")

    bridge.processRequest(request, tokenHeader: "Approov-Token", traceIDHeader: "Approov-TraceID")

    let reply = try bouncedReply(for: request as URLRequest)

    assertEqual("MITM_DETECTED",
                headerValue("Approov-Token", in: reply),
                "Worker should receive the failure reason in the Approov token header")
    assertEqual("trace-123",
                headerValue("Approov-TraceID", in: reply),
                "Worker should receive the preserved trace header")
    assertTrue(headerValue("Content-Digest", in: reply)?.isEmpty == false,
               "Worker should receive Content-Digest when the bounced request is signed")
    assertTrue(headerValue("Signature", in: reply)?.contains("install=:") == true,
               "Worker should receive the Signature header for a signed proceeding failure status")
    assertTrue(headerValue("Signature-Input", in: reply)?.contains("install=(") == true,
               "Worker should receive the Signature-Input header for a signed proceeding failure status")
    assertTrue(ApproovServiceStubState.lastInstallMessage?.contains("\"approov-token\"") == true,
               "The signature base should include the status-valued Approov token header")
    assertTrue(ApproovServiceStubState.lastInstallMessage?.contains("\"approov-traceid\"") == true,
               "The signature base should include the trace header when it is present")
}

private func testInstallSigningCanBeBouncedEndToEnd() throws {
    ApproovServiceStubState.reset()
    ApproovServiceStubState.installSignatureBase64 = derSignatureBase64()

    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = defaultSigner()

    let request = NSMutableURLRequest(url: targetURL())
    request.httpMethod = "POST"
    request.httpBody = Data("{\"hello\":\"world\"}".utf8)
    request.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")
    request.setValue("trace-123", forHTTPHeaderField: "Approov-TraceID")
    request.setValue("Bearer auth-token", forHTTPHeaderField: "Authorization")
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")

    bridge.processRequest(request, tokenHeader: "Approov-Token", traceIDHeader: "Approov-TraceID")

    let reply = try bouncedReply(for: request as URLRequest)

    assertTrue(headerValue("Signature", in: reply)?.contains("install=:") == true,
               "Worker should receive install Signature headers")
    assertTrue(headerValue("Signature-Input", in: reply)?.contains("install=(") == true,
               "Worker should receive install Signature-Input headers")
    assertFalse(headerValue("Signature", in: reply)?.contains("account=") == true,
                "Install signing should not emit account signatures")
    assertTrue(ApproovServiceStubState.lastInstallMessage?.contains("\"approov-token\"") == true,
               "Install signing should include the Approov token header in the signature base")
    assertNil(ApproovServiceStubState.lastAccountMessage,
              "Install signing should not call account signing")
}

private func testAccountSigningCanBeBouncedEndToEnd() throws {
    ApproovServiceStubState.reset()
    ApproovServiceStubState.accountSignatureBase64 = Data("account-signature".utf8).base64EncodedString()

    let factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
    _ = factory.setUseAccountMessageSigning()
    let signer = ApproovDefaultMessageSigning().setDefaultFactory(factory)

    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = signer

    let request = NSMutableURLRequest(url: targetURL())
    request.httpMethod = "POST"
    request.httpBody = Data("{\"hello\":\"world\"}".utf8)
    request.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")
    request.setValue("trace-123", forHTTPHeaderField: "Approov-TraceID")
    request.setValue("Bearer auth-token", forHTTPHeaderField: "Authorization")
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")

    bridge.processRequest(request, tokenHeader: "Approov-Token", traceIDHeader: "Approov-TraceID")

    let reply = try bouncedReply(for: request as URLRequest)

    assertTrue(headerValue("Signature", in: reply)?.contains("account=:") == true,
               "Worker should receive account Signature headers")
    assertTrue(headerValue("Signature-Input", in: reply)?.contains("account=(") == true,
               "Worker should receive account Signature-Input headers")
    assertFalse(headerValue("Signature", in: reply)?.contains("install=") == true,
                "Account signing should not emit install signatures")
    assertTrue(ApproovServiceStubState.lastAccountMessage?.contains("\"approov-token\"") == true,
               "Account signing should include the Approov token header in the signature base")
    assertNil(ApproovServiceStubState.lastInstallMessage,
              "Account signing should not call install signing")
}

private func testSigningFailureFallbackCanBeBouncedEndToEnd() throws {
    ApproovServiceStubState.reset()

    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = defaultSigner()

    let request = NSMutableURLRequest(url: targetURL())
    request.httpMethod = "POST"
    request.httpBody = Data("{\"hello\":\"world\"}".utf8)
    request.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")
    request.setValue("trace-123", forHTTPHeaderField: "Approov-TraceID")
    request.setValue("Bearer auth-token", forHTTPHeaderField: "Authorization")
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")

    bridge.processRequest(request, tokenHeader: "Approov-Token", traceIDHeader: "Approov-TraceID")

    let reply = try bouncedReply(for: request as URLRequest)

    assertEqual("Bearer jwt-token",
                headerValue("Approov-Token", in: reply),
                "Signing fallback should preserve the Approov token header")
    assertEqual("trace-123",
                headerValue("Approov-TraceID", in: reply),
                "Signing fallback should preserve the Approov trace header")
    assertNil(headerValue("Content-Digest", in: reply),
              "Signing fallback should not leave a Content-Digest header behind")
    assertNil(headerValue("Signature", in: reply),
              "Signing fallback should not add a Signature header")
    assertNil(headerValue("Signature-Input", in: reply),
              "Signing fallback should not add a Signature-Input header")
    assertTrue(ApproovServiceStubState.lastInstallMessage?.contains("\"approov-token\"") == true,
               "Signing fallback should still attempt to sign the request")
}

private func testAccountSigningUnavailableFallsOpenThroughBridge() {
    ApproovServiceStubState.reset()

    let factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
    _ = factory.setUseAccountMessageSigning()
    let signer = ApproovDefaultMessageSigning().setDefaultFactory(factory)

    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = signer

    let request = NSMutableURLRequest(url: targetURL())
    request.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")
    request.setValue("trace-123", forHTTPHeaderField: "Approov-TraceID")

    var error: NSError?
    let succeeded = bridge.processRequest(request,
                                          tokenHeader: "Approov-Token",
                                          traceIDHeader: "Approov-TraceID",
                                          errorPointer: &error)

    assertTrue(succeeded, "Unavailable account signing should proceed unsigned")
    assertNil(error?.localizedDescription, "Unavailable account signing should not report a strict error")
    assertNil(request.value(forHTTPHeaderField: "Signature"),
              "Unavailable account signing should not add a Signature header")
    assertNil(request.value(forHTTPHeaderField: "Signature-Input"),
              "Unavailable account signing should not add a Signature-Input header")
    assertTrue(ApproovServiceStubState.lastAccountMessage?.contains("\"approov-token\"") == true,
               "Account signing should still attempt to sign the request")
}

private func testUnsupportedSigningAlgorithmPropagatesThroughBridge() {
    ApproovServiceStubState.reset()

    let signer = ApproovDefaultMessageSigning().setDefaultFactory(UnsupportedAlgorithmFactory())
    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = signer

    let request = NSMutableURLRequest(url: targetURL())
    request.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")

    var error: NSError?
    let succeeded = bridge.processRequest(request,
                                          tokenHeader: "Approov-Token",
                                          traceIDHeader: "Approov-TraceID",
                                          errorPointer: &error)

    assertFalse(succeeded, "Unsupported signing algorithms should fail closed")
    assertTrue(error?.localizedDescription.contains("Unsupported algorithm identifier") == true,
               "Strict signing error should be reported to Objective-C")
}

private func testRequiredBodyDigestFailurePropagatesThroughBridge() throws {
    ApproovServiceStubState.reset()

    let factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
    _ = try factory.setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, required: true)
    let signer = ApproovDefaultMessageSigning().setDefaultFactory(factory)
    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = signer

    let request = NSMutableURLRequest(url: targetURL())
    request.httpMethod = "POST"
    request.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")

    var error: NSError?
    let succeeded = bridge.processRequest(request,
                                          tokenHeader: "Approov-Token",
                                          traceIDHeader: "Approov-TraceID",
                                          errorPointer: &error)

    assertFalse(succeeded, "Required body digest failures should fail closed")
    assertTrue(error?.localizedDescription.contains("Failed to create required body digest") == true,
               "Required digest failure should be reported to Objective-C")
}

private func testDigestBodyBehaviorCanBeBouncedEndToEnd() throws {
    ApproovServiceStubState.reset()
    ApproovServiceStubState.installSignatureBase64 = derSignatureBase64()

    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = defaultSigner()

    let postRequest = NSMutableURLRequest(url: targetURL())
    postRequest.httpMethod = "POST"
    postRequest.httpBody = Data("{\"hello\":\"world\"}".utf8)
    postRequest.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")
    postRequest.setValue("trace-123", forHTTPHeaderField: "Approov-TraceID")
    postRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
    bridge.processRequest(postRequest, tokenHeader: "Approov-Token", traceIDHeader: "Approov-TraceID")
    let postReply = try bouncedReply(for: postRequest as URLRequest)
    assertTrue(headerValue("Content-Digest", in: postReply)?.isEmpty == false,
               "Signed POST requests should include Content-Digest")
    assertTrue(headerValue("Signature", in: postReply)?.contains("install=:") == true,
               "Signed POST requests should include Signature")

    let getRequest = NSMutableURLRequest(url: targetURL())
    getRequest.httpMethod = "GET"
    getRequest.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")
    getRequest.setValue("trace-123", forHTTPHeaderField: "Approov-TraceID")
    bridge.processRequest(getRequest, tokenHeader: "Approov-Token", traceIDHeader: "Approov-TraceID")
    let getReply = try bouncedReply(for: getRequest as URLRequest)
    assertNil(headerValue("Content-Digest", in: getReply),
              "Signed GET requests without a body should not include Content-Digest")
    assertTrue(headerValue("Signature", in: getReply)?.contains("install=:") == true,
               "Signed GET requests should still include Signature")
    assertTrue(headerValue("Signature-Input", in: getReply)?.contains("install=(") == true,
               "Signed GET requests should still include Signature-Input")
}

private func testSingleSignatureApplicationCanBeBouncedEndToEnd() throws {
    ApproovServiceStubState.reset()
    ApproovServiceStubState.installSignatureBase64 = derSignatureBase64()

    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = defaultSigner()

    let request = NSMutableURLRequest(url: targetURL())
    request.httpMethod = "POST"
    request.httpBody = Data("{\"hello\":\"world\"}".utf8)
    request.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")
    request.setValue("trace-123", forHTTPHeaderField: "Approov-TraceID")
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.setValue("stale-signature", forHTTPHeaderField: "Signature")
    request.setValue("stale-input", forHTTPHeaderField: "Signature-Input")
    request.setValue("stale-digest", forHTTPHeaderField: "Signature-Base-Digest")

    bridge.processRequest(request, tokenHeader: "Approov-Token", traceIDHeader: "Approov-TraceID")
    bridge.processRequest(request, tokenHeader: "Approov-Token", traceIDHeader: "Approov-TraceID")

    let reply = try bouncedReply(for: request as URLRequest)
    let signature = headerValue("Signature", in: reply)
    let signatureInput = headerValue("Signature-Input", in: reply)

    assertTrue(signature?.contains("install=:") == true,
               "Signed requests should include the install signature")
    assertTrue(signatureInput?.contains("install=(") == true,
               "Signed requests should include the install Signature-Input")
    assertFalse(signature?.contains("stale-signature") == true,
                "Signed requests should replace stale Signature values")
    assertFalse(signatureInput?.contains("stale-input") == true,
                "Signed requests should replace stale Signature-Input values")
    assertTrue(countOccurrences("install=:", in: signature) == 1,
               "Bounced requests should only contain one Signature entry")
    assertTrue(countOccurrences("install=", in: signatureInput) == 1,
               "Bounced requests should only contain one Signature-Input entry")
}

private func testMutatorBridgeCopiesBackFullRequestState() {
    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = RecordingMutator { request, _ in
        var updated = request
        updated.url = URL(string: "https://api.example.com/rewritten?ok=yes")!
        updated.httpMethod = "PATCH"
        updated.httpBody = Data("rewritten-body".utf8)
        updated.timeoutInterval = 42
        updated.setValue("yes", forHTTPHeaderField: "X-Mutated")
        return updated
    }

    let request = NSMutableURLRequest(url: URL(string: "https://api.example.com/original")!)
    request.httpMethod = "POST"
    request.httpBody = Data("original-body".utf8)
    request.timeoutInterval = 5
    request.setValue("Bearer token", forHTTPHeaderField: "Approov-Token")

    bridge.processRequest(request, tokenHeader: "Approov-Token", traceIDHeader: "Approov-TraceID")

    assertEqual("https://api.example.com/rewritten?ok=yes",
                request.url?.absoluteString,
                "The mutator bridge should copy back URL changes")
    assertEqual("PATCH",
                request.httpMethod,
                "The mutator bridge should copy back the HTTP method")
    assertEqual("rewritten-body",
                request.httpBody.flatMap { String(data: $0, encoding: .utf8) },
                "The mutator bridge should copy back body data")
    assertTrue(request.timeoutInterval == 42,
               "The mutator bridge should copy back timeoutInterval changes")
    assertEqual("yes",
                request.value(forHTTPHeaderField: "X-Mutated"),
                "The mutator bridge should copy back header mutations")
}

private func testMutatorBridgePreservesHttpBodyStreams() {
    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = RecordingMutator { request, _ in
        var updated = request
        updated.httpMethod = "PUT"
        updated.httpBody = nil
        updated.httpBodyStream = InputStream(data: Data("stream-body".utf8))
        updated.setValue("stream", forHTTPHeaderField: "X-Body-Type")
        return updated
    }

    let request = NSMutableURLRequest(url: URL(string: "https://api.example.com/upload")!)
    request.httpMethod = "POST"
    request.httpBody = Data("original-body".utf8)
    request.setValue("Bearer token", forHTTPHeaderField: "Approov-Token")

    bridge.processRequest(request, tokenHeader: "Approov-Token", traceIDHeader: "Approov-TraceID")

    assertEqual("PUT",
                request.httpMethod,
                "The mutator bridge should preserve method changes alongside stream bodies")
    assertEqual("stream-body",
                streamBytes(from: request.httpBodyStream),
                "The mutator bridge should preserve stream-backed bodies")
    assertEqual("stream",
                request.value(forHTTPHeaderField: "X-Body-Type"),
                "The mutator bridge should preserve header changes for stream-backed requests")
}

private func testMutatorBridgePropagatesCustomFetchTokenErrors() {
    let bridge = ApproovServiceMutatorBridge.shared
    bridge.serviceMutator = RecordingMutator(handleFetchToken: { _, _ in
        throw ApproovServiceError.permanentError(message: "custom no service block")
    })

    var error: NSError?
    let shouldProceed = bridge.handleInterceptorFetchTokenResult(
        ApproovTokenFetchResult(status: .noApproovService),
        url: "example.com",
        errorPointer: &error
    )

    assertFalse(shouldProceed,
                "Custom fetch-token mutators should be able to block NO_APPROOV_SERVICE results")
    assertTrue(error?.localizedDescription.contains("custom no service block") == true,
               "The bridge should surface custom fetch-token errors back to Objective-C")
    assertEqual("general",
                error?.userInfo["type"] as? String,
                "Permanent mutator errors should expose the general error type")
}

@main
struct ApproovMessageSigningTestsRunner {
    static func main() {
        do {
            try testDefaultSigningAddsRequiredHeadersAndComputesTheSignature()
            try testDefaultSigningIsIdempotentAndDoesNotDoubleSign()
            try testDefaultSigningCanonicalizesRootTargetUri()
            try testMutatorBridgeSignsWithCustomTokenHeaderAndOptionalTrace()
            try testMutatorBridgeIntegratesMessageSigningEndToEnd()
            try testProceedingFailureStatusCanBeSignedAndBounced()
            try testInstallSigningCanBeBouncedEndToEnd()
            try testAccountSigningCanBeBouncedEndToEnd()
            try testSigningFailureFallbackCanBeBouncedEndToEnd()
            testAccountSigningUnavailableFallsOpenThroughBridge()
            testUnsupportedSigningAlgorithmPropagatesThroughBridge()
            try testRequiredBodyDigestFailurePropagatesThroughBridge()
            try testDigestBodyBehaviorCanBeBouncedEndToEnd()
            try testSingleSignatureApplicationCanBeBouncedEndToEnd()
            testMutatorBridgeCopiesBackFullRequestState()
            testMutatorBridgePreservesHttpBodyStreams()
            testMutatorBridgePropagatesCustomFetchTokenErrors()
        } catch {
            fail("Unexpected Swift message-signing test error: \(error)")
        }

        if failureCount > 0 {
            fputs("\(failureCount) Swift message-signing test(s) failed\n", stderr)
            exit(1)
        }

        print("All iOS Swift message-signing tests passed")
    }
}
