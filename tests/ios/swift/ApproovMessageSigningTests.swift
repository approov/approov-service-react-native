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
