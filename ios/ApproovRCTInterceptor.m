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
#import <React/RCTBridgeModule.h>

// Import the auto-generated Swift header for the bridge module
#if __has_include(                                                             \
    <approov_service_react_native/approov_service_react_native-Swift.h>)
#import <approov_service_react_native/approov_service_react_native-Swift.h>
#elif __has_include("approov_service_react_native-Swift.h")
#import "approov_service_react_native-Swift.h"
#else
// Fallback if the module name is different
#import <approov_service_react_native_Swift.h>
#endif
#import "ApproovService.h"
#include <dlfcn.h>
#import <objc/runtime.h>

// Thread-local key used to prevent double interception when a recovery
// re-swizzle chains through to the original swizzle block.
static NSString *const kApproovRecoveryActiveKey = @"ApproovRecoveryActive";

static BOOL ApproovIsMockURL(NSURL *url) {
  NSString *scheme = url.scheme;
  return scheme != nil && [scheme isEqualToString:@"mockhttps"];
}

static BOOL ApproovIsMockRequest(NSURLRequest *request) {
  return ApproovIsMockURL(request.URL);
}

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
@property(nonatomic, strong) NSString *delegateImagePath;
@property(nonatomic, strong) NSString *delegateBundleIdentifier;
@property(nonatomic, strong) NSDate *createdAt;
@property(nonatomic, assign) NSUInteger requestCount;
@property(nonatomic, assign) BOOL registeredForPinning;
@property(nonatomic, strong) NSString *creationDisposition;
@property(nonatomic, strong) NSDate *lastObservedAt;
@property(nonatomic, strong) NSString *lastObservedTaskType;
@property(nonatomic, strong) NSString *lastObservedRequestURL;
@property(nonatomic, strong) NSString *lastObservedRequestMethod;

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
      @"GDTCCTUploadOperation", // Firebase transport upload delegate
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

// Global configuration variable for max reswizzle attempts (default is 0,
// meaning runtime IMP recovery is disabled unless explicitly enabled).
static NSUInteger gMaxReswizzleAttempts = 0;

// Extended session metadata powers verbose diagnostics for skipped/unregistered
// sessions. It is enabled by default for development, but can be disabled from
// React when apps ship to production and no longer need the extra bookkeeping.
static BOOL gSessionMetadataCollectionEnabled = YES;

// The extended diagnostics ledger is for development only. Cap its retained
// metadata to approximately 1 MB so a forgotten production toggle cannot grow
// without bound.
static const NSUInteger kApproovSessionMetadataBudgetBytes = 1024 * 1024;

// MARK: - ApproovRCTInterceptor Implementation

@implementation ApproovRCTInterceptor {
  // Track all pinned sessions with metadata
  NSMapTable<NSURLSession *, SessionMetadata *> *_pinnedSessions;

  // Track all observed sessions, including those not registered for pinning.
  NSMapTable<NSURLSession *, SessionMetadata *> *_observedSessions;

  // Configuration for which sessions to intercept
  SessionInterceptionPolicy *_policy;

  // Thread safety
  dispatch_queue_t _sessionRegistryQueue;
  NSLock *_policyLock;

  // IMP integrity tracking: maps "ClassName.selectorName" -> recorded IMP
  NSMutableDictionary<NSString *, NSValue *> *_installedIMPs;
  NSLock *_impTrackingLock;

  // Re-swizzle retry counts: maps "ClassName.selectorName" -> attempt count
  NSMutableDictionary<NSString *, NSNumber *> *_reswizzleAttempts;
}

// the single shared intercetor for React Native
static ApproovRCTInterceptor *_sharedInterceptor = nil;

// ensure the singleton is only created once
static dispatch_once_t _onceToken = 0;

static NSString *ApproovPassiveProbeImageForIMP(IMP imp) {
  if (imp == NULL) {
    return @"<nil>";
  }

  Dl_info info;
  if (dladdr((const void *)imp, &info) == 0) {
    return @"<unknown>";
  }

  if (info.dli_fname != NULL) {
    return [NSString stringWithUTF8String:info.dli_fname];
  }

  return @"<unknown>";
}

static NSString *ApproovPassiveProbeSymbolForIMP(IMP imp) {
  if (imp == NULL) {
    return @"<nil>";
  }

  Dl_info info;
  if (dladdr((const void *)imp, &info) == 0) {
    return @"<unknown>";
  }

  if (info.dli_sname != NULL) {
    return [NSString stringWithUTF8String:info.dli_sname];
  }

  return @"<unknown>";
}

static NSString *ApproovImagePathForClass(Class targetClass) {
  if (targetClass == Nil) {
    return nil;
  }

  const char *imageName = class_getImageName(targetClass);
  if (imageName == NULL) {
    return nil;
  }

  return [NSString stringWithUTF8String:imageName];
}

static NSString *ApproovBundleIdentifierForClass(Class targetClass) {
  if (targetClass == Nil) {
    return nil;
  }

  NSBundle *bundle = [NSBundle bundleForClass:targetClass];
  return bundle.bundleIdentifier;
}

static void ApproovLogPassiveMethodProbe(Class targetClass, SEL selector,
                                         BOOL classMethod,
                                         NSString *label) {
  if (targetClass == Nil) {
    ApproovLogI(@"+load passive probe [%@] class missing", label);
    return;
  }

  Method method = classMethod ? class_getClassMethod(targetClass, selector)
                              : class_getInstanceMethod(targetClass, selector);
  IMP imp = method != NULL ? method_getImplementation(method) : NULL;
  ApproovLogI(@"+load passive probe [%@] class=%@ selector=%@ "
              @"kind=%@ imp=%p image=%@ symbol=%@",
              label, NSStringFromClass(targetClass),
              NSStringFromSelector(selector),
              classMethod ? @"class" : @"instance", imp,
              ApproovPassiveProbeImageForIMP(imp),
              ApproovPassiveProbeSymbolForIMP(imp));
}

+ (void)setMaxReswizzleAttempts:(NSInteger)attempts {
  if (attempts >= 0) {
    gMaxReswizzleAttempts = (NSUInteger)attempts;
  }
}

+ (NSInteger)maxReswizzleAttempts {
  return (NSInteger)gMaxReswizzleAttempts;
}

+ (void)setSessionMetadataCollectionEnabled:(BOOL)enabled {
  gSessionMetadataCollectionEnabled = enabled;

  ApproovRCTInterceptor *interceptor = _sharedInterceptor;
  if (interceptor == nil) {
    return;
  }

  dispatch_sync(interceptor->_sessionRegistryQueue, ^{
    NSEnumerator *sessionEnum = [interceptor->_pinnedSessions keyEnumerator];
    NSURLSession *session = nil;
    while ((session = [sessionEnum nextObject])) {
      SessionMetadata *metadata =
          [interceptor->_pinnedSessions objectForKey:session];
      if (metadata != nil && !enabled) {
        metadata.delegateImagePath = nil;
        metadata.delegateBundleIdentifier = nil;
        metadata.creationDisposition = nil;
        metadata.lastObservedAt = nil;
        metadata.lastObservedTaskType = nil;
        metadata.lastObservedRequestURL = nil;
        metadata.lastObservedRequestMethod = nil;
      }
    }

    [interceptor->_observedSessions removeAllObjects];
  });
}

+ (BOOL)sessionMetadataCollectionEnabled {
  return gSessionMetadataCollectionEnabled;
}

static NSUInteger ApproovApproximateStringBytes(NSString *value) {
  if (value == nil) {
    return 0;
  }

  return 32 + [value lengthOfBytesUsingEncoding:NSUTF8StringEncoding];
}

- (NSUInteger)approximateObservedSessionMetadataBytes:
    (SessionMetadata *)metadata {
  if (metadata == nil) {
    return 0;
  }

  NSUInteger totalBytes = 256;
  totalBytes += ApproovApproximateStringBytes(metadata.delegateClassName);
  totalBytes += ApproovApproximateStringBytes(metadata.delegateImagePath);
  totalBytes +=
      ApproovApproximateStringBytes(metadata.delegateBundleIdentifier);
  totalBytes += ApproovApproximateStringBytes(metadata.creationDisposition);
  totalBytes += ApproovApproximateStringBytes(metadata.lastObservedTaskType);
  totalBytes += ApproovApproximateStringBytes(metadata.lastObservedRequestURL);
  totalBytes +=
      ApproovApproximateStringBytes(metadata.lastObservedRequestMethod);

  if (metadata.createdAt != nil) {
    totalBytes += 32;
  }
  if (metadata.lastObservedAt != nil) {
    totalBytes += 32;
  }
  if (metadata.lastAuthChallengeAt != nil) {
    totalBytes += 32;
  }

  totalBytes += sizeof(NSUInteger) * 4;
  totalBytes += sizeof(BOOL) * 2;
  return totalBytes;
}

- (void)trimObservedSessionMetadataToBudgetLocked {
  NSUInteger totalBytes = 0;
  NSMutableArray<NSDictionary *> *entries = [NSMutableArray array];

  NSEnumerator *sessionEnum = [_observedSessions keyEnumerator];
  NSURLSession *session = nil;
  while ((session = [sessionEnum nextObject])) {
    SessionMetadata *metadata = [_observedSessions objectForKey:session];
    if (metadata == nil) {
      continue;
    }

    NSUInteger entryBytes =
        [self approximateObservedSessionMetadataBytes:metadata];
    totalBytes += entryBytes;

    NSDate *sortDate = metadata.lastObservedAt ?: metadata.createdAt;
    if (sortDate == nil) {
      sortDate = [NSDate distantPast];
    }

    [entries addObject:@{
      @"session" : session,
      @"bytes" : @(entryBytes),
      @"registered" : @(metadata.registeredForPinning),
      @"sortDate" : sortDate
    }];
  }

  if (totalBytes <= kApproovSessionMetadataBudgetBytes) {
    return;
  }

  [entries sortUsingComparator:^NSComparisonResult(NSDictionary *left,
                                                   NSDictionary *right) {
    BOOL leftRegistered = [left[@"registered"] boolValue];
    BOOL rightRegistered = [right[@"registered"] boolValue];
    if (leftRegistered != rightRegistered) {
      return leftRegistered ? NSOrderedDescending : NSOrderedAscending;
    }

    return [left[@"sortDate"] compare:right[@"sortDate"]];
  }];

  NSUInteger evictedCount = 0;
  NSUInteger evictedBytes = 0;
  for (NSDictionary *entry in entries) {
    if (totalBytes <= kApproovSessionMetadataBudgetBytes) {
      break;
    }

    NSURLSession *entrySession = entry[@"session"];
    NSUInteger entryBytes = [entry[@"bytes"] unsignedIntegerValue];
    [_observedSessions removeObjectForKey:entrySession];

    totalBytes = (entryBytes >= totalBytes) ? 0 : (totalBytes - entryBytes);
    evictedCount++;
    evictedBytes += entryBytes;
  }

  ApproovLogW(@"Session metadata budget exceeded (%lu > %lu bytes); evicted "
              @"%lu diagnostic session entr%@ to stay within the ~1 MB cap. "
              @"Disable session metadata collection in production with "
              @"setSessionMetadataCollectionEnabled(false).",
              (unsigned long)(totalBytes + evictedBytes),
              (unsigned long)kApproovSessionMetadataBudgetBytes,
              (unsigned long)evictedCount, evictedCount == 1 ? @"y" : @"ies");
}

