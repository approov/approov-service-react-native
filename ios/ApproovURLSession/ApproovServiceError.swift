// MIT License
//
// Copyright (c) 2016-present, Approov Ltd.
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

import Foundation

/**
 *  Approov error conditions.
 */
public enum ApproovServiceError: Error, LocalizedError, CustomNSError {
    
    /**
     *  An error due to a permanent condition. A retry is unlikely to succeed.
     */
    case permanentError(message: String)
    
    /**
     *  An error due to a temporary networking condition. A retry may succeed.
     */
    case networkingError(message: String)
    
    /**
     *  An error due to a rejection by the Approov service.
     */
    case rejectionError(message: String, ARC: String?, rejectionReasons: [String]?)
    
    /**
     *  The error description.
     */
    public var errorDescription: String? {
        switch self {
        case .permanentError(let message):
            return "Approov permanent error: \(message)"
        case .networkingError(let message):
            return "Approov networking error: \(message)"
        case .rejectionError(let message, let ARC, let rejectionReasons):
            var details = ""
            if let arc = ARC {
                details += " ARC: \(arc)"
            }
            if let reasons = rejectionReasons {
                details += " Reasons: \(reasons.joined(separator: ", "))"
            }
            return "Approov rejection error: \(message)\(details)"
        }
    }
    
    public static var errorDomain: String {
        return "io.approov.reactnative"
    }
    
    public var errorCode: Int {
        return 0
    }
    
    public var errorUserInfo: [String : Any] {
        var userInfo: [String: Any] = [:]
        userInfo[NSLocalizedDescriptionKey] = self.errorDescription ?? "Unknown Approov Error"
        
        switch self {
        case .permanentError:
            userInfo["type"] = "general"
        case .networkingError:
            userInfo["type"] = "network"
        case .rejectionError(_, let ARC, let rejectionReasons):
            userInfo["type"] = "rejection"
            if let arc = ARC {
                userInfo["rejectionARC"] = arc
            }
            if let reasons = rejectionReasons {
                userInfo["rejectionReasons"] = reasons.joined(separator: ",")
            }
        }
        return userInfo
    }
}
