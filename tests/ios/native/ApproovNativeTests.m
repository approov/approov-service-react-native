#import <Foundation/Foundation.h>

#import "Approov/Approov.h"
#import "approov_service_react_native-Swift.h"
#import "ios/ApproovMockURLProtocol.h"
#import "ios/ApproovService.h"

extern BOOL isInitialized;
extern NSTimeInterval earliestNetworkRequestTime;
extern BOOL useApproovStatusIfNoToken;
extern BOOL suppressLoggingUnknownURL;
extern NSString *approovTokenHeader;
extern NSString *approovTraceIDHeader;
extern NSString *approovTokenPrefix;
extern NSString *initialConfigString;
extern NSString *bindingHeader;
extern NSMutableDictionary<NSString *, NSString *> *substitutionHeaders;
extern NSMutableSet<NSString *> *substitutionQueryParams;
extern NSMutableSet<NSString *> *exclusionURLRegexs;

static NSUInteger gFailureCount = 0;

static void Fail(NSString *message) {
  NSLog(@"FAIL: %@", message);
  gFailureCount += 1;
}

static void AssertTrue(BOOL condition, NSString *message) {
  if (!condition) {
    Fail(message);
  }
}

static void AssertEqualObjects(id expected, id actual, NSString *message) {
  if ((expected == nil && actual == nil) || [expected isEqual:actual]) {
    return;
  }
  Fail([NSString stringWithFormat:@"%@ (expected %@, got %@)", message, expected,
                                   actual]);
}

static void AssertEqualIntegers(NSInteger expected, NSInteger actual,
                                NSString *message) {
  if (expected != actual) {
    Fail([NSString stringWithFormat:@"%@ (expected %ld, got %ld)", message,
                                   (long)expected, (long)actual]);
  }
}

@interface RCTTestNetworkDelegate : NSObject <NSURLSessionDataDelegate>
@property(nonatomic, strong) NSMutableData *receivedData;
@property(nonatomic, strong, nullable) NSHTTPURLResponse *response;
@property(nonatomic, strong, nullable) NSError *error;
@property(nonatomic, strong) dispatch_semaphore_t semaphore;
@end

@interface ApproovService (NativeTestInitialize)
- (void)initialize:(NSString *)config
           comment:(NSString *_Nullable)comment
          resolver:(RCTPromiseResolveBlock)resolve
          rejecter:(RCTPromiseRejectBlock)reject;
- (void)isInitialized:(RCTPromiseResolveBlock)resolve
             rejecter:(RCTPromiseRejectBlock)reject;
- (void)isApproovEnabled:(RCTPromiseResolveBlock)resolve
                rejecter:(RCTPromiseRejectBlock)reject;
@end

@implementation RCTTestNetworkDelegate

- (instancetype)init {
  self = [super init];
  if (self != nil) {
    _receivedData = [[NSMutableData alloc] init];
    _semaphore = dispatch_semaphore_create(0);
  }
  return self;
}

- (void)URLSession:(NSURLSession *)session
          dataTask:(NSURLSessionDataTask *)dataTask
didReceiveResponse:(NSURLResponse *)response
 completionHandler:(void (^)(NSURLSessionResponseDisposition))completionHandler {
  (void)session;
  (void)dataTask;
  self.response = (NSHTTPURLResponse *)response;
  completionHandler(NSURLSessionResponseAllow);
}

- (void)URLSession:(NSURLSession *)session
          dataTask:(NSURLSessionDataTask *)dataTask
    didReceiveData:(NSData *)data {
  (void)session;
  (void)dataTask;
  [self.receivedData appendData:data];
}

- (void)URLSession:(NSURLSession *)session
              task:(NSURLSessionTask *)task
didCompleteWithError:(NSError *)error {
  (void)session;
  (void)task;
  self.error = error;
  dispatch_semaphore_signal(self.semaphore);
}

@end

static ApproovService *FreshService(void) {
  ApproovTestReset();
  ApproovMutatorBridgeReset();

  isInitialized = NO;
  earliestNetworkRequestTime = 0;
  useApproovStatusIfNoToken = NO;
  suppressLoggingUnknownURL = NO;
  initialConfigString = @"test-config";
  approovTokenHeader = @"Approov-Token";
  approovTraceIDHeader = @"Approov-TraceID";
  approovTokenPrefix = @"";
  bindingHeader = @"";

  return [[ApproovService alloc] init];
}

