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

#import "ApproovRCTInterceptor.h"
#import "ApproovMockURLProtocol.h"
#import "ApproovPinningDelegate.h"
#import "ApproovUtils.h"
#import "RSSwizzle.h"

// MARK: - Session Interception Mode

/// Defines how sessions are intercepted
typedef NS_ENUM(NSInteger, SessionInterceptionMode) {
  SessionInterceptionModeAllowList, // Only intercept known delegates (default)
  SessionInterceptionModeDenyList,  // Intercept all except known exclusions
  SessionInterceptionModeAll        // Intercept everything
};

// MARK: - Session Metadata

/// Metadata tracked for each intercepted session
@interface SessionMetadata : NSObject
@property(nonatomic, strong) NSString *delegateClassName;
@property(nonatomic, strong) NSDate *createdAt;
@property(nonatomic, assign) NSUInteger requestCount;

// Pinning validation tracking
@property(nonatomic, assign) NSUInteger authChallengeCount;
@property(nonatomic, assign) NSUInteger pinnedChallengeCount;
@property(nonatomic, assign) NSUInteger blockedChallengeCount;
@property(nonatomic, strong) NSDate *lastAuthChallengeAt;
@property(nonatomic, assign) BOOL pinningDelegateVerified;
@end

@implementation SessionMetadata
@end

// MARK: - Session Interception Policy

/// Policy for determining which sessions to intercept
@interface SessionInterceptionPolicy : NSObject
@property(nonatomic, assign) SessionInterceptionMode mode;
@property(nonatomic, strong) NSSet<NSString *> *allowedDelegates;
@property(nonatomic, strong) NSSet<NSString *> *excludedDelegates;

- (BOOL)shouldInterceptSessionWithDelegate:(NSString *)delegateClassName;
- (BOOL)isExcludedDelegateClassName:(NSString *)delegateClassName;
- (BOOL)matchesPattern:(NSString *)className
                 inSet:(NSSet<NSString *> *)patterns;
@end

@implementation SessionInterceptionPolicy

- (instancetype)init {
  self = [super init];
  if (self) {
    // Default: Allow known delegates (backward compatible)
    _mode = SessionInterceptionModeAllowList;
    _allowedDelegates = [NSSet setWithArray:@[
      @"RCT*",                       // React Native (prefix match)
      @"RNFetchBlobRequest",         // rn-fetch-blob
      @"NRMAURLSessionTaskDelegate", // NewRelic
      @"SentryNSURLSessionDelegate", // Sentry
      @"Sentry*", // Sentry (prefix match for any Sentry delegates)
    ]];
    _excludedDelegates = [NSSet setWithArray:@[
      @"RCTMultipartDataTask" // Bundle reload (excluded)
    ]];
  }
  return self;
}

- (BOOL)shouldInterceptSessionWithDelegate:(NSString *)delegateClassName {
  // Check exclusions first
  if ([self matchesPattern:delegateClassName inSet:_excludedDelegates]) {
    return NO;
  }

  switch (_mode) {
  case SessionInterceptionModeAll:
    return YES;

  case SessionInterceptionModeDenyList:
    return ![self matchesPattern:delegateClassName inSet:_excludedDelegates];

  case SessionInterceptionModeAllowList:
    return [self matchesPattern:delegateClassName inSet:_allowedDelegates];
  }

  // Defensive fallback for unexpected enum values.
  return [self matchesPattern:delegateClassName inSet:_allowedDelegates];
}

- (BOOL)isExcludedDelegateClassName:(NSString *)delegateClassName {
  return [self matchesPattern:delegateClassName inSet:_excludedDelegates];
}

- (BOOL)matchesPattern:(NSString *)className
                 inSet:(NSSet<NSString *> *)patterns {
  for (NSString *pattern in patterns) {
    if ([pattern hasSuffix:@"*"]) {
      // Prefix match
      NSString *prefix = [pattern substringToIndex:pattern.length - 1];
      if ([className hasPrefix:prefix])
        return YES;
    } else {
      // Exact match
      if ([className isEqualToString:pattern])
        return YES;
    }
  }
  return NO;
}

@end

// MARK: - ApproovRCTInterceptor Implementation

@implementation ApproovRCTInterceptor {
  // Track all pinned sessions with metadata
  NSMapTable<NSURLSession *, SessionMetadata *> *_pinnedSessions;

  // Configuration for which sessions to intercept
  SessionInterceptionPolicy *_policy;

  // Thread safety
  dispatch_queue_t _sessionRegistryQueue;
  NSLock *_policyLock;
}