/**
 * Creates a ReactNative interceptor.
 *
 * @param approovService the ApproovService used to update requests
 */
+ (instancetype)startWithApproovService:(ApproovService *)approovService {
  dispatch_once(&_onceToken, ^{
    _sharedInterceptor = [[self alloc] initWithApproovService:approovService];
  });
  if (_sharedInterceptor.approovService == nil) {
    _sharedInterceptor->_approovService = approovService;
  }
  return _sharedInterceptor;
}

/**
 * Passive startup probe only.
 * This intentionally does not create the interceptor or install swizzles.
 * It provides early diagnostics without changing runtime behavior.
 */
+ (void)load {
  Class nsurlSessionClass = NSClassFromString(@"NSURLSession");
  Class localSessionClass = NSClassFromString(@"__NSURLSessionLocal");
  Class cfsessionClass = NSClassFromString(@"__NSCFURLSession");

  ApproovLogI(@"+load passive probe start");
  ApproovLogI(@"+load passive probe class NSURLSession=%@",
              nsurlSessionClass != Nil ? @"present" : @"missing");
  ApproovLogI(@"+load passive probe class __NSURLSessionLocal=%@",
              localSessionClass != Nil ? @"present" : @"missing");
  ApproovLogI(@"+load passive probe class __NSCFURLSession=%@",
              cfsessionClass != Nil ? @"present" : @"missing");

  ApproovLogPassiveMethodProbe(
      nsurlSessionClass,
      @selector(sessionWithConfiguration:delegate:delegateQueue:), YES,
      @"NSURLSession.sessionWithConfiguration:delegate:delegateQueue:");
  ApproovLogPassiveMethodProbe(nsurlSessionClass,
                               @selector(dataTaskWithRequest:), NO,
                               @"NSURLSession.dataTaskWithRequest:");
  ApproovLogPassiveMethodProbe(
      nsurlSessionClass, @selector(dataTaskWithRequest:completionHandler:), NO,
      @"NSURLSession.dataTaskWithRequest:completionHandler:");
  ApproovLogPassiveMethodProbe(nsurlSessionClass,
                               @selector(uploadTaskWithRequest:fromData:), NO,
                               @"NSURLSession.uploadTaskWithRequest:fromData:");
  ApproovLogPassiveMethodProbe(
      nsurlSessionClass,
      @selector(uploadTaskWithRequest:fromData:completionHandler:), NO,
      @"NSURLSession.uploadTaskWithRequest:fromData:completionHandler:");
  ApproovLogPassiveMethodProbe(
      localSessionClass, @selector(dataTaskWithRequest:), NO,
      @"__NSURLSessionLocal.dataTaskWithRequest:");
  ApproovLogPassiveMethodProbe(
      localSessionClass, @selector(dataTaskWithRequest:completionHandler:), NO,
      @"__NSURLSessionLocal.dataTaskWithRequest:completionHandler:");
  ApproovLogPassiveMethodProbe(
      localSessionClass, @selector(uploadTaskWithRequest:fromData:), NO,
      @"__NSURLSessionLocal.uploadTaskWithRequest:fromData:");
  ApproovLogPassiveMethodProbe(
      localSessionClass,
      @selector(uploadTaskWithRequest:fromData:completionHandler:), NO,
      @"__NSURLSessionLocal.uploadTaskWithRequest:fromData:completionHandler:");
  ApproovLogI(@"+load passive probe end");
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
  _observedSessions = [NSMapTable weakToStrongObjectsMapTable];

  // Serial queue for session registry operations
  _sessionRegistryQueue = dispatch_queue_create("io.approov.sessionRegistry",
                                                DISPATCH_QUEUE_SERIAL);

  // Lock for policy updates
  _policyLock = [[NSLock alloc] init];

  // IMP integrity tracking
  _installedIMPs = [[NSMutableDictionary alloc] init];
  _impTrackingLock = [[NSLock alloc] init];
  _reswizzleAttempts = [[NSMutableDictionary alloc] init];

  // Initialize policy
  _policy = [[SessionInterceptionPolicy alloc] init];

  // swizzle react native session creation methods
  [self swizzleRCTSessionCreation];

  // swizzle react native session data task creation methods
  [self swizzleRCTSessionDataTasks];

  // swizzle upload task creation methods used by Firebase transport and others
  [self swizzleRCTSessionUploadTasks];

  // Swizzle session invalidation for cleanup
  [self swizzleSessionInvalidation];

  return self;
}

- (NSString *)headerStateForValue:(NSString *)value {
  if (value == nil) {
    return @"missing";
  }
  if (value.length == 0) {
    return @"empty";
  }
  return [NSString
      stringWithFormat:@"present(len=%lu)", (unsigned long)value.length];
}

/**
 * Diagnostic helper for swizzle verification.
 *
 * For a concrete session instance, this compares the selector implementation
 * pointer on the runtime class versus NSURLSession. If they differ, the
 * concrete class overrides the selector and swizzling only NSURLSession is not
 * sufficient to observe task creation.
 */
- (void)logSessionMethodResolution:(NSURLSession *)session
                          selector:(SEL)selector
                             label:(NSString *)label {
  Class sessionClass = [session class];
  Method sessionMethod = class_getInstanceMethod(sessionClass, selector);
  Method baseMethod = class_getInstanceMethod([NSURLSession class], selector);
  IMP sessionImp =
      sessionMethod != NULL ? method_getImplementation(sessionMethod) : NULL;
  IMP baseImp =
      baseMethod != NULL ? method_getImplementation(baseMethod) : NULL;
  BOOL overridesBase =
      (sessionImp != NULL && baseImp != NULL && sessionImp != baseImp);
  ApproovLogI(@"session selector map [%@] class=%@ selector=%@ "
              @"overridesNSURLSession=%@ sessionImp=%p baseImp=%p",
              label, NSStringFromClass(sessionClass),
              NSStringFromSelector(selector), overridesBase ? @"yes" : @"no",
              sessionImp, baseImp);
}

- (void)trackObservedSession:(NSURLSession *)session
           delegateClassName:(NSString *)delegateClassName
           delegateImagePath:(NSString *)delegateImagePath
    delegateBundleIdentifier:(NSString *)delegateBundleIdentifier
                  registered:(BOOL)registered
                 disposition:(NSString *)disposition {
  if (!gSessionMetadataCollectionEnabled) {
    return;
  }

  if (session == nil) {
    return;
  }

  dispatch_sync(_sessionRegistryQueue, ^{
    SessionMetadata *metadata = [_observedSessions objectForKey:session];
    if (metadata == nil) {
      metadata = [[SessionMetadata alloc] init];
      metadata.createdAt = [NSDate date];
      metadata.requestCount = 0;
      metadata.authChallengeCount = 0;
      metadata.pinnedChallengeCount = 0;
      metadata.blockedChallengeCount = 0;
      metadata.pinningDelegateVerified = NO;
      [_observedSessions setObject:metadata forKey:session];
    }

    metadata.delegateClassName = delegateClassName ?: @"<unknown>";
    metadata.delegateImagePath = delegateImagePath;
    metadata.delegateBundleIdentifier = delegateBundleIdentifier;
    metadata.registeredForPinning = registered;
    metadata.creationDisposition = disposition ?: @"unknown";
    [self trimObservedSessionMetadataToBudgetLocked];
  });
}

- (void)trackUnregisteredRequestForSession:(NSURLSession *)session
                                   request:(NSURLRequest *)request
                                  taskType:(NSString *)taskType {
  if (!gSessionMetadataCollectionEnabled) {
    return;
  }

  if (session == nil) {
    return;
  }

  dispatch_sync(_sessionRegistryQueue, ^{
    SessionMetadata *metadata = [_observedSessions objectForKey:session];
    if (metadata == nil) {
      metadata = [[SessionMetadata alloc] init];
      metadata.delegateClassName = @"<unknown>";
      metadata.createdAt = [NSDate date];
      metadata.creationDisposition = @"task-observed-without-session-creation";
      metadata.registeredForPinning = NO;
      metadata.authChallengeCount = 0;
      metadata.pinnedChallengeCount = 0;
      metadata.blockedChallengeCount = 0;
      metadata.pinningDelegateVerified = NO;
      [_observedSessions setObject:metadata forKey:session];
    }

    metadata.requestCount++;
    metadata.lastObservedAt = [NSDate date];
    metadata.lastObservedTaskType = taskType ?: @"<unknown>";
    metadata.lastObservedRequestURL = request.URL.absoluteString ?: @"";

    NSString *method = request.HTTPMethod;
    if (method == nil || method.length == 0) {
      method = request.URL != nil ? @"GET" : @"<unknown>";
    }
    metadata.lastObservedRequestMethod = method;
    [self trimObservedSessionMetadataToBudgetLocked];
  });
}