static NSMutableURLRequest *MutableRequest(NSString *urlString) {
  return [NSMutableURLRequest requestWithURL:[NSURL URLWithString:urlString]];
}

static ApproovTokenFetchResult *Result(ApproovTokenFetchStatus status,
                                       NSString *token,
                                       NSString *secureString,
                                       NSString *traceID,
                                       BOOL configChanged) {
  return [ApproovTokenFetchResult resultWithStatus:status
                                             token:token
                                     loggableToken:token
                                      secureString:secureString
                                           traceID:traceID
                                               ARC:@"ARC123"
                                  rejectionReasons:@"reason"
                                     configChanged:configChanged];
}


// Regression guard: verifies that a native SDK initialization failure (e.g. bad
// config) correctly rejects the RN promise and leaves the service layer in an
// uninitialized state, preventing subsequent requests from silently proceeding
// without attestation.
static void TestInitializeFailureRejectsAndKeepsLayerUninitialized(void) {
  ApproovService *service = FreshService();
  NSError *initError = [NSError errorWithDomain:@"io.approov.tests"
                                           code:1
                                       userInfo:@{NSLocalizedDescriptionKey : @"bad config"}];
  ApproovTestSetInitializationError(initError);

  __block BOOL didResolve = NO;
  __block NSString *rejectionCode = nil;
  __block NSString *rejectionMessage = nil;
  [service initialize:@"bad-config"
              comment:nil
             resolver:^(__unused id value) {
               didResolve = YES;
             }
             rejecter:^(NSString *code, NSString *message,
                        __unused NSError *error) {
               rejectionCode = code;
               rejectionMessage = message;
             }];
  ApproovTestClearInitializationError();

  AssertTrue(!didResolve, @"Failed initialization should reject instead of resolve");
  AssertEqualObjects(@"initialize", rejectionCode,
                     @"Failed initialization should reject with initialize");
  AssertEqualObjects(@"initialization failed: bad config", rejectionMessage,
                     @"Failed initialization should surface the initialization error");
  AssertTrue(!isInitialized,
             @"Failed initialization should leave the layer uninitialized");
}

// Regression guard: the native SDK returns (NO, nil) when already initialized
// with the same config. This must resolve (not reject) and mark the layer
// initialized to avoid breaking hot-reload / bridge-reload flows.
static void TestInitializeTreatsFalseNilErrorAsAlreadyInitialized(void) {
  ApproovService *service = FreshService();
  ApproovTestSetInitializationResult(NO);
  ApproovTestClearInitializationError();

  __block BOOL didResolve = NO;
  __block NSString *rejectionCode = nil;
  [service initialize:@"test-config"
              comment:nil
             resolver:^(__unused id value) {
               didResolve = YES;
             }
             rejecter:^(NSString *code, __unused NSString *message,
                        __unused NSError *error) {
               rejectionCode = code;
             }];
  ApproovTestSetInitializationResult(YES);

  AssertTrue(didResolve,
             @"False return with nil error should be treated as already initialized");
  AssertEqualObjects(nil, rejectionCode,
                     @"False return with nil error should not reject");
  AssertTrue(isInitialized,
             @"False return with nil error should still mark the layer initialized");
}

// Regression guard: the native SDK raises a specific error when re-initialized
// with a different config. The service layer must surface this as a promise
// rejection so the RN caller can handle it, not silently swallow it.
static void TestInitializeRejectsNativeDifferentConfigurationError(void) {
  ApproovService *service = FreshService();
  NSError *differentConfigurationError =
      [NSError errorWithDomain:@"com.criticalblue.Approov"
                          code:0
                      userInfo:@{
                        NSLocalizedDescriptionKey :
                            @"Approov SDK already initialized with a different configuration"
                      }];
  ApproovTestSetInitializationError(differentConfigurationError);

  __block BOOL didResolve = NO;
  __block NSString *rejectionCode = nil;
  __block NSString *rejectionMessage = nil;
  [service initialize:@"test-config"
              comment:nil
             resolver:^(__unused id value) {
               didResolve = YES;
             }
             rejecter:^(NSString *code, NSString *message,
                        __unused NSError *error) {
               rejectionCode = code;
               rejectionMessage = message;
             }];
  ApproovTestClearInitializationError();

  AssertTrue(!didResolve,
             @"Different-configuration native SDK error should reject");
  AssertEqualObjects(@"initialize", rejectionCode,
                     @"Different-configuration native SDK error should reject with initialize");
  AssertEqualObjects(
      @"initialization failed: Approov SDK already initialized with a different configuration",
      rejectionMessage,
      @"Different-configuration native SDK error should preserve the platform message");
  AssertTrue(!isInitialized,
             @"Different-configuration native SDK error should leave the layer uninitialized");
}