// the single shared intercetor for React Native
static ApproovRCTInterceptor *_sharedInterceptor = nil;

// ensure the singleton is only created once
static dispatch_once_t _onceToken = 0;

/**
 * Creates a ReactNative interceptor.
 *
 * @param approovService the ApproovService used to update requests
 */
+ (instancetype)startWithApproovService:(ApproovService *)approovService {
  dispatch_once(&_onceToken, ^{
    _sharedInterceptor = [[self alloc] initWithApproovService:approovService];
  });
  return _sharedInterceptor;
}

/**
 * Initializes the ReactNative interceptor.
 *
 * @param approovService the ApproovService used to update requests
 */
- (instancetype)initWithApproovService:(ApproovService *)approovService {
  // initialize the adapter
  self = [super init];
  if (!self) {
    return self;
  }
  _approovService = approovService;

  // Thread-safe session registry using weak-to-strong map table
  // #5)
  _pinnedSessions = [NSMapTable weakToStrongObjectsMapTable];

  // Serial queue for session registry operations
  _sessionRegistryQueue = dispatch_queue_create("io.approov.sessionRegistry",
                                                DISPATCH_QUEUE_SERIAL);

  // Lock for policy updates
  _policyLock = [[NSLock alloc] init];

  // Initialize policy
  _policy = [[SessionInterceptionPolicy alloc] init];

  // swizzle react native session creation methods
  [self swizzleRCTSessionCreation];

  // swizzle react native session data task creation methods
  [self swizzleRCTSessionDataTasks];

  // Swizzle session invalidation for cleanup
  [self swizzleSessionInvalidation];

  return self;
}

/**
 * Determines whether a delegate can participate in TLS challenge handling.
 * This lets us intercept third-party delegates without relying on brittle class
 * name matching.
 */
- (BOOL)delegateSupportsAuthChallenge:(id)delegate {
  if (!delegate) {
    return NO;
  }
  return [delegate
              respondsToSelector:@selector(URLSession:didReceiveChallenge:
                                                        completionHandler:)] ||
         [delegate
             respondsToSelector:@selector(
                                    URLSession:task:didReceiveChallenge:
                                                   completionHandler:)] ||
         [delegate
             respondsToSelector:@selector(
                                    URLSession:dataTask:didReceiveChallenge:
                                                       completionHandler:)];
}

/**
 * Swizzles the NSURLSession creation method that is used by the React Native
 * or rn-fetch-blob networking stacks. This allows us to intercept the creation
 * of this and ensure that a special pinning delegate can be used that applies
 * the Approov dynamic pins.
 */