/**
 * Returns the set of session classes that can own task selectors at runtime.
 *
 * On modern iOS, NSURLSession factory methods often return private subclasses
 * such as __NSURLSessionLocal. Those classes may implement dataTask/uploadTask
 * selectors directly, which bypasses swizzles installed only on NSURLSession.
 * We resolve private classes dynamically so this remains safe across SDK
 * versions where a class may be absent or renamed.
 */
- (NSArray<Class> *)sessionClassesForTaskSwizzling {
  NSMutableArray<Class> *classes = [NSMutableArray array];
  NSArray<NSString *> *candidateClassNames =
      @[ @"NSURLSession", @"__NSURLSessionLocal", @"__NSCFURLSession" ];

  for (NSString *candidateClassName in candidateClassNames) {
    Class sessionClass = NSClassFromString(candidateClassName);
    if (sessionClass != Nil && ![classes containsObject:sessionClass]) {
      [classes addObject:sessionClass];
    }
  }

  return [classes copy];
}

- (ApproovInterceptorResult *)
    prepareInterceptedResultForTaskType:(NSString *)taskType
                                session:(NSURLSession *)session
                               metadata:(SessionMetadata *)metadata
                                request:(NSURLRequest *)request {
  ApproovLogI(@"intercepting %@ %@ %@ for session %p (delegate: %@, requests: "
              @"%lu)",
              taskType, request.HTTPMethod, request.URL, session,
              metadata.delegateClassName, (unsigned long)metadata.requestCount);

  dispatch_async(_sessionRegistryQueue, ^{
    metadata.requestCount++;
  });

  NSString *tokenHeader = [ApproovService sharedTokenHeader];
  NSString *traceIDHeader = [ApproovService sharedTraceIDHeader];
  NSString *tokenBefore = [request valueForHTTPHeaderField:tokenHeader];
  ApproovService *service = self.approovService ?: [ApproovService sharedService];

  if (service == nil) {
    ApproovLogW(@"skipping %@ interception for %@ on session %p because "
                @"ApproovService is not yet available",
                taskType, request.URL, session);
    return [ApproovInterceptorResult createWithRequest:request
                                            withAction:
                                                ApproovInterceptorActionProceed
                                           withMessage:@"ApproovService not "
                                                       @"ready"];
  }

  if (self.approovService == nil) {
    _approovService = service;
  }

  ApproovInterceptorResult *result =
      [service interceptRequest:request];
  NSString *tokenAfterIntercept =
      [result.request valueForHTTPHeaderField:tokenHeader];
  NSString *traceAfterIntercept =
      [result.request valueForHTTPHeaderField:traceIDHeader];

  NSMutableURLRequest *finalRequest = nil;
  if (result.action == ApproovInterceptorActionProceed) {
    finalRequest = [result.request mutableCopy];
    [[ApproovServiceMutatorBridge shared] processRequest:finalRequest
                                             tokenHeader:tokenHeader
                                           traceIDHeader:traceIDHeader];
  }

  NSString *tokenAfterMutator =
      finalRequest ? [finalRequest valueForHTTPHeaderField:tokenHeader]
                   : tokenAfterIntercept;
  NSString *traceAfterMutator =
      finalRequest ? [finalRequest valueForHTTPHeaderField:traceIDHeader]
                   : traceAfterIntercept;

  ApproovLogI(@"task mutation [%@] %@ token=%@->%@->%@ trace=%@->%@ action=%ld "
              @"message=%@",
              taskType, request.URL, [self headerStateForValue:tokenBefore],
              [self headerStateForValue:tokenAfterIntercept],
              [self headerStateForValue:tokenAfterMutator],
              [self headerStateForValue:traceAfterIntercept],
              [self headerStateForValue:traceAfterMutator], (long)result.action,
              result.message);

  if (finalRequest != nil) {
    return [ApproovInterceptorResult createWithRequest:finalRequest
                                            withAction:result.action
                                           withMessage:result.message];
  }

  return result;
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
        NSURLSession *session;
        if (delegate != nil) {
          NSString *delegateClassName = NSStringFromClass([delegate class]);
          NSString *delegateImagePath = ApproovImagePathForClass([delegate class]);
          NSString *delegateBundleIdentifier =
              ApproovBundleIdentifierForClass([delegate class]);
          ApproovLogI(@"checking session creation with %@ delegate",
                      delegateClassName);
          if (delegateImagePath != nil || delegateBundleIdentifier != nil) {
            ApproovLogI(@"delegate %@ loaded from image=%@ bundle=%@",
                        delegateClassName,
                        delegateImagePath ?: @"<unknown>",
                        delegateBundleIdentifier ?: @"<unknown>");
          }
          // Thread-safe policy check
          BOOL shouldIntercept;
          [interceptor->_policyLock lock];
          shouldIntercept = [interceptor->_policy
              shouldInterceptSessionWithDelegate:delegateClassName];
          [interceptor->_policyLock unlock];

          if (shouldIntercept) {
            // we have a delegate associated with React Native that we need to
            // handle (note we don't intercept the RCTMultipartDataTask since
            // this is specifically associated with bundle reload of Javascript
            // and is not associated with the app's own network requests)
            ApproovLogI(@"intercepting a session creation with %@ delegate",
                        delegateClassName);

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

            // Record where task selectors are implemented for this concrete
            // session instance. These logs make it clear why task swizzles
            // must be installed on runtime subclasses as well as NSURLSession.
            ApproovLogI(@"created pinned session %p class=%@ for delegate %@",
                        session, NSStringFromClass([session class]),
                        delegateClassName);
            [interceptor
                logSessionMethodResolution:session
                                  selector:@selector(dataTaskWithRequest:)
                                     label:@"dataTaskWithRequest:"];
            [interceptor
                logSessionMethodResolution:session
                                  selector:@selector(dataTaskWithRequest:
                                                       completionHandler:)
                                     label:@"dataTaskWithRequest:"
                                           @"completionHandler:"];
            [interceptor
                logSessionMethodResolution:session
                                  selector:@selector(uploadTaskWithRequest:
                                                                  fromData:)
                                     label:@"uploadTaskWithRequest:fromData:"];
            [interceptor
                logSessionMethodResolution:session
                                  selector:@selector
                                  (uploadTaskWithRequest:
                                                fromData:completionHandler:)
                                     label:@"uploadTaskWithRequest:fromData:"
                                           @"completionHandler:"];

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
            if (gSessionMetadataCollectionEnabled) {
              metadata.delegateImagePath = delegateImagePath;
              metadata.delegateBundleIdentifier = delegateBundleIdentifier;
              metadata.creationDisposition = @"registered";
            }
            metadata.createdAt = [NSDate date];
            metadata.requestCount = 0;
            metadata.registeredForPinning = YES;
            metadata.authChallengeCount = 0;
            metadata.pinnedChallengeCount = 0;
            metadata.blockedChallengeCount = 0;
            metadata.pinningDelegateVerified = NO;

            dispatch_sync(interceptor->_sessionRegistryQueue, ^{
              [interceptor->_pinnedSessions setObject:metadata forKey:session];
              if (gSessionMetadataCollectionEnabled) {
                [interceptor->_observedSessions setObject:metadata
                                                   forKey:session];
                [interceptor trimObservedSessionMetadataToBudgetLocked];
              }
              ApproovLogI(@"Registered session %p (total: %lu)", session,
                          (unsigned long)interceptor->_pinnedSessions.count);
            });

            // Verify IMP integrity when new sessions are registered
            // (detects if another SDK has overwritten our swizzles)
            [interceptor verifyIMPIntegrity];

            // provide the created session
            return session;
          } else {
            // Delegate was rejected by policy - log this for diagnostics
            session = RSSWCallOriginal(configuration, delegate, queue);
            [interceptor trackObservedSession:session
                            delegateClassName:delegateClassName
                            delegateImagePath:delegateImagePath
                     delegateBundleIdentifier:delegateBundleIdentifier
                                   registered:NO
                                  disposition:@"policy-skipped"];
            ApproovLogW(
                @"SKIPPING session creation %p with %@ delegate (not in "
                @"interception policy, image=%@, bundle=%@)",
                session, delegateClassName,
                delegateImagePath ?: @"<unknown>",
                delegateBundleIdentifier ?: @"<unknown>");
            return session;
          }
        } else {
          // No delegate provided
          NSLog(@"[Approov] WARNING: NSURLSession created with a nil delegate! Call stack: %@", [NSThread callStackSymbols]);
          session = RSSWCallOriginal(configuration, delegate, queue);
          [interceptor trackObservedSession:session
                          delegateClassName:@"<nil delegate>"
                          delegateImagePath:nil
                   delegateBundleIdentifier:nil
                                 registered:NO
                                disposition:@"nil-delegate"];
          ApproovLogD(@"session creation %p with nil delegate", session);
          return session;
        }
      }));
#pragma clang diagnostic pop

  // Record IMP for session creation class method
  [self recordClassMethodIMPForClass:NSClassFromString(@"NSURLSession")
                            selector:@selector
                            (sessionWithConfiguration:delegate:delegateQueue:)];
}

/**
 * Swizzles the NSURLSessionDataTask creation method that is used by the React
 * Native or rn-fetch-blob networking stacks. This allows us to intercept the
 * creation of the networking requests and thus to include Approov tokens in the
 * request, or substitute headers or query parameters. We only do this for
 * requests using the known pinned session.
 */
