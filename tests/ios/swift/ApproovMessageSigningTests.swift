import Foundation

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

@main
struct ApproovMessageSigningTestsRunner {
    static func main() {
        do {
            try testDefaultSigningAddsRequiredHeadersAndComputesTheSignature()
            try testDefaultSigningIsIdempotentAndDoesNotDoubleSign()
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
