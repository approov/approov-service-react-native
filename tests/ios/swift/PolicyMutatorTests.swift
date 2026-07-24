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

private func assertEqualInt(_ expected: Int32, _ actual: Int32, _ message: String) {
    if expected != actual {
        fail("\(message) (expected \(expected), got \(actual))")
    }
}

// A minimal but valid ASN.1 DER ES256 signature (SEQUENCE { INTEGER 1, INTEGER 2 })
// so the delegated install-signing path produces a Signature header.
private func derSignatureBase64() -> String {
    let der = Data([0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02])
    return der.base64EncodedString()
}

private func defaultChanges() -> ApproovRequestMutations {
    let changes = ApproovRequestMutations()
    changes.setTokenHeaderKey("Approov-Token")
    changes.setTraceIDHeaderKey("Approov-TraceID")
    return changes
}

private func signableRequestFixture() -> URLRequest {
    var request = URLRequest(url: URL(string: "https://api.example.com/reply")!)
    request.httpMethod = "POST"
    request.httpBody = Data("{\"hello\":\"world\"}".utf8)
    request.setValue("Bearer jwt-token", forHTTPHeaderField: "Approov-Token")
    request.setValue("trace-123", forHTTPHeaderField: "Approov-TraceID")
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    return request
}

private func result(_ status: ApproovTokenFetchStatus) -> ApproovTokenFetchResult {
    return ApproovTokenFetchResult(status: status)
}

// Returns the PROCEED/FORWARD boolean, or records a failure and returns nil if
// the mutator unexpectedly threw (i.e. it BLOCKED when it should not have).
private func proceedDecision(_ mutator: PolicyMutator,
                             _ status: ApproovTokenFetchStatus,
                             _ context: String) -> Bool? {
    do {
        return try mutator.handleInterceptorFetchTokenResult(result(status),
                                                             url: "https://api.example.com/reply")
    } catch {
        fail("\(context): \(status) unexpectedly threw (\(error))")
        return nil
    }
}

// Asserts that the mutator BLOCKS the given status: it must throw
// ApproovServiceError.permanentError (userInfo type "general"), never return
// false. Returning false would reach the interceptor's Retry/proceed fallback
// and diverge from Android's hard-fail.
private func assertBlocks(_ mutator: PolicyMutator,
                          _ status: ApproovTokenFetchStatus,
                          _ context: String) {
    do {
        let proceeded = try mutator.handleInterceptorFetchTokenResult(
            result(status),
            url: "https://api.example.com/reply")
        fail("\(context): expected \(status) to BLOCK by throwing, but it returned \(proceeded)")
    } catch let ApproovServiceError.permanentError(message) {
        assertTrue(message.contains("PolicyMutator blocked"),
                   "\(context): \(status) should throw a PolicyMutator permanent error (got: \(message))")
        // The permanent error must expose the general type so Objective-C maps it to Fail.
        let type = (ApproovServiceError.permanentError(message: message) as NSError).userInfo["type"] as? String
        assertTrue(type == "general",
                   "\(context): blocked \(status) must be a general (Fail) error, got \(type ?? "nil")")
    } catch {
        fail("\(context): expected ApproovServiceError.permanentError for \(status), got \(error)")
    }
}

private func testCanonicalBitValuesMatchCrossPlatformContract() {
    // These MUST equal the Android PolicyMutator.BIT_* constants and the JS
    // ApproovService.ReturnDecision bits. Do not change.
    assertEqualInt(1 << 0, PolicyMutator.BIT_NO_APPROOV_SERVICE, "BIT_NO_APPROOV_SERVICE")
    assertEqualInt(1 << 1, PolicyMutator.BIT_BAD_URL, "BIT_BAD_URL")
    assertEqualInt(1 << 2, PolicyMutator.BIT_MITM_DETECTED, "BIT_MITM_DETECTED")
    assertEqualInt(1 << 3, PolicyMutator.BIT_NO_NETWORK, "BIT_NO_NETWORK")
    assertEqualInt(1 << 4, PolicyMutator.BIT_POOR_NETWORK, "BIT_POOR_NETWORK")
    assertEqualInt(1 << 5, PolicyMutator.BIT_REJECTED, "BIT_REJECTED")
    assertEqualInt(1 << 6, PolicyMutator.BIT_UNKNOWN_KEY, "BIT_UNKNOWN_KEY")
    assertEqualInt(1 << 7, PolicyMutator.BIT_INTERNAL_ERROR, "BIT_INTERNAL_ERROR")
    assertEqualInt(1 << 8, PolicyMutator.BIT_NO_NETWORK_PERMISSION, "BIT_NO_NETWORK_PERMISSION")
    assertEqualInt(1 << 9, PolicyMutator.BIT_MISSING_LIB_DEPENDENCY, "BIT_MISSING_LIB_DEPENDENCY")
    assertEqualInt(1 << 10, PolicyMutator.BIT_DISABLED, "BIT_DISABLED")
}