// Verifies the transition: empty-config bootstrap → SDK failure.
// After bootstrapping with an empty config the layer is initialized but Approov
// is disabled. A subsequent attempt with a real config that fails at the SDK
// level should reject the promise while preserving the initialized (but disabled)
// bootstrap state.
static void TestInitializeWithEmptyConfigThenSdkFailurePreservesBootstrap(void) {
  ApproovService *service = FreshService();

  // Bootstrap with empty config
  [service initialize:@""
              comment:nil
             resolver:^(__unused id value) {}
             rejecter:^(__unused NSString *code, __unused NSString *message,
                        __unused NSError *error) {
               Fail(@"Empty-config bootstrap should not reject");
             }];
  AssertTrue(isInitialized, @"Empty-config bootstrap should mark the layer initialized");

  // Simulate SDK failure on the real config attempt
  NSError *sdkError = [NSError errorWithDomain:@"io.approov.tests"
                                          code:1
                                      userInfo:@{NSLocalizedDescriptionKey : @"server unreachable"}];
  ApproovTestSetInitializationError(sdkError);

  __block BOOL didResolve = NO;
  __block NSString *rejectionCode = nil;
  [service initialize:@"real-config"
              comment:nil
             resolver:^(__unused id value) {
               didResolve = YES;
             }
             rejecter:^(NSString *code, __unused NSString *message,
                        __unused NSError *error) {
               rejectionCode = code;
             }];
  ApproovTestClearInitializationError();

  AssertTrue(!didResolve,
             @"SDK failure after empty bootstrap should reject");
  AssertEqualObjects(@"initialize", rejectionCode,
                     @"SDK failure after empty bootstrap should reject with initialize");
  // The layer stays initialized from the empty bootstrap
  AssertTrue(isInitialized,
             @"SDK failure after empty bootstrap should preserve the initialized state");
}


static NSURLSession *MockSession(void) {
  NSURLSessionConfiguration *configuration =
      [NSURLSessionConfiguration ephemeralSessionConfiguration];
  configuration.protocolClasses = @[ [ApproovMockURLProtocol class] ];
  return [NSURLSession sessionWithConfiguration:configuration];
}

// Regression guard: CHANGELOG 3.5.12 "iOS Mock Completion Handlers"
// Verifies that ApproovMockURLProtocol status-code mock tasks invoke the
// NSURLSession completion handler. Before the fix, completion handlers were
// silently dropped, causing callers to hang indefinitely.
static void TestMockStatusCompletionHandlersFire(void) {
  NSURLSession *session = MockSession();
  dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
  __block NSInteger statusCode = 0;
  __block NSError *capturedError = nil;

  NSURLSessionDataTask *task = [ApproovMockURLProtocol
      createMockTaskForSession:session
                withStatusCode:503
                   withMessage:@"retry please"
             completionHandler:^(NSData *data, NSURLResponse *response,
                                 NSError *error) {
               (void)data;
               statusCode = ((NSHTTPURLResponse *)response).statusCode;
               capturedError = error;
               dispatch_semaphore_signal(semaphore);
             }];
  [task resume];

  long waitResult = dispatch_semaphore_wait(
      semaphore, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));
  AssertEqualIntegers(0, waitResult, @"Mock status task should finish in time");
  AssertEqualIntegers(503, statusCode,
                      @"Mock status task should surface the status code");
  AssertTrue(capturedError == nil,
             @"Mock status tasks should complete without an NSError");
  [session finishTasksAndInvalidate];
}

