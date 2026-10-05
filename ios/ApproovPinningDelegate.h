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

#import "ApproovService.h"
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// An NSURLSessionDelegate for applying the dynamic pins provided by Approov
@interface PinningURLSessionDelegate : NSObject <NSURLSessionDelegate>

// Callback to notify interceptor of auth challenges
@property(nonatomic, copy) void (^authChallengeCallback)
    (NSString *host, ApproovTrustDecision decision);

/// Creates a pinning URL session delegate.
///
/// @param delegate is the original delgate
/// @param approovService is the ApproovService that will provide the pinning
/// information
+ (instancetype)createWithDelegate:(id<NSURLSessionDataDelegate> _Nullable)delegate
                    approovService:(ApproovService *_Nullable)approovService;

/// Initializes a pinning URL session delegate.
///
/// @param delegate is the original delgate
/// @param approovService is the ApproovService that will provide the pinning
/// information
- (instancetype)initWithDelegate:(id<NSURLSessionDataDelegate> _Nullable)delegate
                  approovService:(ApproovService *_Nullable)approovService;

/// Records the headers and URL a request had before Approov processed it, on
/// the processed request, so a redirect can undo exactly what Approov added.
/// The record is stored as NSURLProtocol properties, which URLSession keeps on
/// the task's requests and on the redirect request it builds. It holds the
/// app's own values (placeholders, not substituted secrets) and only SHA-256
/// digests of what Approov applied, never the token, signatures or secrets.
///
/// @param original is the request as the app built it
/// @param processed is the request Approov will send
+ (void)recordPreApproovRequest:(NSURLRequest *)original
             onProcessedRequest:(NSMutableURLRequest *)processed;

/**
 * Takes out of a request everything Approov applied to the request it was built
 * from (for example a completed task's currentRequest copied into a new task),
 * using the record that request carries, so only the app's own values are
 * processed again. A request without a record is returned unchanged.
 */
+ (NSURLRequest *)requestByUndoingRecordedApproovChangesIn:(NSURLRequest *)request;

@end

NS_ASSUME_NONNULL_END
