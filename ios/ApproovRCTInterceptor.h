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

/// ReactNative interceptor that is able to add Approov onto requests being made
/// by the built-in networking stack
@interface ApproovRCTInterceptor : NSObject

/// ApproovService being used by the interceptor
@property(readonly) ApproovService *approovService;

/// Creates a ReactNative interceptor.
///
/// @param approovService the ApproovService used to update requests
+ (instancetype)startWithApproovService:(ApproovService *)approovService;

// MARK: - Configuration APIs (NEW)

/// Sets the interception mode for session tracking
/// @param mode 0=AllowList (default), 1=DenyList, 2=All
+ (void)setInterceptionMode:(NSInteger)mode;

/// Adds a delegate pattern to the allow list
/// @param delegatePattern delegate class name or pattern (use * for wildcard)
+ (void)addAllowedDelegate:(NSString *)delegatePattern;

/// Adds a delegate pattern to the exclusion list
/// @param delegatePattern delegate class name or pattern (use * for wildcard)
+ (void)addExcludedDelegate:(NSString *)delegatePattern;

/// Removes a delegate pattern from the allow list
/// @param delegatePattern delegate class name or pattern
+ (void)removeAllowedDelegate:(NSString *)delegatePattern;

/// Removes a delegate pattern from the exclusion list
/// @param delegatePattern delegate class name or pattern
+ (void)removeExcludedDelegate:(NSString *)delegatePattern;

// MARK: - Diagnostic APIs (NEW)

/// Returns diagnostic information about tracked sessions
+ (NSDictionary *)getSessionDiagnostics;

/// Returns diagnostic information about pinning validation
+ (NSDictionary *)getPinningDiagnostics;

/// Validates that pinning is active and logs warnings if not
+ (void)validatePinningIsActive;

/// Returns IMP integrity diagnostics — detects if another SDK has
/// overwritten our swizzled method implementations
+ (NSDictionary *)getIMPIntegrityDiagnostics;

@end

NS_ASSUME_NONNULL_END