- (void)swizzleRCTSessionCreation {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wshadow"
  __block ApproovRCTInterceptor *interceptor = self;
  RSSwizzleClassMethod(
      NSClassFromString(@"NSURLSession"),
      @selector(sessionWithConfiguration:delegate:delegateQueue:),
      RSSWReturnType(NSURLSession *),
      RSSWArguments(NSURLSessionConfiguration *_Nonnull configuration,
                    id _Nullable delegate, NSOperationQueue *_Nullable queue),
      RSSWReplacement({
        // Check if the delegate should be intercepted.
        // Note: in `mode=all` we also intercept nil delegates so that sessions
        // created without an explicit delegate still receive Approov protection.
        NSURLSession *session;
        NSString *delegateClassName =
            delegate ? NSStringFromClass([delegate class]) : @"<nil>";
        BOOL shouldIntercept = NO;
        BOOL usedCapabilityFallback = NO;
        BOOL delegateSupportsAuthChallenge =
            [interceptor delegateSupportsAuthChallenge:delegate];

        [interceptor->_policyLock lock];
        if (delegate != nil) {
          shouldIntercept = [interceptor->_policy
              shouldInterceptSessionWithDelegate:delegateClassName];
          if (!shouldIntercept &&
              interceptor->_policy.mode == SessionInterceptionModeAllowList &&
              delegateSupportsAuthChallenge &&
              ![interceptor->_policy
                  isExcludedDelegateClassName:delegateClassName]) {
            shouldIntercept = YES;
            usedCapabilityFallback = YES;
          }
        } else {
          shouldIntercept =
              interceptor->_policy.mode == SessionInterceptionModeAll;
        }
        [interceptor->_policyLock unlock];

        if (shouldIntercept) {
          if (usedCapabilityFallback) {
            ApproovLogI(@"intercepting a session creation with %@ delegate "
                        @"(capability fallback: auth challenge selector "
                        @"detected)",
                        delegateClassName);
          } else {
            ApproovLogI(@"intercepting a session creation with %@ delegate",
                        delegateClassName);
          }

          // add mock https protocol for sending status code and error
          if (!configuration.protocolClasses ||
              [configuration.protocolClasses count] == 0) {
            configuration.protocolClasses =
                @[ [ApproovMockURLProtocol class] ];
          } else if (![configuration.protocolClasses
                         containsObject:[ApproovMockURLProtocol class]]) {
            NSMutableArray *protocolClasses =
                [configuration.protocolClasses mutableCopy];
            [protocolClasses insertObject:[ApproovMockURLProtocol class]
                                  atIndex:0];
            configuration.protocolClasses = protocolClasses;
          }

          // call the original method but provide the pinning delegate instead
          // of the one provided
          PinningURLSessionDelegate *pinningDelegate =
              [PinningURLSessionDelegate
                  createWithDelegate:delegate
                      approovService:interceptor.approovService];

          // Create session FIRST so we can capture it in the callback
          session = RSSWCallOriginal(configuration, pinningDelegate, queue);
          __weak NSURLSession *weakSession = session;

          // Register auth challenge callback
          // IMPORTANT: weakSession must be assigned BEFORE this block is
          // created
          pinningDelegate.authChallengeCallback =
              ^(NSString *host, ApproovTrustDecision decision) {
                dispatch_async(interceptor->_sessionRegistryQueue, ^{
                  SessionMetadata *metadata =
                      [interceptor->_pinnedSessions objectForKey:weakSession];
                  if (metadata) {
                    metadata.authChallengeCount++;
                    metadata.lastAuthChallengeAt = [NSDate date];
                    metadata.pinningDelegateVerified = YES;

                    if (decision == ApproovTrustDecisionAllow) {
                      metadata.pinnedChallengeCount++;
                    } else {
                      metadata.blockedChallengeCount++;
                    }

                    ApproovLogI(@"Session %p: auth challenge for %@ "
                                @"(decision: %d, total: %lu)",
                                weakSession, host, decision,
                                (unsigned long)metadata.authChallengeCount);
                  } else {
                    ApproovLogW(@"Auth challenge callback: session %p not "
                                @"found in registry",
                                weakSession);
                  }
                });
              };

          // Store in registry with metadata
          SessionMetadata *metadata = [[SessionMetadata alloc] init];
          metadata.delegateClassName = delegateClassName;
          metadata.createdAt = [NSDate date];
          metadata.requestCount = 0;
          metadata.authChallengeCount = 0;
          metadata.pinnedChallengeCount = 0;
          metadata.blockedChallengeCount = 0;
          metadata.pinningDelegateVerified = NO;

          dispatch_sync(interceptor->_sessionRegistryQueue, ^{
            [interceptor->_pinnedSessions setObject:metadata forKey:session];
            ApproovLogI(@"Registered session %p (total: %lu)", session,
                        (unsigned long)interceptor->_pinnedSessions.count);
          });

          // provide the created session
          return session;
        } else {
          // Delegate was rejected by policy - log this for diagnostics
          ApproovLogW(@"SKIPPING session creation with %@ delegate (not in "
                      @"interception policy)",
                      delegateClassName);
        }

        // if we don't want to intercept the session then we just call the
        // original method unmodified
        return RSSWCallOriginal(configuration, delegate, queue);
      }));
#pragma clang diagnostic pop
}

/**
 * Swizzles the NSURLSessionDataTask creation method that is used by the React
 * Native or rn-fetch-blob networking stacks. This allows us to intercept the
 * creation of the networking requests and thus to include Approov tokens in the
 * request, or substitute headers or query parameters. We only do this for
 * requests using the known pinned session.
 */
