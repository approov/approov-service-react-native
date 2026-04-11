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

static void TestInterceptRequestFailsOnBadURL(void) {
  ApproovService *service = FreshService();
  NSURL *url = [NSURL URLWithString:@"file:///tmp/no-host"];
  NSURLRequest *request = [NSURLRequest requestWithURL:url];

  ApproovInterceptorResult *result = [service interceptRequest:request];

  AssertEqualIntegers(ApproovInterceptorActionFail, result.action,
                      @"Bad URLs should fail");
  AssertEqualObjects(@"BAD_URL", result.message,
                     @"Bad URL message should use the Approov status string");
}

static void TestInterceptRequestForwardsLocalhost(void) {
  ApproovService *service = FreshService();
  NSURLRequest *request =
      [NSURLRequest requestWithURL:[NSURL URLWithString:@"https://localhost/health"]];

  ApproovInterceptorResult *result = [service interceptRequest:request];

  AssertEqualIntegers(ApproovInterceptorActionProceed, result.action,
                      @"localhost should proceed");
  AssertEqualObjects(@"localhost forwarded", result.message,
                     @"localhost should be forwarded unchanged");
}

static void TestInterceptRequestForwardsWhenUninitialized(void) {
  ApproovService *service = FreshService();
  earliestNetworkRequestTime = [[NSDate date] timeIntervalSince1970] - 1.0;
  NSURLRequest *request =
      [NSURLRequest requestWithURL:[NSURL URLWithString:@"https://example.com/data"]];

  ApproovInterceptorResult *result = [service interceptRequest:request];

  AssertEqualIntegers(ApproovInterceptorActionProceed, result.action,
                      @"Expired startup window should forward uninitialized requests");
  AssertEqualObjects(@"uninitalized forwarded", result.message,
                     @"Uninitialized path should forward without mutation");
}

static void TestInitializeWithEmptyConfigForwardsWithoutApproov(void) {
  ApproovService *service = FreshService();
  __block BOOL didResolve = NO;
  __block NSString *rejectionCode = nil;

  [service initialize:@""
              comment:nil
             resolver:^(__unused id value) {
               didResolve = YES;
             }
             rejecter:^(NSString *code, __unused NSString *message,
                        __unused NSError *error) {
               rejectionCode = code;
             }];

  AssertTrue(didResolve, @"Empty config initialization should resolve");
  AssertEqualObjects(nil, rejectionCode,
                     @"Empty config initialization should not reject");
  AssertTrue(isInitialized,
             @"Empty config initialization should still mark the layer initialized");

  NSURLRequest *request =
      [NSURLRequest requestWithURL:[NSURL URLWithString:@"https://example.com/data"]];
  ApproovInterceptorResult *result = [service interceptRequest:request];

  AssertEqualIntegers(ApproovInterceptorActionProceed, result.action,
                      @"Empty config should forward requests unchanged");
  AssertEqualObjects(@"approov disabled forwarded", result.message,
                     @"Empty config should bypass Approov processing");
  AssertEqualIntegers(0, (NSInteger)ApproovTestFetchApproovTokenCallCount(),
                      @"Empty config should not fetch Approov tokens");
}

static void TestInitializeIgnoresSameConfig(void) {
  ApproovService *service = FreshService();
  __block NSInteger resolveCount = 0;
  __block NSString *rejectionCode = nil;

  [service initialize:@"test-config"
              comment:nil
             resolver:^(__unused id value) {
               resolveCount += 1;
             }
             rejecter:^(NSString *code, __unused NSString *message,
                        __unused NSError *error) {
               rejectionCode = code;
             }];
  [service initialize:@"test-config"
              comment:nil
             resolver:^(__unused id value) {
               resolveCount += 1;
             }
             rejecter:^(NSString *code, __unused NSString *message,
                        __unused NSError *error) {
               rejectionCode = code;
             }];

  AssertEqualIntegers(2, resolveCount,
                      @"Same-config reinitialization should resolve both calls");
  AssertEqualObjects(nil, rejectionCode,
                     @"Same-config reinitialization should not reject");
  AssertTrue(isInitialized,
             @"Same-config reinitialization should keep the layer initialized");
}

