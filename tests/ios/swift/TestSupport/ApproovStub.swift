import Foundation

public enum ApproovTokenFetchStatus {
    case success
    case unknownURL
    case unprotectedURL
    case noNetwork
    case poorNetwork
    case mitmDetected
    case noApproovService
    case badURL
    case rejected
    case unknownKey
    case disabled
    case internalError
}

public final class ApproovTokenFetchResult {
    public let status: ApproovTokenFetchStatus
    public let arc: String
    public let rejectionReasons: String

    public init(status: ApproovTokenFetchStatus,
                arc: String = "",
                rejectionReasons: String = "") {
        self.status = status
        self.arc = arc
        self.rejectionReasons = rejectionReasons
    }
}

public enum Approov {
    public static func string(from status: ApproovTokenFetchStatus) -> String {
        switch status {
        case .success:
            return "SUCCESS"
        case .unknownURL:
            return "UNKNOWN_URL"
        case .unprotectedURL:
            return "UNPROTECTED_URL"
        case .noNetwork:
            return "NO_NETWORK"
        case .poorNetwork:
            return "POOR_NETWORK"
        case .mitmDetected:
            return "MITM_DETECTED"
        case .noApproovService:
            return "NO_APPROOV_SERVICE"
        case .badURL:
            return "BAD_URL"
        case .rejected:
            return "REJECTED"
        case .unknownKey:
            return "UNKNOWN_KEY"
        case .disabled:
            return "DISABLED"
        case .internalError:
            return "INTERNAL_ERROR"
        }
    }
}