// Regression guard: CHANGELOG 3.5.12 "iOS Mock Completion Handlers"
// Verifies that ApproovMockURLProtocol error mock tasks invoke the completion
// handler with the correct NSError. Before the fix, error completion handlers
// were dropped on the failure path.
static void TestMockErrorCompletionHandlersFire(void) {
  NSURLSession *session = MockSession();
  dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
  __block NSError *capturedError = nil;

  NSURLSessionDataTask *task = [ApproovMockURLProtocol
      createMockTaskForSession:session
                 withErrorCode:499
                   withMessage:@"mock failure"
             completionHandler:^(NSData *data, NSURLResponse *response,
                                 NSError *error) {
               (void)data;
               (void)response;
               capturedError = error;
               dispatch_semaphore_signal(semaphore);
             }];
  [task resume];

  long waitResult = dispatch_semaphore_wait(
      semaphore, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));
  AssertEqualIntegers(0, waitResult, @"Mock error task should finish in time");
  AssertTrue(capturedError != nil,
             @"Mock error tasks should surface an NSError");
  AssertTrue([capturedError.localizedDescription containsString:@"mock failure"],
             @"Mock error tasks should preserve the encoded message");
  [session finishTasksAndInvalidate];
}

// Regression guard: CHANGELOG 3.5.12 "iOS Mock Completion Handlers"
// Verifies that upload task mock responses created through completion-handler
// selectors return real upload tasks and invoke the handler correctly. Before
// the fix, data tasks were incorrectly cast as upload tasks.
static void TestMockUploadCompletionHandlersFire(void) {
  NSURLSession *session = MockSession();
  dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
  __block NSInteger statusCode = 0;

  NSURLSessionUploadTask *task = [ApproovMockURLProtocol
      createMockUploadTaskForSession:session
                      withStatusCode:503
                         withMessage:@"retry upload"
                   completionHandler:^(NSData *data, NSURLResponse *response,
                                       NSError *error) {
                     (void)data;
                     (void)error;
                     statusCode = ((NSHTTPURLResponse *)response).statusCode;
                     dispatch_semaphore_signal(semaphore);
                   }];
  [task resume];

  long waitResult = dispatch_semaphore_wait(
      semaphore, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));
  AssertEqualIntegers(0, waitResult, @"Mock upload task should finish in time");
  AssertEqualIntegers(503, statusCode,
                      @"Mock upload tasks should preserve the status code");
  [session finishTasksAndInvalidate];
}

// Regression guard: CHANGELOG 3.5.12 "iOS Mock Response Recursion Fix"
// Verifies that the swizzled dataTaskWithURL: path returns a synthetic 503
// response on failure rather than recursing through the interceptor. This
// exercises the mockhttps:// URL-scheme filter that prevents re-interception.
static void TestReactFetchStyleDataTaskWithURLReturnsSyntheticResponse(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;

  __block NSInteger fetchTokenHandlerCalls = 0;
  ApproovMutatorBridgeSetFetchTokenHandler(^BOOL(id result, NSString *url,
                                                NSError **errorPointer) {
    (void)result;
    (void)url;
    (void)errorPointer;
    fetchTokenHandlerCalls += 1;
    return NO;
  });

  ApproovTestEnqueueTokenResult(
      Result(ApproovTokenFetchStatusPoorNetwork, @"", @"", @"", NO));

  RCTTestNetworkDelegate *delegate = [[RCTTestNetworkDelegate alloc] init];
  NSURLSessionConfiguration *configuration =
      [NSURLSessionConfiguration ephemeralSessionConfiguration];
  NSURLSession *session =
      [NSURLSession sessionWithConfiguration:configuration
                                    delegate:delegate
                               delegateQueue:nil];

  NSURLSessionDataTask *task = [session
      dataTaskWithURL:[NSURL URLWithString:@"https://example.com/data"]];
  [task resume];

  long waitResult = dispatch_semaphore_wait(
      delegate.semaphore, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));
  AssertEqualIntegers(0, waitResult,
                      @"Synthetic dataTaskWithURL retry should complete in time");
  AssertTrue(delegate.error == nil,
             @"Synthetic dataTaskWithURL retry should not fail with NSError");
  AssertEqualIntegers(503, delegate.response.statusCode,
                      @"Synthetic dataTaskWithURL retry should surface a 503 status");
  AssertEqualIntegers(0, (NSInteger)delegate.receivedData.length,
                      @"Synthetic dataTaskWithURL retry should not include a body");
  AssertEqualIntegers(1, (NSInteger)ApproovTestFetchApproovTokenCallCount(),
                      @"Synthetic dataTaskWithURL retry should only fetch a token once");
  AssertEqualIntegers(1, fetchTokenHandlerCalls,
                      @"Synthetic dataTaskWithURL retry should only invoke the mutator once");

  [session finishTasksAndInvalidate];
  (void)service;
}