- (NSURLSessionDataTask *)
    interceptDataTaskCreationForSession:(NSURLSession *)session
                                request:(NSURLRequest *)request
                           originalCall:(NSURLSessionDataTask * (^)(NSURLRequest
                                                                        *request))
                                            originalCall {
  // Thread-safe session lookup
  __block SessionMetadata *metadata = nil;
  dispatch_sync(_sessionRegistryQueue, ^{
    metadata = [_pinnedSessions objectForKey:session];
  });

  if (metadata != nil) {
    // update the request to include Approov dealing with any failures -
    // note that this part may block for the duration of the time it takes
    // to fetch an Approov token but experiments indicate that this does
    // not impact the behaviour of the React Native Javascript execution
    ApproovLogI(@"intercepting data task %@ %@ for session %p (delegate: "
                @"%@, requests: %lu)",
                request.HTTPMethod, request.URL, session,
                metadata.delegateClassName, (unsigned long)metadata.requestCount);

    // Thread-safe counter increment
    dispatch_async(_sessionRegistryQueue, ^{
      metadata.requestCount++;
    });

    ApproovInterceptorResult *result = [_approovService interceptRequest:request];
    switch ([result action]) {
    case ApproovInterceptorActionProceed: {
      // proceed with the updated request
      return originalCall(result.request);
    }
    case ApproovInterceptorActionRetry: {
      // return a task with 5xx error code suggesting retry
      return [ApproovMockURLProtocol createMockTaskForSession:session
                                                withStatusCode:503
                                                   withMessage:[result message]];
    }
    default: {
      // return a task which fails indicating a more permanent issue
      return [ApproovMockURLProtocol createMockTaskForSession:session
                                                 withErrorCode:499
                                                   withMessage:[result message]];
    }
    }
  }

  // if the data task creation is for a different (unpinned) session
  // then we don't add Approov
  ApproovLogI(@"skipping request for unregistered session %p", session);
  return originalCall(request);
}

- (void)swizzleRCTSessionDataTasks {
  __block ApproovRCTInterceptor *interceptor = self;

  // Primary request-based creator.
  RSSwizzleInstanceMethod(
      NSClassFromString(@"NSURLSession"), @selector(dataTaskWithRequest:),
      RSSWReturnType(NSURLSessionDataTask *),
      RSSWArguments(NSURLRequest *_Nonnull request), RSSWReplacement({
        return [interceptor
            interceptDataTaskCreationForSession:self
                                       request:request
                                  originalCall:^NSURLSessionDataTask *(
                                                   NSURLRequest
                                                       *updatedRequest) {
                                    return RSSWCallOriginal(updatedRequest);
                                  }];
      }),
      0, NULL);

  // Completion-handler request creator used by many SDKs/framework wrappers.
  RSSwizzleInstanceMethod(
      NSClassFromString(@"NSURLSession"),
      @selector(dataTaskWithRequest:completionHandler:),
      RSSWReturnType(NSURLSessionDataTask *),
      RSSWArguments(NSURLRequest *_Nonnull request,
                    void (^_Nullable completionHandler)(
                        NSData *_Nullable data, NSURLResponse *_Nullable response,
                        NSError *_Nullable error)),
      RSSWReplacement({
        return [interceptor
            interceptDataTaskCreationForSession:self
                                       request:request
                                  originalCall:^NSURLSessionDataTask *(
                                                   NSURLRequest
                                                       *updatedRequest) {
                                    return RSSWCallOriginal(updatedRequest,
                                                            completionHandler);
                                  }];
      }),
      0, NULL);

  // URL-only creators are redirected to the request-based creators so they
  // share exactly the same interception path.
  RSSwizzleInstanceMethod(
      NSClassFromString(@"NSURLSession"), @selector(dataTaskWithURL:),
      RSSWReturnType(NSURLSessionDataTask *),
      RSSWArguments(NSURL *_Nonnull url), RSSWReplacement({
        if (url == nil) {
          return RSSWCallOriginal(url);
        }
        NSURLRequest *request = [NSURLRequest requestWithURL:url];
        return [self dataTaskWithRequest:request];
      }),
      0, NULL);

  RSSwizzleInstanceMethod(
      NSClassFromString(@"NSURLSession"),
      @selector(dataTaskWithURL:completionHandler:),
      RSSWReturnType(NSURLSessionDataTask *),
      RSSWArguments(NSURL *_Nonnull url,
                    void (^_Nullable completionHandler)(
                        NSData *_Nullable data, NSURLResponse *_Nullable response,
                        NSError *_Nullable error)),
      RSSWReplacement({
        if (url == nil) {
          return RSSWCallOriginal(url, completionHandler);
        }
        NSURLRequest *request = [NSURLRequest requestWithURL:url];
        return [self dataTaskWithRequest:request
                       completionHandler:completionHandler];
      }),
      0, NULL);
}

/**
 * Swizzles the NSURLSession invalidation methods to clean up the session
 * registry when sessions are invalidated. This prevents memory leaks and stale
 * session tracking.
 */