static void TestInitializeRejectsDifferentConfig(void) {
  ApproovService *service = FreshService();
  __block BOOL firstResolved = NO;
  __block NSString *rejectionCode = nil;
  __block NSString *rejectionMessage = nil;

  [service initialize:@"test-config"
              comment:nil
             resolver:^(__unused id value) {
               firstResolved = YES;
             }
             rejecter:^(__unused NSString *code, __unused NSString *message,
                        __unused NSError *error) {
             }];
  [service initialize:@"different-config"
              comment:nil
             resolver:^(__unused id value) {
             }
             rejecter:^(NSString *code, NSString *message,
                        __unused NSError *error) {
               rejectionCode = code;
               rejectionMessage = message;
             }];

  AssertTrue(firstResolved, @"Initial initialization should resolve");
  AssertEqualObjects(@"initialize", rejectionCode,
                     @"Different-config reinitialization should reject with initialize");
  AssertEqualObjects(@"attempt to reinitialize Approov SDK with a different config",
                     rejectionMessage,
                     @"Different-config reinitialization should explain the mismatch");
  AssertTrue(isInitialized,
             @"Different-config reinitialization should keep the existing initialized state");
}

static void TestInitializeAllowsReinitCommentWithDifferentConfig(void) {
  ApproovService *service = FreshService();
  __block BOOL secondResolved = NO;
  __block NSString *rejectionCode = nil;

  [service initialize:@"test-config"
              comment:nil
             resolver:^(__unused id value) {
             }
             rejecter:^(__unused NSString *code, __unused NSString *message,
                        __unused NSError *error) {
             }];
  [service initialize:@"different-config"
              comment:@"reinit:account-switch"
             resolver:^(__unused id value) {
               secondResolved = YES;
             }
             rejecter:^(NSString *code, __unused NSString *message,
                        __unused NSError *error) {
               rejectionCode = code;
             }];

  AssertTrue(secondResolved, @"Reinit comment should allow reinitialization");
  AssertEqualObjects(nil, rejectionCode, @"Reinit comment should not reject");
  AssertTrue(isInitialized, @"Reinit comment should leave the layer initialized");
}

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

static void TestInitializeIgnoresNativeAlreadyInitializedError(void) {
  ApproovService *service = FreshService();
  NSError *alreadyInitializedError =
      [NSError errorWithDomain:@"Foundation._GenericObjCError"
                          code:0
                      userInfo:nil];
  ApproovTestSetInitializationError(alreadyInitializedError);

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
  ApproovTestClearInitializationError();

  AssertTrue(didResolve,
             @"Already-initialized native SDK error should resolve");
  AssertEqualObjects(nil, rejectionCode,
                     @"Already-initialized native SDK error should not reject");
  AssertTrue(isInitialized,
             @"Already-initialized native SDK error should still mark the layer initialized");
  AssertEqualObjects(@"test-config", initialConfigString,
                     @"Successful guarded initialization should store the config");
}

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