// Regression guard: CHANGELOG 3.5.12 "iOS Mock Response Recursion Fix"
// Verifies that the swizzled dataTaskWithRequest: path on POOR_NETWORK returns
// a synthetic 503 without recursion. Ensures the token is fetched exactly once
// and the custom mutator is invoked exactly once — proving the recursion guard
// is effective.
static void
TestReactFetchStylePoorNetworkReturnsSyntheticResponseWithoutRecursion(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;

  __block NSInteger fetchTokenHandlerCalls = 0;
  ApproovMutatorBridgeSetFetchTokenHandler(^BOOL(id result, NSString *url,
                                                NSError **errorPointer) {
    (void)result;
    (void)url;
    (void)errorPointer;
    fetchTokenHandlerCalls += 1;
    return NO;
  });

  ApproovTestEnqueueTokenResult(
      Result(ApproovTokenFetchStatusPoorNetwork, @"", @"", @"", NO));

  RCTTestNetworkDelegate *delegate = [[RCTTestNetworkDelegate alloc] init];
  NSURLSessionConfiguration *configuration =
      [NSURLSessionConfiguration ephemeralSessionConfiguration];
  NSURLSession *session =
      [NSURLSession sessionWithConfiguration:configuration
                                    delegate:delegate
                               delegateQueue:nil];

  NSURLRequest *request =
      [NSURLRequest requestWithURL:[NSURL URLWithString:@"https://example.com/data"]];
  NSURLSessionDataTask *task = [session dataTaskWithRequest:request];
  [task resume];

  long waitResult = dispatch_semaphore_wait(
      delegate.semaphore, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));
  AssertEqualIntegers(0, waitResult,
                      @"Synthetic retry response should complete in time");
  AssertTrue(delegate.error == nil,
             @"Synthetic retry response should not complete with an NSError");
  AssertEqualIntegers(503, delegate.response.statusCode,
                      @"Synthetic retry response should surface a 503 status");
  AssertEqualIntegers(0, (NSInteger)delegate.receivedData.length,
                      @"Synthetic retry response should not include a body payload");
  AssertEqualIntegers(1, (NSInteger)ApproovTestFetchApproovTokenCallCount(),
                      @"Synthetic retry response should only fetch an Approov token once");
  AssertEqualIntegers(1, fetchTokenHandlerCalls,
                      @"Custom mutator should be invoked exactly once");

  [session finishTasksAndInvalidate];
  (void)service;
}

// Regression guard: verifies that fetchWithApproov rejects non-HTTP URLs
// (e.g. file://) with a clear error code rather than crashing or silently
// proceeding with a nil host.
static void TestFetchWithApproovRejectsInvalidURLs(void) {
  ApproovService *service = FreshService();
  dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
  __block BOOL resolved = NO;
  __block NSString *rejectCode = nil;
  __block NSString *rejectMessage = nil;

  [service fetchWithApproov:@"file:///tmp/no-host"
                    options:@{}
                   resolver:^(id result) {
                     (void)result;
                     resolved = YES;
                     dispatch_semaphore_signal(semaphore);
                   }
                   rejecter:^(NSString *code, NSString *message,
                              NSError *error) {
                     (void)error;
                     rejectCode = code;
                     rejectMessage = message;
                     dispatch_semaphore_signal(semaphore);
                   }];

  long waitResult = dispatch_semaphore_wait(
      semaphore, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));
  AssertEqualIntegers(0, waitResult,
                      @"fetchWithApproov bad URL rejections should complete in time");
  AssertTrue(!resolved,
             @"fetchWithApproov bad URL handling should reject instead of resolve");
  AssertEqualObjects(@"bad_url", rejectCode,
                     @"fetchWithApproov should reject invalid URLs with bad_url");
  AssertTrue([rejectMessage containsString:@"invalid URL supplied to fetchWithApproov"],
             @"fetchWithApproov should provide a descriptive invalid URL error");
}

@interface CaptureProtocol : NSURLProtocol
@end