- (void)swizzleSessionInvalidation {
  __block ApproovRCTInterceptor *interceptor = self;

  // Swizzle both invalidation methods
  NSArray *invalidationSelectors =
      @[ @"invalidateAndCancel", @"finishTasksAndInvalidate" ];

  for (NSString *selectorName in invalidationSelectors) {
    RSSwizzleInstanceMethod(
        NSClassFromString(@"NSURLSession"), NSSelectorFromString(selectorName),
        RSSWReturnType(void), RSSWArguments(), RSSWReplacement({
          // Thread-safe cleanup
          dispatch_sync(interceptor->_sessionRegistryQueue, ^{
            SessionMetadata *metadata =
                [interceptor->_pinnedSessions objectForKey:self];
            if (metadata) {
              ApproovLogD(@"Removing invalidated session %p (served %lu "
                          @"requests, %lu auth challenges)",
                          self, (unsigned long)metadata.requestCount,
                          (unsigned long)metadata.authChallengeCount);
              [interceptor->_pinnedSessions removeObjectForKey:self];
            }
          });
          RSSWCallOriginal();
        }),
        0, NULL);
  }
}

// MARK: - Configuration API Implementations

+ (void)setInterceptionMode:(NSInteger)mode {
  if (_sharedInterceptor) {
    SessionInterceptionMode normalizedMode = SessionInterceptionModeAllowList;
    if (mode == SessionInterceptionModeDenyList) {
      normalizedMode = SessionInterceptionModeDenyList;
    } else if (mode == SessionInterceptionModeAll) {
      normalizedMode = SessionInterceptionModeAll;
    } else if (mode != SessionInterceptionModeAllowList) {
      ApproovLogW(@"Invalid interception mode %ld, defaulting to AllowList",
                  (long)mode);
    }

    [_sharedInterceptor->_policyLock lock];
    _sharedInterceptor->_policy.mode = normalizedMode;
    [_sharedInterceptor->_policyLock unlock];
    ApproovLogI(@"Interception mode set to %ld", (long)normalizedMode);
  }
}

+ (void)addAllowedDelegate:(NSString *)delegatePattern {
  if (_sharedInterceptor) {
    [_sharedInterceptor->_policyLock lock];
    NSMutableSet *allowed =
        [_sharedInterceptor->_policy.allowedDelegates mutableCopy];
    [allowed addObject:delegatePattern];
    _sharedInterceptor->_policy.allowedDelegates = [allowed copy];
    [_sharedInterceptor->_policyLock unlock];
    ApproovLogI(@"Added allowed delegate pattern: %@", delegatePattern);
  }
}

+ (void)addExcludedDelegate:(NSString *)delegatePattern {
  if (_sharedInterceptor) {
    [_sharedInterceptor->_policyLock lock];
    NSMutableSet *excluded =
        [_sharedInterceptor->_policy.excludedDelegates mutableCopy];
    [excluded addObject:delegatePattern];
    _sharedInterceptor->_policy.excludedDelegates = [excluded copy];
    [_sharedInterceptor->_policyLock unlock];
    ApproovLogI(@"Added excluded delegate pattern: %@", delegatePattern);
  }
}

+ (void)removeAllowedDelegate:(NSString *)delegatePattern {
  if (_sharedInterceptor) {
    [_sharedInterceptor->_policyLock lock];
    NSMutableSet *allowed =
        [_sharedInterceptor->_policy.allowedDelegates mutableCopy];
    [allowed removeObject:delegatePattern];
    _sharedInterceptor->_policy.allowedDelegates = [allowed copy];
    [_sharedInterceptor->_policyLock unlock];
    ApproovLogI(@"Removed allowed delegate pattern: %@", delegatePattern);
  }
}

+ (void)removeExcludedDelegate:(NSString *)delegatePattern {
  if (_sharedInterceptor) {
    [_sharedInterceptor->_policyLock lock];
    NSMutableSet *excluded =
        [_sharedInterceptor->_policy.excludedDelegates mutableCopy];
    [excluded removeObject:delegatePattern];
    _sharedInterceptor->_policy.excludedDelegates = [excluded copy];
    [_sharedInterceptor->_policyLock unlock];
    ApproovLogI(@"Removed excluded delegate pattern: %@", delegatePattern);
  }
}

// MARK: - Diagnostic API Implementations