private func testBaselineIsNonMaskable() {
    // Even with an empty proceed mask, the baseline always applies.
    let mutator = PolicyMutator(proceedMask: 0)
    assertTrue(proceedDecision(mutator, .success, "baseline") == true,
               "SUCCESS must always PROCEED (return true)")
    assertTrue(proceedDecision(mutator, .unknownURL, "baseline") == false,
               "UNKNOWN_URL must FORWARD (return false)")
    assertTrue(proceedDecision(mutator, .unprotectedURL, "baseline") == false,
               "UNPROTECTED_URL must FORWARD (return false)")
}

private func testEmptyMaskBlocksEveryFailureStatus() {
    let mutator = PolicyMutator(proceedMask: 0)
    // With no bits set, every maskable failure status must BLOCK (throw).
    assertBlocks(mutator, .noApproovService, "empty mask")
    assertBlocks(mutator, .badURL, "empty mask")
    assertBlocks(mutator, .mitmDetected, "empty mask")
    assertBlocks(mutator, .poorNetwork, "empty mask")
    assertBlocks(mutator, .rejected, "empty mask")
    assertBlocks(mutator, .unknownKey, "empty mask")
    assertBlocks(mutator, .internalError, "empty mask")
    assertBlocks(mutator, .disabled, "empty mask")
    // Network statuses BLOCK too (hard fail) rather than following the default
    // signer's Retry path — this is the key Android-parity divergence.
    assertBlocks(mutator, .noNetwork, "empty mask")
}

private func testInMaskFailureProceedsOthersBlock() {
    let mutator = PolicyMutator(proceedMask: PolicyMutator.BIT_MITM_DETECTED)
    assertTrue(proceedDecision(mutator, .mitmDetected, "single-bit mask") == true,
               "A failure status whose bit is set must PROCEED")
    // Any other failure status is not in the mask and must BLOCK.
    assertBlocks(mutator, .badURL, "single-bit mask")
    assertBlocks(mutator, .noApproovService, "single-bit mask")
    assertBlocks(mutator, .noNetwork, "single-bit mask")
    // Baseline still applies regardless of the mask.
    assertTrue(proceedDecision(mutator, .success, "single-bit mask") == true,
               "SUCCESS still PROCEEDs under a failure-only mask")
}

private func testMultipleMaskBitsProceedIndependently() {
    let mask = PolicyMutator.BIT_NO_APPROOV_SERVICE | PolicyMutator.BIT_BAD_URL
    let mutator = PolicyMutator(proceedMask: mask)
    assertTrue(proceedDecision(mutator, .noApproovService, "multi-bit mask") == true,
               "NO_APPROOV_SERVICE bit set must PROCEED")
    assertTrue(proceedDecision(mutator, .badURL, "multi-bit mask") == true,
               "BAD_URL bit set must PROCEED")
    assertBlocks(mutator, .mitmDetected, "multi-bit mask")
    assertBlocks(mutator, .rejected, "multi-bit mask")
}

private func testSignFalseForwardsRequestUnchanged() throws {
    ApproovServiceStubState.reset()
    ApproovServiceStubState.installSignatureBase64 = derSignatureBase64()

    let mutator = PolicyMutator(proceedMask: PolicyMutator.BIT_MITM_DETECTED, sign: false)
    let request = signableRequestFixture()
    let forwarded = try mutator.handleInterceptorProcessedRequest(request, changes: defaultChanges())

    // Unsigned: no signing headers must be added, and the request is unchanged.
    assertTrue(forwarded.value(forHTTPHeaderField: "Signature") == nil,
               "sign:false must not add a Signature header")
    assertTrue(forwarded.value(forHTTPHeaderField: "Signature-Input") == nil,
               "sign:false must not add a Signature-Input header")
    assertTrue(forwarded.value(forHTTPHeaderField: "Content-Digest") == nil,
               "sign:false must not add a Content-Digest header")
    assertTrue(forwarded.value(forHTTPHeaderField: "Approov-Token") == "Bearer jwt-token",
               "sign:false must preserve the Approov token header")
    assertTrue(forwarded.url == request.url,
               "sign:false must forward the original request URL unchanged")
    // The signer must not have been invoked at all when signing is disabled.
    assertTrue(ApproovServiceStubState.lastInstallMessage == nil,
               "sign:false must not invoke the message signer")
}