@implementation CaptureProtocol
+ (BOOL)canInitWithRequest:(NSURLRequest *)request {
  return [request.URL.host isEqualToString:@"example.com"];
}
+ (NSURLRequest *)canonicalRequestForRequest:(NSURLRequest *)request {
  return request;
}
- (void)startLoading {
  [ApproovMockURLProtocol setLastRequest:self.request];
  NSHTTPURLResponse *response = [[NSHTTPURLResponse alloc] initWithURL:self.request.URL statusCode:200 HTTPVersion:@"HTTP/1.1" headerFields:@{}];
  [self.client URLProtocol:self didReceiveResponse:response cacheStoragePolicy:NSURLCacheStorageNotAllowed];
  [self.client URLProtocolDidFinishLoading:self];
}
- (void)stopLoading {}
@end

// Regression guard: verifies that useApproovStatusIfNoToken works through the
// swizzled NSURLSession path (not just fetchWithApproov). When a MITM is
// detected and the mutator allows proceeding, the outgoing request must carry
// "Bearer MITM_DETECTED" in the Approov-Token header.
static void TestNSURLSessionExposesStatusHeaderWhenTokenMissingAndAllowed(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;
  approovTokenPrefix = @"Bearer ";
  useApproovStatusIfNoToken = YES;
  [ApproovMockURLProtocol setLastRequest:nil];
  [NSURLProtocol registerClass:[CaptureProtocol class]];

  ApproovTestEnqueueTokenResult(
      Result(ApproovTokenFetchStatusMITMDetected, @"", @"", @"", NO));
  ApproovTestEnqueueTokenResult(
      Result(ApproovTokenFetchStatusMITMDetected, @"", @"", @"", NO));
  ApproovMutatorBridgeSetFetchTokenHandler(^BOOL(id result, NSString *url,
                                                NSError **errorPointer) {
    (void)result;
    (void)url;
    (void)errorPointer;
    return YES;
  });

  NSURL *url = [NSURL URLWithString:@"https://example.com/data"];
  NSURLRequest *request = [NSURLRequest requestWithURL:url];
  RCTTestNetworkDelegate *delegate = [[RCTTestNetworkDelegate alloc] init];
  NSURLSessionConfiguration *config = [NSURLSessionConfiguration ephemeralSessionConfiguration];
  config.protocolClasses = @[[CaptureProtocol class]];
  NSURLSession *session = [NSURLSession sessionWithConfiguration:config
                                                        delegate:delegate
                                                   delegateQueue:nil];

  NSURLSessionDataTask *task = [session dataTaskWithRequest:request];
  [task resume];

  long waitResult = dispatch_semaphore_wait(
      delegate.semaphore, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));
  AssertEqualIntegers(0, waitResult, @"Request should complete in time");

  NSURLRequest *lastRequest = [ApproovMockURLProtocol lastRequest];
  AssertTrue(lastRequest != nil, @"Mock protocol should have captured the request");
  AssertEqualObjects(@"Bearer MITM_DETECTED",
                     [lastRequest valueForHTTPHeaderField:@"Approov-Token"],
                     @"Status header should be present in the outgoing request");
  
  [NSURLProtocol unregisterClass:[CaptureProtocol class]];
}

int main(void) {
  @autoreleasepool {
    NSArray<void (^)(void)> *tests = @[
      ^{ TestInitializeFailureRejectsAndKeepsLayerUninitialized(); },
      ^{ TestInitializeTreatsFalseNilErrorAsAlreadyInitialized(); },
      ^{ TestInitializeRejectsNativeDifferentConfigurationError(); },
      ^{ TestInitializeWithEmptyConfigThenSdkFailurePreservesBootstrap(); },
      ^{ TestMockStatusCompletionHandlersFire(); },
      ^{ TestMockErrorCompletionHandlersFire(); },
      ^{ TestMockUploadCompletionHandlersFire(); },
      ^{ TestReactFetchStyleDataTaskWithURLReturnsSyntheticResponse(); },
      ^{ TestReactFetchStylePoorNetworkReturnsSyntheticResponseWithoutRecursion(); },
      ^{ TestFetchWithApproovRejectsInvalidURLs(); },
      ^{ TestNSURLSessionExposesStatusHeaderWhenTokenMissingAndAllowed(); },
    ];

    for (void (^testBlock)(void) in tests) {
      testBlock();
    }

    if (gFailureCount > 0) {
      NSLog(@"%lu iOS native test(s) failed", (unsigned long)gFailureCount);
      return 1;
    }

    NSLog(@"All iOS native tests passed");
    return 0;
  }
}
