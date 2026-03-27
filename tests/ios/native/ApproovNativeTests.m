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

int main(void) {
  @autoreleasepool {
    NSArray<void (^)(void)> *tests = @[
      ^{ TestInterceptRequestFailsOnBadURL(); },
      ^{ TestInterceptRequestForwardsLocalhost(); },
      ^{ TestInterceptRequestForwardsWhenUninitialized(); },
      ^{ TestInterceptRequestAddsTokenTraceAndFetchesConfig(); },
      ^{ TestInterceptRequestCanProceedOnMitmWithStatusHeader(); },
      ^{ TestInterceptRequestRetriesOnNetworkFailure(); },
      ^{ TestInterceptRequestDefaultsNoApproovServiceToProceed(); },
      ^{ TestHeaderSubstitutionNetworkFailureRetries(); },
      ^{ TestQueryParameterSubstitutionUpdatesTheURL(); },
      ^{ TestMockStatusCompletionHandlersFire(); },
      ^{ TestMockErrorCompletionHandlersFire(); },
      ^{ TestMockUploadCompletionHandlersFire(); },
      ^{ TestReactFetchStylePoorNetworkReturnsSyntheticResponseWithoutRecursion(); },
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
