import Foundation

enum ApproovServiceStubState {
    static var exclusionRegexs = NSMutableSet()
    static var useApproovStatusIfNoToken = false
    static var installSignatureBase64: String?
    static var accountSignatureBase64: String?
    static var lastInstallMessage: String?
    static var lastAccountMessage: String?

    static func reset() {
        exclusionRegexs = NSMutableSet()
        useApproovStatusIfNoToken = false
        installSignatureBase64 = nil
        accountSignatureBase64 = nil
        lastInstallMessage = nil
        lastAccountMessage = nil
    }
}

@objc class ApproovService: NSObject {
    @objc static func sharedExclusionURLRegexs() -> NSMutableSet {
        return ApproovServiceStubState.exclusionRegexs
    }

    @objc static func sharedUseApproovStatusIfNoToken() -> Bool {
        return ApproovServiceStubState.useApproovStatusIfNoToken
    }

    @objc static func getInstallMessageSignature(_ message: String) -> String? {
        ApproovServiceStubState.lastInstallMessage = message
        return ApproovServiceStubState.installSignatureBase64
    }

    @objc static func getAccountMessageSignature(_ message: String) -> String? {
        ApproovServiceStubState.lastAccountMessage = message
        return ApproovServiceStubState.accountSignatureBase64
    }
}
