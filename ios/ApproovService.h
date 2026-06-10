/*
 * MIT License
 *
 * Copyright (c) 2016-present, CriticalBlue Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#import <React/RCTBridgeModule.h>

/// Recommended actions after an interception
typedef NS_ENUM(NSInteger, ApproovInterceptorAction) {
  ApproovInterceptorActionProceed,
  ApproovInterceptorActionRetry,
  ApproovInterceptorActionFail,
};

/// Results generated as a result of a networking interception
@interface ApproovInterceptorResult : NSObject

/// The updated request
@property NSURLRequest *request;

/// The recommended next action
@property ApproovInterceptorAction action;

/// Message describing the result
@property(copy) NSString *message;

/// Creates an interceptor result.
///
/// @param request the updated request
/// @param action the recommended action
/// @param message the result message
+ (instancetype)createWithRequest:(NSURLRequest *)request
                       withAction:(ApproovInterceptorAction)action
                      withMessage:(NSString *)message;

/// Initializes an interceptors result.
///
/// @param request the updated request
/// @param action the recommended action
/// @param message the result message
- (instancetype)initWithRequest:(NSURLRequest *)request
                     withAction:(ApproovInterceptorAction)action
                    withMessage:(NSString *)message;

@end

/// Trust verification decisions
typedef NS_ENUM(NSUInteger, ApproovTrustDecision) {
  ApproovTrustDecisionAllow,
  ApproovTrustDecisionBlock,
  ApproovTrustDecisionNotPinned,
};

/// ApproovService wraps the underlying Approov SDK, provides network
/// interceptors and bridges calls from Javascript
@interface ApproovService : NSObject <RCTBridgeModule>

/// Returns the current shared service instance when available.
+ (nullable ApproovService *)sharedService;

/// Intercepts a request and updates it to potentially add an Approov token
/// and/or perform substitutions on headers and query parameters.
///
/// @param request the requesst
/// @return the result including a modifieed request and status code and message
- (ApproovInterceptorResult * _Nonnull)interceptRequest:(NSURLRequest * _Nonnull)request;

/// Verifies the server presents valid pinned certificates.
///
/// @param serverTrust the server's trust object
/// @param host the requested server host name
/// @return a trust decision - allow, block, or not pinned
- (ApproovTrustDecision)verifyPins:(SecTrustRef _Nonnull)serverTrust
                           forHost:(NSString * _Nonnull)host;

/// Returns the current state of proceed on network fail.
///
/// @return YES if proceed on network fail is enabled
// + (BOOL)sharedProceedOnNetworkFailure;

+ (BOOL)sharedUseApproovStatusIfNoToken;

/// Returns the current token header.
///
/// @return the current token header
+ (NSString * _Nullable)sharedTokenHeader;

/// Returns the current trace ID header.
///
/// @return the current trace ID header
+ (NSString * _Nullable)sharedTraceIDHeader;

/// Returns the current exclusion URL regexs.
///
/// @return a set of exclusion URL regexs
+ (NSMutableSet<NSString *> * _Nullable)sharedExclusionURLRegexs;

/// Gets the signature for the given message using the install private key.
///
/// @param message is the message to be signed
/// @return the base64 encoded signature
+ (NSString * _Nullable)getInstallMessageSignature:(NSString * _Nonnull)message;

/// Gets the signature for the given message using the account signing key.
///
/// @param message is the message to be signed
/// @return the base64 encoded signature
+ (NSString * _Nullable)getAccountMessageSignature:(NSString * _Nonnull)message;

/// Performs a secure fetch bypassing any swizzling, applying Approov
/// protections and pinning.
///
/// @param url the requested URL
/// @param options dictionary containing headers, body, method, etc.
/// @param resolve promise resolver
/// @param reject promise rejecter
- (void)fetchWithApproov:(NSString * _Nonnull)url
                 options:(NSDictionary * _Nullable)options
                resolver:(RCTPromiseResolveBlock _Nonnull)resolve
                rejecter:(RCTPromiseRejectBlock _Nonnull)reject;


@end