static void TestStatusMethodsDifferentiateInitializedAndEnabled(void) {
  ApproovService *service = FreshService();
  __block NSNumber *initializedBefore = nil;
  __block NSNumber *enabledBefore = nil;
  __block NSNumber *initializedAfter = nil;
  __block NSNumber *enabledAfter = nil;

  [service isInitialized:^(id value) {
    initializedBefore = value;
  }
             rejecter:^(__unused NSString *code, __unused NSString *message,
                        __unused NSError *error) {
             }];
  [service isApproovEnabled:^(id value) {
    enabledBefore = value;
  }
                rejecter:^(__unused NSString *code, __unused NSString *message,
                           __unused NSError *error) {
                }];

  [service initialize:@""
              comment:nil
             resolver:^(__unused id value) {
             }
             rejecter:^(__unused NSString *code, __unused NSString *message,
                        __unused NSError *error) {
             }];

  [service isInitialized:^(id value) {
    initializedAfter = value;
  }
             rejecter:^(__unused NSString *code, __unused NSString *message,
                        __unused NSError *error) {
             }];
  [service isApproovEnabled:^(id value) {
    enabledAfter = value;
  }
                rejecter:^(__unused NSString *code, __unused NSString *message,
                           __unused NSError *error) {
                }];

  AssertEqualObjects(@NO, initializedBefore,
                     @"Service should report uninitialized before initialize");
  AssertEqualObjects(@NO, enabledBefore,
                     @"Approov should report disabled before initialize");
  AssertEqualObjects(@YES, initializedAfter,
                     @"Empty-config initialize should still mark the layer initialized");
  AssertEqualObjects(@NO, enabledAfter,
                     @"Empty-config initialize should keep Approov disabled");
}

static void TestInterceptRequestAddsTokenTraceAndFetchesConfig(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;
  approovTokenPrefix = @"Bearer ";

  ApproovTestEnqueueTokenResult(Result(ApproovTokenFetchStatusSuccess,
                                       @"jwt-token", @"", @"trace-123", YES));

  NSMutableURLRequest *request = MutableRequest(@"https://example.com/data");
  ApproovInterceptorResult *result = [service interceptRequest:request];

  AssertEqualIntegers(ApproovInterceptorActionProceed, result.action,
                      @"Successful fetch should proceed");
  AssertEqualObjects(@"Bearer jwt-token",
                     [result.request valueForHTTPHeaderField:@"Approov-Token"],
                     @"Success path should add the Approov token");
  AssertEqualObjects(@"trace-123",
                     [result.request valueForHTTPHeaderField:@"Approov-TraceID"],
                     @"Success path should add the trace header");
  AssertEqualIntegers(1, (NSInteger)ApproovTestFetchConfigCallCount(),
                      @"Config changes should trigger a fetchConfig refresh");
}

static void TestInterceptRequestSuccessWithEmptyTokenOmitsEmptyHeaders(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;

  ApproovTestEnqueueTokenResult(Result(ApproovTokenFetchStatusSuccess,
                                       @"", @"", @"", NO));

  NSMutableURLRequest *request = MutableRequest(@"https://example.com/data");
  ApproovInterceptorResult *result = [service interceptRequest:request];

  AssertEqualIntegers(ApproovInterceptorActionProceed, result.action,
                      @"Empty-token success should still proceed");
  AssertEqualObjects(nil,
                     [result.request valueForHTTPHeaderField:@"Approov-Token"],
                     @"Empty-token success should omit the token header");
  AssertEqualObjects(nil,
                     [result.request valueForHTTPHeaderField:@"Approov-TraceID"],
                     @"Empty-token success should omit the trace header");
}

static void TestInterceptRequestCanProceedOnMitmWithStatusHeader(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;
  approovTokenPrefix = @"Bearer ";
  useApproovStatusIfNoToken = YES;

  ApproovTestEnqueueTokenResult(
      Result(ApproovTokenFetchStatusMITMDetected, @"", @"", @"", NO));
  ApproovMutatorBridgeSetFetchTokenHandler(^BOOL(id result, NSString *url,
                                                NSError **errorPointer) {
    (void)result;
    (void)url;
    (void)errorPointer;
    return YES;
  });

  ApproovInterceptorResult *result =
      [service interceptRequest:[NSURLRequest requestWithURL:[NSURL URLWithString:
                                                                 @"https://example.com/data"]]];

  AssertEqualIntegers(ApproovInterceptorActionProceed, result.action,
                      @"Custom mutator should be able to proceed on MITM");
  AssertEqualObjects(@"Bearer MITM_DETECTED",
                     [result.request valueForHTTPHeaderField:@"Approov-Token"],
                     @"Proceeding failure states should expose the status header");
}