- (void)swizzleRCTSessionDataTasks {
  __block ApproovRCTInterceptor *interceptor = self;
  // Install identical swizzles on all resolved session classes so request
  // interception works regardless of whether selectors are implemented on
  // NSURLSession itself or on a concrete runtime subclass.
  for (Class sessionClass in [self sessionClassesForTaskSwizzling]) {
    ApproovLogI(@"installing dataTask swizzles on class %@",
                NSStringFromClass(sessionClass));

    RSSwizzleInstanceMethod(
        sessionClass, @selector(dataTaskWithRequest:),
        RSSWReturnType(NSURLSessionDataTask *),
        RSSWArguments(NSURLRequest *_Nonnull request), RSSWReplacement({
          // Guard: skip if recovery block already processed this call
          if ([NSThread.currentThread
                      .threadDictionary[kApproovRecoveryActiveKey] boolValue]) {
            return RSSWCallOriginal(request);
          }
          if (ApproovIsMockRequest(request)) {
            return RSSWCallOriginal(request);
          }
          ApproovLogI(@"observed dataTaskWithRequest: for session %p %@", self,
                      request.URL);
          // Thread-safe session lookup
          __block SessionMetadata *metadata = nil;
          dispatch_sync(interceptor->_sessionRegistryQueue, ^{
            metadata = [interceptor->_pinnedSessions objectForKey:self];
          });

          if (metadata != nil) {
            ApproovInterceptorResult *result = [interceptor
                prepareInterceptedResultForTaskType:@"dataTaskWithRequest:"
                                            session:self
                                           metadata:metadata
                                            request:request];
            switch ([result action]) {
            case ApproovInterceptorActionProceed: {
              return RSSWCallOriginal([result request]);
            }
            case ApproovInterceptorActionRetry: {
              // return a task with a network-style error (matches Android IOException behavior)
              return [ApproovMockURLProtocol
                  createMockTaskForSession:self
                             withErrorCode:503
                               withMessage:[result message]];
            }
            default: {
              // return a task which fails indicating a more permanent issue
              return [ApproovMockURLProtocol
                  createMockTaskForSession:self
                             withErrorCode:499
                               withMessage:[result message]];
            }
            }
          } else {
            // if the data task creation is for a different (unpinned) session
            // then we don't add Approov
            [interceptor trackUnregisteredRequestForSession:self
                                                    request:request
                                                   taskType:@"dataTaskWithRequest:"];
            ApproovLogI(
                @"skipping dataTaskWithRequest for unregistered session "
                @"%p %@ %@",
                self, request.HTTPMethod, request.URL);
            return RSSWCallOriginal(request);
          }
        }),
        0, NULL);

    RSSwizzleInstanceMethod(
        sessionClass, @selector(dataTaskWithRequest:completionHandler:),
        RSSWReturnType(NSURLSessionDataTask *),
        RSSWArguments(NSURLRequest *_Nonnull request,
                      void (^_Nullable completionHandler)(
                          NSData *_Nullable, NSURLResponse *_Nullable,
                          NSError *_Nullable)),
        RSSWReplacement({
          // Guard: skip if recovery block already processed this call
          if ([NSThread.currentThread
                      .threadDictionary[kApproovRecoveryActiveKey] boolValue]) {
            return RSSWCallOriginal(request, completionHandler);
          }
          if (ApproovIsMockRequest(request)) {
            return RSSWCallOriginal(request, completionHandler);
          }
          ApproovLogI(@"observed dataTaskWithRequest:completionHandler: for "
                      @"session %p %@",
                      self, request.URL);
          __block SessionMetadata *metadata = nil;
          dispatch_sync(interceptor->_sessionRegistryQueue, ^{
            metadata = [interceptor->_pinnedSessions objectForKey:self];
          });

          if (metadata != nil) {
            ApproovInterceptorResult *result =
                [interceptor prepareInterceptedResultForTaskType:
                                 @"dataTaskWithRequest:completionHandler:"
                                                         session:self
                                                        metadata:metadata
                                                         request:request];
            switch ([result action]) {
            case ApproovInterceptorActionProceed:
              return RSSWCallOriginal([result request], completionHandler);
            case ApproovInterceptorActionRetry:
              return [ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:503
                               withMessage:[result message]
                         completionHandler:completionHandler];
            default:
              return [ApproovMockURLProtocol
                  createMockTaskForSession:self
                             withErrorCode:499
                               withMessage:[result message]
                         completionHandler:completionHandler];
            }
          } else {
            [interceptor trackUnregisteredRequestForSession:self
                                                    request:request
                                                   taskType:@"dataTaskWithRequest:completionHandler:"];
            ApproovLogI(@"skipping dataTaskWithRequest:completionHandler: for "
                        @"unregistered session %p %@ %@",
                        self, request.HTTPMethod, request.URL);
            return RSSWCallOriginal(request, completionHandler);
          }
        }),
        0, NULL);

    // Swizzle URL-based convenience methods that bypass
    // dataTaskWithRequest:
    RSSwizzleInstanceMethod(
        sessionClass, @selector(dataTaskWithURL:),
        RSSWReturnType(NSURLSessionDataTask *),
        RSSWArguments(NSURL *_Nonnull url), RSSWReplacement({
          // Guard: skip if recovery block already processed this call
          if ([NSThread.currentThread
                      .threadDictionary[kApproovRecoveryActiveKey] boolValue]) {
            return RSSWCallOriginal(url);
          }
          if (ApproovIsMockURL(url)) {
            return RSSWCallOriginal(url);
          }
          ApproovLogI(@"observed dataTaskWithURL: for session %p %@", self,
                      url);
          __block SessionMetadata *metadata = nil;
          dispatch_sync(interceptor->_sessionRegistryQueue, ^{
            metadata = [interceptor->_pinnedSessions objectForKey:self];
          });

          if (metadata != nil) {
            NSURLRequest *request = [NSURLRequest requestWithURL:url];
            ApproovInterceptorResult *result = [interceptor
                prepareInterceptedResultForTaskType:@"dataTaskWithURL:"
                                            session:self
                                           metadata:metadata
                                            request:request];
            switch ([result action]) {
            case ApproovInterceptorActionProceed:
              return RSSWCallOriginal([result.request URL]);
            case ApproovInterceptorActionRetry:
              return [ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:503
                               withMessage:[result message]];
            default:
              return [ApproovMockURLProtocol
                  createMockTaskForSession:self
                             withErrorCode:499
                               withMessage:[result message]];
            }
          } else {
            NSURLRequest *request = [NSURLRequest requestWithURL:url];
            [interceptor trackUnregisteredRequestForSession:self
                                                    request:request
                                                   taskType:@"dataTaskWithURL:"];
            ApproovLogI(@"skipping dataTaskWithURL: for unregistered session "
                        @"%p %@",
                        self, url);
            return RSSWCallOriginal(url);
          }
        }),
        0, NULL);

    RSSwizzleInstanceMethod(
        sessionClass, @selector(dataTaskWithURL:completionHandler:),
        RSSWReturnType(NSURLSessionDataTask *),
        RSSWArguments(NSURL *_Nonnull url,
                      void (^_Nullable completionHandler)(
                          NSData *_Nullable, NSURLResponse *_Nullable,
                          NSError *_Nullable)),
        RSSWReplacement({
          // Guard: skip if recovery block already processed this call
          if ([NSThread.currentThread
                      .threadDictionary[kApproovRecoveryActiveKey] boolValue]) {
            return RSSWCallOriginal(url, completionHandler);
          }
          if (ApproovIsMockURL(url)) {
            return RSSWCallOriginal(url, completionHandler);
          }
          ApproovLogI(@"observed dataTaskWithURL:completionHandler: for "
                      @"session %p %@",
                      self, url);
          __block SessionMetadata *metadata = nil;
          dispatch_sync(interceptor->_sessionRegistryQueue, ^{
            metadata = [interceptor->_pinnedSessions objectForKey:self];
          });

          if (metadata != nil) {
            NSURLRequest *request = [NSURLRequest requestWithURL:url];
            ApproovInterceptorResult *result =
                [interceptor prepareInterceptedResultForTaskType:
                                 @"dataTaskWithURL:completionHandler:"
                                                         session:self
                                                        metadata:metadata
                                                         request:request];
            switch ([result action]) {
            case ApproovInterceptorActionProceed:
              return RSSWCallOriginal([result.request URL], completionHandler);
            case ApproovInterceptorActionRetry:
              return [ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:503
                               withMessage:[result message]
                         completionHandler:completionHandler];
            default:
              return [ApproovMockURLProtocol
                  createMockTaskForSession:self
                             withErrorCode:499
                               withMessage:[result message]
                         completionHandler:completionHandler];
            }
          } else {
            NSURLRequest *request = [NSURLRequest requestWithURL:url];
            [interceptor trackUnregisteredRequestForSession:self
                                                    request:request
                                                   taskType:@"dataTaskWithURL:completionHandler:"];
            ApproovLogI(@"skipping dataTaskWithURL:completionHandler: for "
                        @"unregistered session %p %@",
                        self, url);
            return RSSWCallOriginal(url, completionHandler);
          }
        }),
        0, NULL);

    // Record IMPs after swizzles are installed for integrity checking
    [self recordIMPForClass:sessionClass
                   selector:@selector(dataTaskWithRequest:)];
    [self recordIMPForClass:sessionClass
                   selector:@selector(dataTaskWithRequest:completionHandler:)];
    [self recordIMPForClass:sessionClass selector:@selector(dataTaskWithURL:)];
    [self recordIMPForClass:sessionClass
                   selector:@selector(dataTaskWithURL:completionHandler:)];
  }
}

/**
 * Swizzles NSURLSession upload task creation methods so SDKs that use upload
 * tasks (for example Firebase transport delegates) also pass through request
 * mutation and token/header logic.
 */