+ (NSDictionary *)getSessionDiagnostics {
  if (!_sharedInterceptor) {
    return @{@"error" : @"Interceptor not initialized"};
  }

  __block NSMutableArray *sessions = [NSMutableArray array];
  __block NSUInteger totalRequests = 0;

  dispatch_sync(_sharedInterceptor->_sessionRegistryQueue, ^{
    NSEnumerator *sessionEnum =
        [_sharedInterceptor->_pinnedSessions keyEnumerator];
    NSURLSession *session;
    while ((session = [sessionEnum nextObject])) {
      SessionMetadata *metadata =
          [_sharedInterceptor->_pinnedSessions objectForKey:session];
      if (metadata) {
        totalRequests += metadata.requestCount;
        [sessions addObject:@{
          @"sessionPointer" : [NSString stringWithFormat:@"%p", session],
          @"delegateClassName" : metadata.delegateClassName,
          @"createdAt" : [NSString stringWithFormat:@"%@", metadata.createdAt],
          @"requestCount" : @(metadata.requestCount),
          @"authChallengeCount" : @(metadata.authChallengeCount),
          @"pinnedChallengeCount" : @(metadata.pinnedChallengeCount),
          @"blockedChallengeCount" : @(metadata.blockedChallengeCount),
          @"pinningDelegateVerified" : @(metadata.pinningDelegateVerified)
        }];
      }
    }
  });

  return @{
    @"totalSessions" : @(sessions.count),
    @"totalRequests" : @(totalRequests),
    @"sessions" : sessions
  };
}

+ (NSDictionary *)getPinningDiagnostics {
  if (!_sharedInterceptor) {
    return @{@"error" : @"Interceptor not initialized"};
  }

  __block NSUInteger totalAuthChallenges = 0;
  __block NSUInteger totalPinned = 0;
  __block NSUInteger totalBlocked = 0;
  __block NSUInteger sessionsWithPinning = 0;
  __block NSUInteger sessionsWithoutPinning = 0;
  __block NSMutableArray *unpinnedSessions = [NSMutableArray array];

  dispatch_sync(_sharedInterceptor->_sessionRegistryQueue, ^{
    NSEnumerator *sessionEnum =
        [_sharedInterceptor->_pinnedSessions keyEnumerator];
    NSURLSession *session;
    while ((session = [sessionEnum nextObject])) {
      SessionMetadata *metadata =
          [_sharedInterceptor->_pinnedSessions objectForKey:session];
      if (metadata) {
        totalAuthChallenges += metadata.authChallengeCount;
        totalPinned += metadata.pinnedChallengeCount;
        totalBlocked += metadata.blockedChallengeCount;

        if (metadata.requestCount > 0 && !metadata.pinningDelegateVerified) {
          sessionsWithoutPinning++;
          [unpinnedSessions addObject:@{
            @"sessionPointer" : [NSString stringWithFormat:@"%p", session],
            @"delegateClassName" : metadata.delegateClassName,
            @"requestCount" : @(metadata.requestCount)
          }];
        } else if (metadata.pinningDelegateVerified) {
          sessionsWithPinning++;
        }
      }
    }
  });

  return @{
    @"totalAuthChallenges" : @(totalAuthChallenges),
    @"totalPinned" : @(totalPinned),
    @"totalBlocked" : @(totalBlocked),
    @"sessionsWithPinning" : @(sessionsWithPinning),
    @"sessionsWithoutPinning" : @(sessionsWithoutPinning),
    @"unpinnedSessions" : unpinnedSessions
  };
}

+ (void)validatePinningIsActive {
  NSDictionary *pinningDiag = [self getPinningDiagnostics];
  NSNumber *sessionsWithoutPinning = pinningDiag[@"sessionsWithoutPinning"];
  NSArray *unpinnedSessions = pinningDiag[@"unpinnedSessions"];

  if ([sessionsWithoutPinning integerValue] > 0) {
    ApproovLogW(@"WARNING: %@ session(s) have made requests but pinning was "
                @"NOT verified!",
                sessionsWithoutPinning);
    for (NSDictionary *session in unpinnedSessions) {
      ApproovLogW(@"  - Session %@ (delegate: %@, requests: %@)",
                  session[@"sessionPointer"], session[@"delegateClassName"],
                  session[@"requestCount"]);
    }
    ApproovLogW(@"This may indicate that pinning is being bypassed. "
                @"Investigate immediately!");
  } else {
    ApproovLogI(
        @"Pinning validation: All active sessions have verified pinning ✓");
  }
}

@end