static void TestInterceptRequestDefaultMutatorFailsClosedOnMitm(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;

  ApproovTestEnqueueTokenResult(
      Result(ApproovTokenFetchStatusMITMDetected, @"", @"", @"", NO));

  ApproovInterceptorResult *result =
      [service interceptRequest:[NSURLRequest requestWithURL:[NSURL URLWithString:
                                                                 @"https://example.com/data"]]];

  AssertEqualIntegers(ApproovInterceptorActionRetry, result.action,
                      @"Default mutator should fail closed on MITM by blocking the request");
  AssertEqualObjects(@"MITM_DETECTED", result.message,
                     @"Fail-closed MITM behavior should surface the status");
}

static void TestInterceptRequestRetriesOnNetworkFailure(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;

  ApproovTestEnqueueTokenResult(
      Result(ApproovTokenFetchStatusNoNetwork, @"", @"", @"", NO));

  ApproovInterceptorResult *result =
      [service interceptRequest:[NSURLRequest requestWithURL:[NSURL URLWithString:
                                                                 @"https://example.com/data"]]];

  AssertEqualIntegers(ApproovInterceptorActionRetry, result.action,
                      @"No-network fetches should recommend retry");
  AssertEqualObjects(@"NO_NETWORK", result.message,
                     @"Retry path should expose the network status");
}

static void TestInterceptRequestDefaultsNoApproovServiceToProceed(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;

  ApproovTestEnqueueTokenResult(
      Result(ApproovTokenFetchStatusNoApproovService, @"", @"", @"", NO));

  ApproovInterceptorResult *result =
      [service interceptRequest:[NSURLRequest requestWithURL:[NSURL URLWithString:
                                                                 @"https://example.com/data"]]];

  AssertEqualIntegers(ApproovInterceptorActionProceed, result.action,
                      @"NO_APPROOV_SERVICE should proceed by default");
  AssertEqualObjects(@"NO_APPROOV_SERVICE", result.message,
                     @"Proceed path should surface the service status");
}

static void TestInterceptRequestHonorsCustomNoApproovServiceBlocks(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;

  ApproovTestEnqueueTokenResult(
      Result(ApproovTokenFetchStatusNoApproovService, @"", @"", @"", NO));
  ApproovMutatorBridgeSetFetchTokenHandler(^BOOL(id result, NSString *url,
                                                NSError **errorPointer) {
    (void)result;
    (void)url;
    if (errorPointer != NULL) {
      *errorPointer = [NSError errorWithDomain:@"io.approov.reactnative.tests"
                                          code:1
                                      userInfo:@{
                                        NSLocalizedDescriptionKey :
                                            @"custom no service block"
                                      }];
    }
    return NO;
  });

  ApproovInterceptorResult *result =
      [service interceptRequest:[NSURLRequest requestWithURL:[NSURL URLWithString:
                                                                 @"https://example.com/data"]]];

  AssertEqualIntegers(ApproovInterceptorActionFail, result.action,
                      @"Custom mutators should be able to block NO_APPROOV_SERVICE");
  AssertEqualObjects(@"custom no service block", result.message,
                     @"Custom mutator failures should be surfaced");
}

static void TestHeaderSubstitutionNetworkFailureRetries(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;
  substitutionHeaders[@"Api-Key"] = @"Bearer ";

  ApproovTestEnqueueTokenResult(Result(ApproovTokenFetchStatusSuccess,
                                       @"jwt-token", @"", @"", NO));
  ApproovTestEnqueueSecureStringResult(
      Result(ApproovTokenFetchStatusNoNetwork, @"", @"", @"", NO));

  NSMutableURLRequest *request = MutableRequest(@"https://example.com/data");
  [request setValue:@"Bearer live-secret" forHTTPHeaderField:@"Api-Key"];

  ApproovInterceptorResult *result = [service interceptRequest:request];

  AssertEqualIntegers(ApproovInterceptorActionRetry, result.action,
                      @"Header substitution network failures should retry");
  AssertTrue([result.message containsString:@"Header substitution network error"],
             @"Header substitution retry should include a diagnostic message");
}