- (void)swizzleRCTSessionUploadTasks {
  __block ApproovRCTInterceptor *interceptor = self;
  // Mirror dataTask coverage for upload APIs because SDK delegates may use
  // either family depending on their transport implementation.
  for (Class sessionClass in [self sessionClassesForTaskSwizzling]) {
    ApproovLogI(@"installing uploadTask swizzles on class %@",
                NSStringFromClass(sessionClass));

    RSSwizzleInstanceMethod(
        sessionClass, @selector(uploadTaskWithRequest:fromData:),
        RSSWReturnType(NSURLSessionUploadTask *),
        RSSWArguments(NSURLRequest *_Nonnull request,
                      NSData *_Nullable bodyData),
        RSSWReplacement({
          // Guard: skip if recovery block already processed this call
          if ([NSThread.currentThread
                      .threadDictionary[kApproovRecoveryActiveKey] boolValue]) {
            return RSSWCallOriginal(request, bodyData);
          }
          if (ApproovIsMockRequest(request)) {
            return RSSWCallOriginal(request, bodyData);
          }
          ApproovLogI(
              @"observed uploadTaskWithRequest:fromData: for session %p "
              @"%@",
              self, request.URL);
          __block SessionMetadata *metadata = nil;
          dispatch_sync(interceptor->_sessionRegistryQueue, ^{
            metadata = [interceptor->_pinnedSessions objectForKey:self];
          });

          if (metadata != nil) {
            ApproovInterceptorResult *result =
                [interceptor prepareInterceptedResultForTaskType:
                                 @"uploadTaskWithRequest:fromData:"
                                                         session:self
                                                        metadata:metadata
                                                         request:request];
            switch ([result action]) {
            case ApproovInterceptorActionProceed:
              return RSSWCallOriginal([result request], bodyData);
            case ApproovInterceptorActionRetry:
              return (NSURLSessionUploadTask *)[ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:503
                               withMessage:[result message]];
            default:
              return (NSURLSessionUploadTask *)[ApproovMockURLProtocol
                  createMockTaskForSession:self
                             withErrorCode:499
                               withMessage:[result message]];
            }
          } else {
            [interceptor trackUnregisteredRequestForSession:self
                                                    request:request
                                                   taskType:@"uploadTaskWithRequest:fromData:"];
            ApproovLogI(@"skipping uploadTaskWithRequest:fromData: for "
                        @"unregistered session %p %@ %@",
                        self, request.HTTPMethod, request.URL);
            return RSSWCallOriginal(request, bodyData);
          }
        }),
        0, NULL);

    RSSwizzleInstanceMethod(
        sessionClass,
        @selector(uploadTaskWithRequest:fromData:completionHandler:),
        RSSWReturnType(NSURLSessionUploadTask *),
        RSSWArguments(NSURLRequest *_Nonnull request,
                      NSData *_Nullable bodyData,
                      void (^_Nullable completionHandler)(
                          NSData *_Nullable, NSURLResponse *_Nullable,
                          NSError *_Nullable)),
        RSSWReplacement({
          // Guard: skip if recovery block already processed this call
          if ([NSThread.currentThread
                      .threadDictionary[kApproovRecoveryActiveKey] boolValue]) {
            return RSSWCallOriginal(request, bodyData, completionHandler);
          }
          if (ApproovIsMockRequest(request)) {
            return RSSWCallOriginal(request, bodyData, completionHandler);
          }
          ApproovLogI(
              @"observed uploadTaskWithRequest:fromData:completionHandler: "
              @"for session %p %@",
              self, request.URL);
          __block SessionMetadata *metadata = nil;
          dispatch_sync(interceptor->_sessionRegistryQueue, ^{
            metadata = [interceptor->_pinnedSessions objectForKey:self];
          });

          if (metadata != nil) {
            ApproovInterceptorResult *result = [interceptor
                prepareInterceptedResultForTaskType:
                    @"uploadTaskWithRequest:fromData:completionHandler:"
                                            session:self
                                           metadata:metadata
                                            request:request];
            switch ([result action]) {
            case ApproovInterceptorActionProceed:
              return RSSWCallOriginal([result request], bodyData,
                                      completionHandler);
            case ApproovInterceptorActionRetry:
              return [ApproovMockURLProtocol
                  createMockUploadTaskForSession:self
                            withErrorCode:503
                               withMessage:[result message]
                         completionHandler:completionHandler];
            default:
              return [ApproovMockURLProtocol
                  createMockUploadTaskForSession:self
                             withErrorCode:499
                               withMessage:[result message]
                         completionHandler:completionHandler];
            }
          } else {
            [interceptor trackUnregisteredRequestForSession:self
                                                    request:request
                                                   taskType:@"uploadTaskWithRequest:fromData:completionHandler:"];
            ApproovLogI(
                @"skipping uploadTaskWithRequest:fromData:completionHandler: "
                @"for unregistered session %p %@ %@",
                self, request.HTTPMethod, request.URL);
            return RSSWCallOriginal(request, bodyData, completionHandler);
          }
        }),
        0, NULL);

    RSSwizzleInstanceMethod(
        sessionClass, @selector(uploadTaskWithRequest:fromFile:),
        RSSWReturnType(NSURLSessionUploadTask *),
        RSSWArguments(NSURLRequest *_Nonnull request, NSURL *_Nullable fileURL),
        RSSWReplacement({
          // Guard: skip if recovery block already processed this call
          if ([NSThread.currentThread
                      .threadDictionary[kApproovRecoveryActiveKey] boolValue]) {
            return RSSWCallOriginal(request, fileURL);
          }
          if (ApproovIsMockRequest(request)) {
            return RSSWCallOriginal(request, fileURL);
          }
          ApproovLogI(
              @"observed uploadTaskWithRequest:fromFile: for session %p "
              @"%@",
              self, request.URL);
          __block SessionMetadata *metadata = nil;
          dispatch_sync(interceptor->_sessionRegistryQueue, ^{
            metadata = [interceptor->_pinnedSessions objectForKey:self];
          });

          if (metadata != nil) {
            ApproovInterceptorResult *result =
                [interceptor prepareInterceptedResultForTaskType:
                                 @"uploadTaskWithRequest:fromFile:"
                                                         session:self
                                                        metadata:metadata
                                                         request:request];
            switch ([result action]) {
            case ApproovInterceptorActionProceed:
              return RSSWCallOriginal([result request], fileURL);
            case ApproovInterceptorActionRetry:
              return (NSURLSessionUploadTask *)[ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:503
                               withMessage:[result message]];
            default:
              return (NSURLSessionUploadTask *)[ApproovMockURLProtocol
                  createMockTaskForSession:self
                             withErrorCode:499
                               withMessage:[result message]];
            }
          } else {
            [interceptor trackUnregisteredRequestForSession:self
                                                    request:request
                                                   taskType:@"uploadTaskWithRequest:fromFile:"];
            ApproovLogI(@"skipping uploadTaskWithRequest:fromFile: for "
                        @"unregistered session %p %@ %@",
                        self, request.HTTPMethod, request.URL);
            return RSSWCallOriginal(request, fileURL);
          }
        }),
        0, NULL);

    RSSwizzleInstanceMethod(
        sessionClass,
        @selector(uploadTaskWithRequest:fromFile:completionHandler:),
        RSSWReturnType(NSURLSessionUploadTask *),
        RSSWArguments(NSURLRequest *_Nonnull request, NSURL *_Nullable fileURL,
                      void (^_Nullable completionHandler)(
                          NSData *_Nullable, NSURLResponse *_Nullable,
                          NSError *_Nullable)),
        RSSWReplacement({
          // Guard: skip if recovery block already processed this call
          if ([NSThread.currentThread
                      .threadDictionary[kApproovRecoveryActiveKey] boolValue]) {
            return RSSWCallOriginal(request, fileURL, completionHandler);
          }
          if (ApproovIsMockRequest(request)) {
            return RSSWCallOriginal(request, fileURL, completionHandler);
          }
          ApproovLogI(
              @"observed uploadTaskWithRequest:fromFile:completionHandler: "
              @"for session %p %@",
              self, request.URL);
          __block SessionMetadata *metadata = nil;
          dispatch_sync(interceptor->_sessionRegistryQueue, ^{
            metadata = [interceptor->_pinnedSessions objectForKey:self];
          });

          if (metadata != nil) {
            ApproovInterceptorResult *result = [interceptor
                prepareInterceptedResultForTaskType:
                    @"uploadTaskWithRequest:fromFile:completionHandler:"
                                            session:self
                                           metadata:metadata
                                            request:request];
            switch ([result action]) {
            case ApproovInterceptorActionProceed:
              return RSSWCallOriginal([result request], fileURL,
                                      completionHandler);
            case ApproovInterceptorActionRetry:
              return [ApproovMockURLProtocol
                  createMockUploadTaskForSession:self
                            withErrorCode:503
                               withMessage:[result message]
                         completionHandler:completionHandler];
            default:
              return [ApproovMockURLProtocol
                  createMockUploadTaskForSession:self
                             withErrorCode:499
                               withMessage:[result message]
                         completionHandler:completionHandler];
            }
          } else {
            [interceptor trackUnregisteredRequestForSession:self
                                                    request:request
                                                   taskType:@"uploadTaskWithRequest:fromFile:completionHandler:"];
            ApproovLogI(
                @"skipping uploadTaskWithRequest:fromFile:completionHandler: "
                @"for unregistered session %p %@ %@",
                self, request.HTTPMethod, request.URL);
            return RSSWCallOriginal(request, fileURL, completionHandler);
          }
        }),
        0, NULL);

    RSSwizzleInstanceMethod(
        sessionClass, @selector(uploadTaskWithStreamedRequest:),
        RSSWReturnType(NSURLSessionUploadTask *),
        RSSWArguments(NSURLRequest *_Nonnull request), RSSWReplacement({
          // Guard: skip if recovery block already processed this call
          if ([NSThread.currentThread
                      .threadDictionary[kApproovRecoveryActiveKey] boolValue]) {
            return RSSWCallOriginal(request);
          }
          if (ApproovIsMockRequest(request)) {
            return RSSWCallOriginal(request);
          }
          ApproovLogI(@"observed uploadTaskWithStreamedRequest: for session %p "
                      @"%@",
                      self, request.URL);
          __block SessionMetadata *metadata = nil;
          dispatch_sync(interceptor->_sessionRegistryQueue, ^{
            metadata = [interceptor->_pinnedSessions objectForKey:self];
          });

          if (metadata != nil) {
            ApproovInterceptorResult *result =
                [interceptor prepareInterceptedResultForTaskType:
                                 @"uploadTaskWithStreamedRequest:"
                                                         session:self
                                                        metadata:metadata
                                                         request:request];
            switch ([result action]) {
            case ApproovInterceptorActionProceed:
              return RSSWCallOriginal([result request]);
            case ApproovInterceptorActionRetry:
              return (NSURLSessionUploadTask *)[ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:503
                               withMessage:[result message]];
            default:
              return (NSURLSessionUploadTask *)[ApproovMockURLProtocol
                  createMockTaskForSession:self
                             withErrorCode:499
                               withMessage:[result message]];
            }
          } else {
            [interceptor trackUnregisteredRequestForSession:self
                                                    request:request
                                                   taskType:@"uploadTaskWithStreamedRequest:"];
            ApproovLogI(@"skipping uploadTaskWithStreamedRequest: for "
                        @"unregistered session %p %@ %@",
                        self, request.HTTPMethod, request.URL);
            return RSSWCallOriginal(request);
          }
        }),
        0, NULL);

    // Record IMPs for upload task selectors
    [self recordIMPForClass:sessionClass
                   selector:@selector(uploadTaskWithRequest:fromData:)];
    [self recordIMPForClass:sessionClass
                   selector:@selector
                   (uploadTaskWithRequest:fromData:completionHandler:)];
    [self recordIMPForClass:sessionClass
                   selector:@selector(uploadTaskWithRequest:fromFile:)];
    [self recordIMPForClass:sessionClass
                   selector:@selector
                   (uploadTaskWithRequest:fromFile:completionHandler:)];
    [self recordIMPForClass:sessionClass
                   selector:@selector(uploadTaskWithStreamedRequest:)];
  }
}