private func testSignTrueDelegatesToTheSigner() throws {
    ApproovServiceStubState.reset()
    ApproovServiceStubState.installSignatureBase64 = derSignatureBase64()

    // Default init: sign defaults to true.
    let mutator = PolicyMutator(proceedMask: PolicyMutator.BIT_MITM_DETECTED)
    let request = signableRequestFixture()
    let signed = try mutator.handleInterceptorProcessedRequest(request, changes: defaultChanges())

    assertTrue(signed.value(forHTTPHeaderField: "Signature")?.contains("install=:") == true,
               "sign:true must delegate to the signer and add a Signature header")
    assertTrue(signed.value(forHTTPHeaderField: "Signature-Input")?.contains("install=(") == true,
               "sign:true must delegate to the signer and add a Signature-Input header")
    assertTrue(signed.value(forHTTPHeaderField: "Approov-Token") == "Bearer jwt-token",
               "sign:true must preserve the Approov token header")
    assertTrue(ApproovServiceStubState.lastInstallMessage?.isEmpty == false,
               "sign:true must invoke the message signer (build a signature base)")
}

private func testBridgeInstallsPolicyMutatorAndSurfacesBlockAsFail() {
    let bridge = ApproovServiceMutatorBridge.shared

    bridge.setPolicyMutator(PolicyMutator.BIT_MITM_DETECTED, sign: true)
    assertTrue(bridge.serviceMutator is PolicyMutator,
               "setPolicyMutator should install a PolicyMutator as the active mutator")

    // In-mask failure PROCEEDs via the Objective-C bridge with no error.
    var proceedError: NSError?
    let proceeds = bridge.handleInterceptorFetchTokenResult(result(.mitmDetected),
                                                            url: "https://api.example.com/reply",
                                                            errorPointer: &proceedError)
    assertTrue(proceeds, "MITM_DETECTED in the mask should PROCEED through the bridge")
    assertTrue(proceedError == nil, "A proceeding status should not surface an error")

    // Not-in-mask failure must surface a general (Fail) error, not just false.
    var blockError: NSError?
    let blockedProceeds = bridge.handleInterceptorFetchTokenResult(result(.badURL),
                                                                   url: "https://api.example.com/reply",
                                                                   errorPointer: &blockError)
    assertFalse(blockedProceeds, "BAD_URL not in the mask must not PROCEED through the bridge")
    assertTrue(blockError != nil, "A blocked status must surface an explicit error to Objective-C")
    assertTrue(blockError?.userInfo["type"] as? String == "general",
               "A blocked status must be a general error so Objective-C maps it to Fail")

    // resetToDefault restores the built-in signing default.
    bridge.resetToDefault()
    assertTrue(bridge.serviceMutator is ApproovDefaultMessageSigning,
               "resetToDefault should restore an ApproovDefaultMessageSigning mutator")
    assertFalse(bridge.serviceMutator is PolicyMutator,
                "resetToDefault should remove the installed PolicyMutator")
}

@main
struct PolicyMutatorTestsRunner {
    static func main() {
        testCanonicalBitValuesMatchCrossPlatformContract()
        testBaselineIsNonMaskable()
        testEmptyMaskBlocksEveryFailureStatus()
        testInMaskFailureProceedsOthersBlock()
        testMultipleMaskBitsProceedIndependently()
        testBridgeInstallsPolicyMutatorAndSurfacesBlockAsFail()

        do {
            try testSignFalseForwardsRequestUnchanged()
            try testSignTrueDelegatesToTheSigner()
        } catch {
            fail("Unexpected Swift PolicyMutator test error: \(error)")
        }

        if failureCount > 0 {
            fputs("\(failureCount) Swift PolicyMutator test(s) failed\n", stderr)
        } else {
            print("All iOS Swift PolicyMutator tests passed")
        }
        exit(Int32(failureCount))
    }
}