static void TestQueryParameterSubstitutionUpdatesTheURL(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;
  [substitutionQueryParams addObject:@"secret"];

  ApproovTestEnqueueTokenResult(Result(ApproovTokenFetchStatusSuccess,
                                       @"jwt-token", @"", @"", NO));
  ApproovTestEnqueueSecureStringResult(Result(ApproovTokenFetchStatusSuccess,
                                              @"", @"live-query-secret", @"",
                                              NO));

  NSURLRequest *request =
      [NSURLRequest requestWithURL:[NSURL URLWithString:@"https://example.com/data?secret=query-secret"]];
  ApproovInterceptorResult *result = [service interceptRequest:request];

  AssertEqualIntegers(ApproovInterceptorActionProceed, result.action,
                      @"Successful query substitution should proceed");
  AssertEqualObjects(@"https://example.com/data?secret=live-query-secret",
                     result.request.URL.absoluteString,
                     @"Query substitution should update the request URL");
}

static NSURLSession *MockSession(void) {
  NSURLSessionConfiguration *configuration =
      [NSURLSessionConfiguration ephemeralSessionConfiguration];
  configuration.protocolClasses = @[ [ApproovMockURLProtocol class] ];
  return [NSURLSession sessionWithConfiguration:configuration];
}

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

static void TestNSURLSessionExposesStatusHeaderWhenTokenMissingAndAllowed(void) {
  ApproovService *service = FreshService();
  isInitialized = YES;
  approovTokenPrefix = @"Bearer ";
  useApproovStatusIfNoToken = YES;
  [ApproovMockURLProtocol setLastRequest:nil];
  [NSURLProtocol registerClass:[CaptureProtocol class]];

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
  NSURLSessionConfiguration *config = [NSURLSessionConfiguration defaultSessionConfiguration];
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
      ^{ TestInterceptRequestFailsOnBadURL(); },
      ^{ TestInterceptRequestForwardsLocalhost(); },
      ^{ TestInterceptRequestForwardsWhenUninitialized(); },
      ^{ TestInitializeWithEmptyConfigForwardsWithoutApproov(); },
      ^{ TestInitializeIgnoresSameConfig(); },
      ^{ TestInitializeRejectsDifferentConfig(); },
      ^{ TestInitializeAllowsReinitCommentWithDifferentConfig(); },
      ^{ TestInitializeFailureRejectsAndKeepsLayerUninitialized(); },
      ^{ TestInitializeIgnoresNativeAlreadyInitializedError(); },
      ^{ TestInitializeRejectsNativeDifferentConfigurationError(); },
      ^{ TestStatusMethodsDifferentiateInitializedAndEnabled(); },
      ^{ TestInterceptRequestAddsTokenTraceAndFetchesConfig(); },
      ^{ TestInterceptRequestSuccessWithEmptyTokenOmitsEmptyHeaders(); },
      ^{ TestInterceptRequestDefaultMutatorFailsClosedOnMitm(); },
      ^{ TestInterceptRequestCanProceedOnMitmWithStatusHeader(); },
      ^{ TestInterceptRequestRetriesOnNetworkFailure(); },
      ^{ TestInterceptRequestDefaultsNoApproovServiceToProceed(); },
      ^{ TestInterceptRequestHonorsCustomNoApproovServiceBlocks(); },
      ^{ TestHeaderSubstitutionNetworkFailureRetries(); },
      ^{ TestQueryParameterSubstitutionUpdatesTheURL(); },
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