// MARK: - IMP Integrity Tracking

/**
 * Records the current IMP for a class/selector pair after our swizzle is
 * installed.  If another SDK later overwrites the IMP we can detect it.
 */
- (void)recordIMPForClass:(Class)cls selector:(SEL)sel {
  Method m = class_getInstanceMethod(cls, sel);
  if (m == NULL)
    return;
  IMP imp = method_getImplementation(m);
  NSString *key = [NSString stringWithFormat:@"%@.%@", NSStringFromClass(cls),
                                             NSStringFromSelector(sel)];
  [_impTrackingLock lock];
  _installedIMPs[key] = [NSValue valueWithPointer:imp];
  [_impTrackingLock unlock];
  ApproovLogD(@"IMP recorded: %@ = %p", key, imp);
}

/**
 * Records the current IMP for a CLASS method after our swizzle is installed.
 * Class methods live on the metaclass, so we use class_getClassMethod.
 */
- (void)recordClassMethodIMPForClass:(Class)cls selector:(SEL)sel {
  Method m = class_getClassMethod(cls, sel);
  if (m == NULL)
    return;
  IMP imp = method_getImplementation(m);
  // Use "+" prefix to distinguish class methods from instance methods
  NSString *key = [NSString stringWithFormat:@"+%@.%@", NSStringFromClass(cls),
                                             NSStringFromSelector(sel)];
  [_impTrackingLock lock];
  _installedIMPs[key] = [NSValue valueWithPointer:imp];
  [_impTrackingLock unlock];
  ApproovLogD(@"IMP recorded (class method): %@ = %p", key, imp);
}

/**
 * Verifies that IMPs have not been overwritten since we installed our swizzles.
 * If a conflict is detected:
 *   1. Uses dladdr() to identify the SDK/framework that replaced our hook
 *   2. Re-swizzles on top to recover control (up to 3 attempts per selector)
 * Returns the count of conflicts found.
 */
- (NSUInteger)verifyIMPIntegrity {
  NSUInteger maxAttempts = [[self class] maxReswizzleAttempts];
  if (maxAttempts == 0) {
    return 0;
  }

  NSUInteger conflicts = 0;
  [_impTrackingLock lock];
  NSDictionary<NSString *, NSValue *> *snapshot = [_installedIMPs copy];
  [_impTrackingLock unlock];

  for (NSString *key in snapshot) {
    // Parse "ClassName.selectorName" or "+ClassName.selectorName"
    BOOL isClassMethod = [key hasPrefix:@"+"];
    NSString *strippedKey = isClassMethod ? [key substringFromIndex:1] : key;
    NSRange dot = [strippedKey rangeOfString:@"."];
    if (dot.location == NSNotFound)
      continue;
    NSString *className = [strippedKey substringToIndex:dot.location];
    NSString *selectorName = [strippedKey substringFromIndex:dot.location + 1];
    Class cls = NSClassFromString(className);
    SEL sel = NSSelectorFromString(selectorName);
    if (cls == Nil)
      continue;
    Method m = isClassMethod ? class_getClassMethod(cls, sel)
                             : class_getInstanceMethod(cls, sel);
    if (m == NULL)
      continue;
    IMP currentIMP = method_getImplementation(m);
    IMP recordedIMP = [snapshot[key] pointerValue];
    if (currentIMP != recordedIMP) {
      conflicts++;

      // Identify the conflicting SDK using dladdr()
      Dl_info info;
      NSString *conflictingLib = @"unknown";
      NSString *conflictingSymbol = @"unknown";
      if (dladdr((void *)currentIMP, &info)) {
        if (info.dli_fname) {
          conflictingLib = [@(info.dli_fname) lastPathComponent];
        }
        if (info.dli_sname) {
          conflictingSymbol = @(info.dli_sname);
        }
      }

      ApproovLogE(@"IMP CONFLICT: %@ was %p at install time, now %p — "
                  @"replaced by %@ in %@",
                  key, recordedIMP, currentIMP, conflictingSymbol,
                  conflictingLib);

      // Attempt auto re-swizzle recovery
      NSUInteger attempts = 0;
      [_impTrackingLock lock];
      attempts = [_reswizzleAttempts[key] unsignedIntegerValue];
      if (attempts < maxAttempts) {
        _reswizzleAttempts[key] = @(attempts + 1);
        [_impTrackingLock unlock];
        ApproovLogE(@"IMP RECOVERY: re-swizzling %@ (attempt %lu of %lu)", key,
                    (unsigned long)(attempts + 1), (unsigned long)maxAttempts);

        // Re-install our swizzle on top of theirs.
        // RSSwizzle appends to its internal block list, so a new call
        // effectively wraps whatever is currently installed.  Our block
        // runs first and then chains through the rest.
        if (isClassMethod) {
          [self reswizzleClassMethod:cls selector:sel];
          [self recordClassMethodIMPForClass:cls selector:sel];
        } else {
          [self reswizzleClass:cls selector:sel];
          [self recordIMPForClass:cls selector:sel];
        }
      } else {
        [_impTrackingLock unlock];
        ApproovLogE(@"IMP RECOVERY EXHAUSTED: %@ has been re-swizzled %lu "
                    @"times — giving up. Conflicting SDK: %@ (%@). "
                    @"Customer must disable network instrumentation in %@",
                    key, (unsigned long)maxAttempts, conflictingLib,
                    conflictingSymbol, conflictingLib);
      }
    }
  }

  if (conflicts == 0) {
    ApproovLogD(@"IMP integrity check passed: all %lu hooks intact",
                (unsigned long)snapshot.count);
  } else {
    ApproovLogE(@"IMP integrity check: %lu conflict(s) detected out of %lu "
                @"hooks — auto-recovery attempted",
                (unsigned long)conflicts, (unsigned long)snapshot.count);
  }
  return conflicts;
}

/**
 * Re-installs our swizzle on a single class/selector pair.
 * Called when an IMP conflict is detected to recover control.
 */
- (void)reswizzleClass:(Class)cls selector:(SEL)sel {
  __block ApproovRCTInterceptor *interceptor = self;
  NSString *label = NSStringFromSelector(sel);

  // For dataTask and uploadTask selectors, re-install the interception swizzle
  if (sel == @selector(dataTaskWithRequest:)) {
    RSSwizzleInstanceMethod(
        cls, sel, RSSWReturnType(NSURLSessionDataTask *),
        RSSWArguments(NSURLRequest *_Nonnull request), RSSWReplacement({
          // Set recovery flag so original blocks skip double interception
          NSThread.currentThread.threadDictionary[kApproovRecoveryActiveKey] =
              @YES;
          @try {
            if (ApproovIsMockRequest(request)) {
              return RSSWCallOriginal(request);
            }
            ApproovLogI(@"observed %@ for session %p %@ [recovered]", label,
                        self, request.URL);
            __block SessionMetadata *metadata = nil;
            dispatch_sync(interceptor->_sessionRegistryQueue, ^{
              metadata = [interceptor->_pinnedSessions objectForKey:self];
            });
            if (metadata != nil) {
              ApproovInterceptorResult *result =
                  [interceptor prepareInterceptedResultForTaskType:label
                                                           session:self
                                                          metadata:metadata
                                                           request:request];
              if ([result action] == ApproovInterceptorActionProceed)
                return RSSWCallOriginal([result request]);
              return [ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:([result action] ==
                                            ApproovInterceptorActionRetry)
                                               ? 503
                                               : 499
                               withMessage:[result message]];
            }
            return RSSWCallOriginal(request);
          } @finally {
            [NSThread.currentThread.threadDictionary
                removeObjectForKey:kApproovRecoveryActiveKey];
          }
        }),
        0, NULL);
  } else if (sel == @selector(dataTaskWithRequest:completionHandler:)) {
    RSSwizzleInstanceMethod(
        cls, sel, RSSWReturnType(NSURLSessionDataTask *),
        RSSWArguments(NSURLRequest *_Nonnull request,
                      void (^_Nullable completionHandler)(
                          NSData *_Nullable, NSURLResponse *_Nullable,
                          NSError *_Nullable)),
        RSSWReplacement({
          // Set recovery flag so original blocks skip double interception
          NSThread.currentThread.threadDictionary[kApproovRecoveryActiveKey] =
              @YES;
          @try {
            if (ApproovIsMockRequest(request)) {
              return RSSWCallOriginal(request, completionHandler);
            }
            ApproovLogI(@"observed %@ for session %p %@ [recovered]", label,
                        self, request.URL);
            __block SessionMetadata *metadata = nil;
            dispatch_sync(interceptor->_sessionRegistryQueue, ^{
              metadata = [interceptor->_pinnedSessions objectForKey:self];
            });
            if (metadata != nil) {
              ApproovInterceptorResult *result =
                  [interceptor prepareInterceptedResultForTaskType:label
                                                           session:self
                                                          metadata:metadata
                                                           request:request];
              if ([result action] == ApproovInterceptorActionProceed)
                return RSSWCallOriginal([result request], completionHandler);
              return [ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:([result action] ==
                                            ApproovInterceptorActionRetry)
                                               ? 503
                                               : 499
                               withMessage:[result message]
                         completionHandler:completionHandler];
            }
            return RSSWCallOriginal(request, completionHandler);
          } @finally {
            [NSThread.currentThread.threadDictionary
                removeObjectForKey:kApproovRecoveryActiveKey];
          }
        }),
        0, NULL);
  } else if (sel == @selector(dataTaskWithURL:)) {
    RSSwizzleInstanceMethod(
        cls, sel, RSSWReturnType(NSURLSessionDataTask *),
        RSSWArguments(NSURL *_Nonnull url), RSSWReplacement({
          // Set recovery flag so original blocks skip double interception
          NSThread.currentThread.threadDictionary[kApproovRecoveryActiveKey] =
              @YES;
          @try {
            if (ApproovIsMockURL(url)) {
              return RSSWCallOriginal(url);
            }
            ApproovLogI(@"observed %@ for session %p %@ [recovered]", label,
                        self, url);
            __block SessionMetadata *metadata = nil;
            dispatch_sync(interceptor->_sessionRegistryQueue, ^{
              metadata = [interceptor->_pinnedSessions objectForKey:self];
            });
            if (metadata != nil) {
              NSURLRequest *request = [NSURLRequest requestWithURL:url];
              ApproovInterceptorResult *result =
                  [interceptor prepareInterceptedResultForTaskType:label
                                                           session:self
                                                          metadata:metadata
                                                           request:request];
              if ([result action] == ApproovInterceptorActionProceed)
                return RSSWCallOriginal([result.request URL]);
              return [ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:([result action] ==
                                            ApproovInterceptorActionRetry)
                                               ? 503
                                               : 499
                               withMessage:[result message]];
            }
            return RSSWCallOriginal(url);
          } @finally {
            [NSThread.currentThread.threadDictionary
                removeObjectForKey:kApproovRecoveryActiveKey];
          }
        }),
        0, NULL);
  } else if (sel == @selector(dataTaskWithURL:completionHandler:)) {
    RSSwizzleInstanceMethod(
        cls, sel, RSSWReturnType(NSURLSessionDataTask *),
        RSSWArguments(NSURL *_Nonnull url,
                      void (^_Nullable completionHandler)(
                          NSData *_Nullable, NSURLResponse *_Nullable,
                          NSError *_Nullable)),
        RSSWReplacement({
          // Set recovery flag so original blocks skip double interception
          NSThread.currentThread.threadDictionary[kApproovRecoveryActiveKey] =
              @YES;
          @try {
            if (ApproovIsMockURL(url)) {
              return RSSWCallOriginal(url, completionHandler);
            }
            ApproovLogI(@"observed %@ for session %p %@ [recovered]", label,
                        self, url);
            __block SessionMetadata *metadata = nil;
            dispatch_sync(interceptor->_sessionRegistryQueue, ^{
              metadata = [interceptor->_pinnedSessions objectForKey:self];
            });
            if (metadata != nil) {
              NSURLRequest *request = [NSURLRequest requestWithURL:url];
              ApproovInterceptorResult *result =
                  [interceptor prepareInterceptedResultForTaskType:label
                                                           session:self
                                                          metadata:metadata
                                                           request:request];
              if ([result action] == ApproovInterceptorActionProceed)
                return RSSWCallOriginal([result.request URL], completionHandler);
              return [ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:([result action] ==
                                            ApproovInterceptorActionRetry)
                                               ? 503
                                               : 499
                               withMessage:[result message]
                         completionHandler:completionHandler];
            }
            return RSSWCallOriginal(url, completionHandler);
          } @finally {
            [NSThread.currentThread.threadDictionary
                removeObjectForKey:kApproovRecoveryActiveKey];
          }
        }),
        0, NULL);

    // MARK: Upload task re-swizzle

  } else if (sel == @selector(uploadTaskWithRequest:fromData:)) {
    RSSwizzleInstanceMethod(
        cls, sel, RSSWReturnType(NSURLSessionUploadTask *),
        RSSWArguments(NSURLRequest *_Nonnull request,
                      NSData *_Nullable bodyData),
        RSSWReplacement({
          // Set recovery flag so original blocks skip double interception
          NSThread.currentThread.threadDictionary[kApproovRecoveryActiveKey] =
              @YES;
          @try {
            if (ApproovIsMockRequest(request)) {
              return RSSWCallOriginal(request, bodyData);
            }
            ApproovLogI(@"observed %@ for session %p %@ [recovered]", label,
                        self, request.URL);
            __block SessionMetadata *metadata = nil;
            dispatch_sync(interceptor->_sessionRegistryQueue, ^{
              metadata = [interceptor->_pinnedSessions objectForKey:self];
            });
            if (metadata != nil) {
              ApproovInterceptorResult *result =
                  [interceptor prepareInterceptedResultForTaskType:label
                                                           session:self
                                                          metadata:metadata
                                                           request:request];
              if ([result action] == ApproovInterceptorActionProceed)
                return RSSWCallOriginal([result request], bodyData);
              return (NSURLSessionUploadTask *)[ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:([result action] ==
                                            ApproovInterceptorActionRetry)
                                               ? 503
                                               : 499
                               withMessage:[result message]];
            }
            return RSSWCallOriginal(request, bodyData);
          } @finally {
            [NSThread.currentThread.threadDictionary
                removeObjectForKey:kApproovRecoveryActiveKey];
          }
        }),
        0, NULL);
  } else if (sel == @selector(uploadTaskWithRequest:
                                           fromData:completionHandler:)) {
    RSSwizzleInstanceMethod(
        cls, sel, RSSWReturnType(NSURLSessionUploadTask *),
        RSSWArguments(NSURLRequest *_Nonnull request,
                      NSData *_Nullable bodyData,
                      void (^_Nullable completionHandler)(
                          NSData *_Nullable, NSURLResponse *_Nullable,
                          NSError *_Nullable)),
        RSSWReplacement({
          // Set recovery flag so original blocks skip double interception
          NSThread.currentThread.threadDictionary[kApproovRecoveryActiveKey] =
              @YES;
          @try {
            if (ApproovIsMockRequest(request)) {
              return RSSWCallOriginal(request, bodyData, completionHandler);
            }
            ApproovLogI(@"observed %@ for session %p %@ [recovered]", label,
                        self, request.URL);
            __block SessionMetadata *metadata = nil;
            dispatch_sync(interceptor->_sessionRegistryQueue, ^{
              metadata = [interceptor->_pinnedSessions objectForKey:self];
            });
            if (metadata != nil) {
              ApproovInterceptorResult *result =
                  [interceptor prepareInterceptedResultForTaskType:label
                                                           session:self
                                                          metadata:metadata
                                                           request:request];
              if ([result action] == ApproovInterceptorActionProceed)
                return RSSWCallOriginal([result request], bodyData,
                                        completionHandler);
              return [ApproovMockURLProtocol
                  createMockUploadTaskForSession:self
                            withErrorCode:([result action] ==
                                            ApproovInterceptorActionRetry)
                                               ? 503
                                               : 499
                               withMessage:[result message]
                         completionHandler:completionHandler];
            }
            return RSSWCallOriginal(request, bodyData, completionHandler);
          } @finally {
            [NSThread.currentThread.threadDictionary
                removeObjectForKey:kApproovRecoveryActiveKey];
          }
        }),
        0, NULL);
  } else if (sel == @selector(uploadTaskWithRequest:fromFile:)) {
    RSSwizzleInstanceMethod(
        cls, sel, RSSWReturnType(NSURLSessionUploadTask *),
        RSSWArguments(NSURLRequest *_Nonnull request, NSURL *_Nonnull fileURL),
        RSSWReplacement({
          // Set recovery flag so original blocks skip double interception
          NSThread.currentThread.threadDictionary[kApproovRecoveryActiveKey] =
              @YES;
          @try {
            if (ApproovIsMockRequest(request)) {
              return RSSWCallOriginal(request, fileURL);
            }
            ApproovLogI(@"observed %@ for session %p %@ [recovered]", label,
                        self, request.URL);
            __block SessionMetadata *metadata = nil;
            dispatch_sync(interceptor->_sessionRegistryQueue, ^{
              metadata = [interceptor->_pinnedSessions objectForKey:self];
            });
            if (metadata != nil) {
              ApproovInterceptorResult *result =
                  [interceptor prepareInterceptedResultForTaskType:label
                                                           session:self
                                                          metadata:metadata
                                                           request:request];
              if ([result action] == ApproovInterceptorActionProceed)
                return RSSWCallOriginal([result request], fileURL);
              return (NSURLSessionUploadTask *)[ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:([result action] ==
                                            ApproovInterceptorActionRetry)
                                               ? 503
                                               : 499
                               withMessage:[result message]];
            }
            return RSSWCallOriginal(request, fileURL);
          } @finally {
            [NSThread.currentThread.threadDictionary
                removeObjectForKey:kApproovRecoveryActiveKey];
          }
        }),
        0, NULL);
  } else if (sel == @selector(uploadTaskWithRequest:
                                           fromFile:completionHandler:)) {
    RSSwizzleInstanceMethod(
        cls, sel, RSSWReturnType(NSURLSessionUploadTask *),
        RSSWArguments(NSURLRequest *_Nonnull request, NSURL *_Nullable fileURL,
                      void (^_Nullable completionHandler)(
                          NSData *_Nullable, NSURLResponse *_Nullable,
                          NSError *_Nullable)),
        RSSWReplacement({
          // Set recovery flag so original blocks skip double interception
          NSThread.currentThread.threadDictionary[kApproovRecoveryActiveKey] =
              @YES;
          @try {
            if (ApproovIsMockRequest(request)) {
              return RSSWCallOriginal(request, fileURL, completionHandler);
            }
            ApproovLogI(@"observed %@ for session %p %@ [recovered]", label,
                        self, request.URL);
            __block SessionMetadata *metadata = nil;
            dispatch_sync(interceptor->_sessionRegistryQueue, ^{
              metadata = [interceptor->_pinnedSessions objectForKey:self];
            });
            if (metadata != nil) {
              ApproovInterceptorResult *result =
                  [interceptor prepareInterceptedResultForTaskType:label
                                                           session:self
                                                          metadata:metadata
                                                           request:request];
              if ([result action] == ApproovInterceptorActionProceed)
                return RSSWCallOriginal([result request], fileURL,
                                        completionHandler);
              return [ApproovMockURLProtocol
                  createMockUploadTaskForSession:self
                            withErrorCode:([result action] ==
                                            ApproovInterceptorActionRetry)
                                               ? 503
                                               : 499
                               withMessage:[result message]
                         completionHandler:completionHandler];
            }
            return RSSWCallOriginal(request, fileURL, completionHandler);
          } @finally {
            [NSThread.currentThread.threadDictionary
                removeObjectForKey:kApproovRecoveryActiveKey];
          }
        }),
        0, NULL);
  } else if (sel == @selector(uploadTaskWithStreamedRequest:)) {
    RSSwizzleInstanceMethod(
        cls, sel, RSSWReturnType(NSURLSessionUploadTask *),
        RSSWArguments(NSURLRequest *_Nonnull request), RSSWReplacement({
          // Set recovery flag so original blocks skip double interception
          NSThread.currentThread.threadDictionary[kApproovRecoveryActiveKey] =
              @YES;
          @try {
            if (ApproovIsMockRequest(request)) {
              return RSSWCallOriginal(request);
            }
            ApproovLogI(@"observed %@ for session %p %@ [recovered]", label,
                        self, request.URL);
            __block SessionMetadata *metadata = nil;
            dispatch_sync(interceptor->_sessionRegistryQueue, ^{
              metadata = [interceptor->_pinnedSessions objectForKey:self];
            });
            if (metadata != nil) {
              ApproovInterceptorResult *result =
                  [interceptor prepareInterceptedResultForTaskType:label
                                                           session:self
                                                          metadata:metadata
                                                           request:request];
              if ([result action] == ApproovInterceptorActionProceed)
                return RSSWCallOriginal([result request]);
              return (NSURLSessionUploadTask *)[ApproovMockURLProtocol
                  createMockTaskForSession:self
                            withErrorCode:([result action] ==
                                            ApproovInterceptorActionRetry)
                                               ? 503
                                               : 499
                               withMessage:[result message]];
            }
            return RSSWCallOriginal(request);
          } @finally {
            [NSThread.currentThread.threadDictionary
                removeObjectForKey:kApproovRecoveryActiveKey];
          }
        }),
        0, NULL);
  } else {
    ApproovLogE(@"IMP RECOVERY: unrecognized selector %@.%@ — "
                @"cannot auto-recover",
                NSStringFromClass(cls), NSStringFromSelector(sel));
  }
}

/**
 * Re-installs our swizzle on a class method (session creation).
 * Called when an IMP conflict is detected on the session factory method.
 */
- (void)reswizzleClassMethod:(Class)cls selector:(SEL)sel {
  if (sel != @selector(sessionWithConfiguration:delegate:delegateQueue:)) {
    ApproovLogE(@"IMP RECOVERY: unrecognized class method +%@.%@ — "
                @"cannot auto-recover",
                NSStringFromClass(cls), NSStringFromSelector(sel));
    return;
  }

  __block ApproovRCTInterceptor *interceptor = self;
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wshadow"
  RSSwizzleClassMethod(
      cls, @selector(sessionWithConfiguration:delegate:delegateQueue:),
      RSSWReturnType(NSURLSession *),
      RSSWArguments(NSURLSessionConfiguration *_Nonnull configuration,
                    id _Nullable delegate, NSOperationQueue *_Nullable queue),
      RSSWReplacement({
        NSURLSession *session;
        if (delegate != nil) {
          NSString *delegateClassName = NSStringFromClass([delegate class]);
          ApproovLogI(@"checking session creation with %@ delegate "
                      @"[recovered]",
                      delegateClassName);

          BOOL shouldIntercept;
          [interceptor->_policyLock lock];
          shouldIntercept = [interceptor->_policy
              shouldInterceptSessionWithDelegate:delegateClassName];
          [interceptor->_policyLock unlock];

          if (shouldIntercept) {
            ApproovLogI(@"intercepting session creation with %@ delegate "
                        @"[recovered]",
                        delegateClassName);

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

            PinningURLSessionDelegate *pinningDelegate =
                [PinningURLSessionDelegate
                    createWithDelegate:delegate
                        approovService:interceptor.approovService];

            session = RSSWCallOriginal(configuration, pinningDelegate, queue);
            __weak NSURLSession *weakSession = session;

            ApproovLogI(@"created pinned session %p class=%@ for "
                        @"delegate %@ [recovered]",
                        session, NSStringFromClass([session class]),
                        delegateClassName);

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
                    }
                  });
                };

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
              ApproovLogI(@"Registered session %p (total: %lu) [recovered]",
                          session,
                          (unsigned long)interceptor->_pinnedSessions.count);
            });

            return session;
          } else {
            ApproovLogW(@"SKIPPING session creation with %@ delegate "
                        @"(not in interception policy) [recovered]",
                        delegateClassName);
          }
        } else {
          ApproovLogD(@"session creation with nil delegate [recovered]");
        }

        return RSSWCallOriginal(configuration, delegate, queue);
      }));
#pragma clang diagnostic pop
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

  for (Class sessionClass in [self sessionClassesForTaskSwizzling]) {
    // Use the same class set as task swizzling so pinned session registry
    // cleanup still runs when private session subclasses invalidate.
    ApproovLogI(@"installing invalidation swizzles on class %@",
                NSStringFromClass(sessionClass));
    for (NSString *selectorName in invalidationSelectors) {
      RSSwizzleInstanceMethod(
          sessionClass, NSSelectorFromString(selectorName),
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
}

// MARK: - Configuration API Implementations

+ (void)setInterceptionMode:(NSInteger)mode {
  if (_sharedInterceptor) {
    [_sharedInterceptor->_policyLock lock];
    _sharedInterceptor->_policy.mode = (SessionInterceptionMode)mode;
    [_sharedInterceptor->_policyLock unlock];
    ApproovLogI(@"Interception mode set to %ld", (long)mode);
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

  if (!gSessionMetadataCollectionEnabled) {
    return @{
      @"enabled" : @NO,
      @"message" : @"Session metadata collection disabled"
    };
  }

  __block NSMutableArray *registeredSessions = [NSMutableArray array];
  __block NSMutableArray *unregisteredSessions = [NSMutableArray array];
  __block NSUInteger totalRequests = 0;
  __block NSUInteger nilDelegateSessionCount = 0;
  __block NSUInteger policySkippedSessionCount = 0;
  __block NSUInteger taskObservedWithoutSessionCreationCount = 0;

  dispatch_sync(_sharedInterceptor->_sessionRegistryQueue, ^{
    NSEnumerator *sessionEnum =
        [_sharedInterceptor->_observedSessions keyEnumerator];
    NSURLSession *session;
    while ((session = [sessionEnum nextObject])) {
      SessionMetadata *metadata =
          [_sharedInterceptor->_observedSessions objectForKey:session];
      if (metadata) {
        totalRequests += metadata.requestCount;
        NSDictionary *sessionEntry = @{
          @"sessionPointer" : [NSString stringWithFormat:@"%p", session],
          @"delegateClassName" : metadata.delegateClassName,
          @"delegateImagePath" : metadata.delegateImagePath ?: [NSNull null],
          @"delegateBundleIdentifier" :
              metadata.delegateBundleIdentifier ?: [NSNull null],
          @"createdAt" : [NSString stringWithFormat:@"%@", metadata.createdAt],
          @"requestCount" : @(metadata.requestCount),
          @"registeredForPinning" : @(metadata.registeredForPinning),
          @"creationDisposition" : metadata.creationDisposition ?: @"unknown",
          @"lastObservedAt" : metadata.lastObservedAt != nil ? [NSString stringWithFormat:@"%@", metadata.lastObservedAt] : [NSNull null],
          @"lastObservedTaskType" : metadata.lastObservedTaskType ?: [NSNull null],
          @"lastObservedRequestURL" : metadata.lastObservedRequestURL ?: [NSNull null],
          @"lastObservedRequestMethod" : metadata.lastObservedRequestMethod ?: [NSNull null],
          @"authChallengeCount" : @(metadata.authChallengeCount),
          @"pinnedChallengeCount" : @(metadata.pinnedChallengeCount),
          @"blockedChallengeCount" : @(metadata.blockedChallengeCount),
          @"pinningDelegateVerified" : @(metadata.pinningDelegateVerified)
        };

        if ([metadata.creationDisposition isEqualToString:@"nil-delegate"]) {
          nilDelegateSessionCount++;
        } else if ([metadata.creationDisposition
                        isEqualToString:@"policy-skipped"]) {
          policySkippedSessionCount++;
        } else if ([metadata.creationDisposition
                        isEqualToString:
                            @"task-observed-without-session-creation"]) {
          taskObservedWithoutSessionCreationCount++;
        }

        if (metadata.registeredForPinning) {
          [registeredSessions addObject:sessionEntry];
        } else {
          [unregisteredSessions addObject:sessionEntry];
        }
      }
    }
  });

  return @{
    @"enabled" : @YES,
    @"totalSessions" :
        @(registeredSessions.count + unregisteredSessions.count),
    @"totalRequests" : @(totalRequests),
    @"sessions" : registeredSessions,
    @"registeredSessions" : registeredSessions,
    @"unregisteredSessions" : unregisteredSessions,
    @"registeredSessionCount" : @(registeredSessions.count),
    @"unregisteredSessionCount" : @(unregisteredSessions.count),
    @"nilDelegateSessionCount" : @(nilDelegateSessionCount),
    @"policySkippedSessionCount" : @(policySkippedSessionCount),
    @"taskObservedWithoutSessionCreationCount" :
        @(taskObservedWithoutSessionCreationCount)
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

+ (NSDictionary *)getIMPIntegrityDiagnostics {
  if (!_sharedInterceptor) {
    return @{@"error" : @"Interceptor not initialized"};
  }
  NSUInteger conflicts = [_sharedInterceptor verifyIMPIntegrity];
  return @{
    @"conflicts" : @(conflicts),
    @"status" : conflicts == 0 ? @"all hooks intact" : @"CONFLICTS DETECTED"
  };
}

@end
