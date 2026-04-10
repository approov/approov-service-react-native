#import <Foundation/Foundation.h>
#import <CommonCrypto/CommonDigest.h>

#import "Approov.h"
#import "MiniSDKTestSupport.h"
#import "approov_service_react_native-Swift.h"
#import "ios/ApproovPinningDelegate.h"
#import "ios/ApproovRCTInterceptor.h"
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

@interface ApproovService (MiniSDKNativeTests)
- (void)initialize:(NSString *)config
          resolver:(RCTPromiseResolveBlock)resolve
          rejecter:(RCTPromiseRejectBlock)reject;
- (void)precheck:(RCTPromiseResolveBlock)resolve
        rejecter:(RCTPromiseRejectBlock)reject;
- (void)getDeviceID:(RCTPromiseResolveBlock)resolve
           rejecter:(RCTPromiseRejectBlock)reject;
- (void)getLastARC:(RCTPromiseResolveBlock)resolve
          rejecter:(RCTPromiseRejectBlock)reject;
- (void)fetchToken:(NSString *)url
          resolver:(RCTPromiseResolveBlock)resolve
          rejecter:(RCTPromiseRejectBlock)reject;
- (void)fetchSecureString:(NSString *)key
                   newDef:(NSString *)newDef
                 resolver:(RCTPromiseResolveBlock)resolve
                 rejecter:(RCTPromiseRejectBlock)reject;
- (void)fetchCustomJWT:(NSString *)payload
              resolver:(RCTPromiseResolveBlock)resolve
              rejecter:(RCTPromiseRejectBlock)reject;
- (void)fetchWithApproov:(NSString *)url
                  options:(NSDictionary *)options
                 resolver:(RCTPromiseResolveBlock)resolve
                 rejecter:(RCTPromiseRejectBlock)reject;
- (void)setDataHashInToken:(NSString *)data
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject;
- (void)getPinningDiagnostics:(RCTPromiseResolveBlock)resolve
                     rejecter:(RCTPromiseRejectBlock)reject;
- (ApproovTrustDecision)verifyPins:(SecTrustRef)serverTrust
                           forHost:(NSString *)host;
- (void)setTokenHeader:(NSString *)header prefix:(NSString *)prefix;
- (void)setTraceIDHeader:(NSString *)header;
- (void)setBindingHeader:(NSString *)header;
- (void)removeSubstitutionHeader:(NSString *)header;
- (void)removeSubstitutionQueryParam:(NSString *)key;
- (void)removeExclusionURLRegex:(NSString *)urlRegex;
- (void)addSubstitutionHeader:(NSString *)header requiredPrefix:(NSString *)requiredPrefix;
- (void)addSubstitutionQueryParam:(NSString *)key;
- (void)addExclusionURLRegex:(NSString *)urlRegex;
@end

static NSUInteger gFailureCount = 0;
static NSString *const kValidInitialConfig = @"#cb-ivol#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";

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
  Fail([NSString stringWithFormat:@"%@ (expected %@, got %@)", message, expected, actual]);
}

static void AssertNotNil(id value, NSString *message) {
  if (value == nil) {
    Fail(message);
  }
}

static void AssertNil(id value, NSString *message) {
  if (value != nil) {
    Fail([NSString stringWithFormat:@"%@ (got %@)", message, value]);
  }
}

static NSString *TargetURL(void) {
  NSString *url = NSProcessInfo.processInfo.environment[@"TESTING_REPLY_URL"];
  return url ?: @"https://replay.ivol.workers.dev";
}

static NSString *UnprotectedURL(void) {
  NSString *url = NSProcessInfo.processInfo.environment[@"TESTING_REPLY_URL_UNPROTECTED"];
  return url ?: @"https://replay-unprotected.ivol.workers.dev";
}

static NSString *TargetHost(void) {
  return [NSURL URLWithString:TargetURL()].host;
}

static void ResetSharedState(void) {
  isInitialized = NO;
  earliestNetworkRequestTime = 0;
  useApproovStatusIfNoToken = NO;
  suppressLoggingUnknownURL = NO;
  initialConfigString = @"test-config";
  approovTokenHeader = @"Approov-Token";
  approovTraceIDHeader = @"Approov-TraceID";
  approovTokenPrefix = @"";
  bindingHeader = @"";
  substitutionHeaders = [[NSMutableDictionary alloc] init];
  substitutionQueryParams = [[NSMutableSet alloc] init];
  exclusionURLRegexs = [[NSMutableSet alloc] init];
  [MiniSDKAttesterProxyController reset];
  [MiniSDKAttesterProxyController loadTokenSigningConfigFile:@"../core-service-layers-testing/mini-sdk/attester-proxy/token-signing-config.json"];
}

static NSString *ScenarioJSON(NSString *caseName, NSString *body) {
  return [NSString stringWithFormat:
      @"{\"activeCase\":\"%@\",\"cases\":{\"%@\":{%@}}}",
      caseName, caseName, body];
}

static NSString *UniqueCaseName(NSString *prefix) {
  return [NSString stringWithFormat:@"%@-%@", prefix, [[NSUUID UUID] UUIDString].lowercaseString];
}

static void LoadProtectedDomainScenario(NSString *extraBody) {
  NSString *body = [NSString stringWithFormat:@"\"protectedDomains\":[\"%@\"]", TargetHost()];
  if (extraBody != nil && extraBody.length > 0) {
    body = [body stringByAppendingFormat:@",%@", extraBody];
  }
  [MiniSDKAttesterProxyController loadScenarioJSON:ScenarioJSON(UniqueCaseName(@"rn-ios"), body)];
}

static NSDictionary *DecodeJWTBody(NSString *jwt) {
  NSArray<NSString *> *parts = [jwt componentsSeparatedByString:@"."];
  if (parts.count != 3) return nil;
  NSString *payload = parts[1];
  NSUInteger pad = (4 - payload.length % 4) % 4;
  payload = [[payload stringByReplacingOccurrencesOfString:@"-" withString:@"+"]
      stringByReplacingOccurrencesOfString:@"_" withString:@"/"];
  if (pad > 0) {
    payload = [payload stringByAppendingString:[@"" stringByPaddingToLength:pad withString:@"=" startingAtIndex:0]];
  }
  NSData *data = [[NSData alloc] initWithBase64EncodedString:payload options:0];
  if (data == nil) return nil;
  return [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
}

static NSString *SHA256Base64(NSString *value) {
  NSData *data = [value dataUsingEncoding:NSUTF8StringEncoding];
  uint8_t digest[CC_SHA256_DIGEST_LENGTH];
  CC_SHA256(data.bytes, (CC_LONG)data.length, digest);
  return [[NSData dataWithBytes:digest length:sizeof(digest)] base64EncodedStringWithOptions:0];
}

static NSDictionary *AwaitPromise(void (^work)(RCTPromiseResolveBlock, RCTPromiseRejectBlock)) {
  dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
  __block id resolvedValue = nil;
  __block NSString *rejectedCode = nil;
  __block NSString *rejectedMessage = nil;

  work(^(id value) {
    resolvedValue = value;
    dispatch_semaphore_signal(semaphore);
  }, ^(NSString *code, NSString *message, NSError *error) {
    (void)error;
    rejectedCode = code;
    rejectedMessage = message;
    dispatch_semaphore_signal(semaphore);
  });

  dispatch_time_t timeout = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(5 * NSEC_PER_SEC));
  if (dispatch_semaphore_wait(semaphore, timeout) != 0) {
    Fail(@"Timed out waiting for promise");
  }

  return @{
    @"value": resolvedValue ?: [NSNull null],
    @"code": rejectedCode ?: [NSNull null],
    @"message": rejectedMessage ?: [NSNull null],
  };
}

static NSDictionary *AwaitResolved(void (^work)(RCTPromiseResolveBlock, RCTPromiseRejectBlock)) {
  NSDictionary *result = AwaitPromise(work);
  if (result[@"code"] != [NSNull null]) {
    Fail([NSString stringWithFormat:@"Expected resolve, got %@ %@", result[@"code"], result[@"message"]]);
  }
  return result;
}

static NSDictionary *AwaitRejected(void (^work)(RCTPromiseResolveBlock, RCTPromiseRejectBlock)) {
  NSDictionary *result = AwaitPromise(work);
  if (result[@"code"] == [NSNull null]) {
    Fail(@"Expected rejection, but promise resolved");
  }
  return result;
}

static ApproovService *FreshService(void) {
  ResetSharedState();
  return [[ApproovService alloc] init];
}

static void InitializeService(ApproovService *service, NSString *comment) {
  NSDictionary *result = AwaitResolved(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service initialize:kValidInitialConfig resolver:resolve rejecter:reject];
  });
  (void)result;
  (void)comment;
}

static NSDictionary *FetchNetworkReply(ApproovService *service, NSString *url, NSDictionary *options) {
  NSDictionary *result = AwaitResolved(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service fetchWithApproov:url options:options ?: @{} resolver:resolve rejecter:reject];
  });
  NSDictionary *response = result[@"value"];
  if (response == nil || (id)response == [NSNull null]) {
    Fail([NSString stringWithFormat:@"Expected resolve with value, got code=%@ msg=%@", result[@"code"], result[@"message"]]);
    return nil;
  }
  NSString *body = response[@"body"];
  if (body == nil || (id)body == [NSNull null] || body.length == 0) {
    Fail(@"Expected fetchWithApproov to return a non-empty body");
    return nil;
  }
  NSData *data = [body dataUsingEncoding:NSUTF8StringEncoding];
  NSError *error = nil;
  NSDictionary *reply = [NSJSONSerialization JSONObjectWithData:data options:0 error:&error];
  if (error) {
    Fail([NSString stringWithFormat:@"Failed to parse worker JSON: %@. Body was: %@", error.localizedDescription, body]);
    return nil;
  }
  return reply;
}

static id HeaderValue(NSDictionary *reply, NSString *key) {
  NSDictionary *headers = reply[@"headers"];
  if (![headers isKindOfClass:[NSDictionary class]]) return nil;
  id value = headers[key.lowercaseString];
  if (value == nil) value = headers[key];
  if ([value isKindOfClass:[NSArray class]]) {
    return [value count] > 0 ? value[0] : nil;
  }
  return value;
}

static NSDictionary *PerformPinnedRequest(ApproovService *service,
                                          NSString *url,
                                          ApproovTrustDecision *decisionOut,
                                          NSError **errorOut) {
  dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
  __block NSDictionary *reply = nil;
  __block NSError *taskError = nil;
  __block ApproovTrustDecision decision = ApproovTrustDecisionNotPinned;

  PinningURLSessionDelegate *pinningDelegate =
      [[PinningURLSessionDelegate alloc] initWithDelegate:nil approovService:service];
  pinningDelegate.authChallengeCallback = ^(NSString *host, ApproovTrustDecision callbackDecision) {
    (void)host;
    decision = callbackDecision;
  };

  NSURLSession *session = [NSURLSession sessionWithConfiguration:[NSURLSessionConfiguration defaultSessionConfiguration]
                                                        delegate:pinningDelegate
                                                   delegateQueue:nil];
  NSURLRequest *request = [NSURLRequest requestWithURL:[NSURL URLWithString:url]];
  NSURLSessionDataTask *task =
      [session dataTaskWithRequest:request
                 completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
                   (void)response;
                   taskError = error;
                   if (data != nil && error == nil) {
                     reply = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
                   }
                   [session finishTasksAndInvalidate];
                   dispatch_semaphore_signal(semaphore);
                 }];
  [task resume];

  dispatch_time_t timeout = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(10 * NSEC_PER_SEC));
  if (dispatch_semaphore_wait(semaphore, timeout) != 0) {
    Fail(@"Timed out waiting for pinned request");
  }

  if (decisionOut != NULL) {
    *decisionOut = decision;
  }
  if (errorOut != NULL) {
    *errorOut = taskError;
  }
  return reply;
}

static void TestInitializeWithEmptyConfigForwardsWithoutApproov(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  AwaitResolved(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service initialize:@"" resolver:resolve rejecter:reject];
  });

  AssertTrue(isInitialized, @"Empty config should still initialize the layer");
  NSDictionary *pins = [Approov getPins:@"public-key-sha256"];
  AssertTrue(pins == nil || [pins count] == 0,
             @"Empty config should leave pinning disabled");
  NSDictionary *reply = FetchNetworkReply(service, TargetURL(), @{});
  AssertNil(HeaderValue(reply, @"Approov-Token"), @"Empty config should not add tokens");
  AssertNil(HeaderValue(reply, @"Approov-TraceID"), @"Empty config should not add trace IDs");
}

static void TestGetDeviceIDReturnsMiniSDKDeviceID(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");
  NSDictionary *result = AwaitResolved(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service getDeviceID:resolve rejecter:reject];
  });
  AssertEqualObjects(@"daIvmEWBA2gvZny7a/RC/w==", result[@"value"], @"Mini SDK device ID should match");
}

static void TestGetPinningDiagnosticsReturnsExpectedShape(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  [ApproovRCTInterceptor startWithApproovService:service];
  NSDictionary *result = AwaitResolved(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service getPinningDiagnostics:resolve rejecter:reject];
  });

  NSDictionary *diagnostics = result[@"value"];
  AssertTrue([diagnostics isKindOfClass:[NSDictionary class]], @"Pinning diagnostics should resolve to a dictionary");
  AssertNotNil(diagnostics[@"sessionsWithPinning"], @"Pinning diagnostics should include sessionsWithPinning");
  AssertNotNil(diagnostics[@"sessionsWithoutPinning"], @"Pinning diagnostics should include sessionsWithoutPinning");
  AssertNotNil(diagnostics[@"unpinnedSessions"], @"Pinning diagnostics should include unpinnedSessions");
}

static void TestFetchWithApproovAddsTokenTraceAndSubstitutions(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(@"\"initialSecureStrings\":{\"header-key\":\"header-secret\",\"query-key\":\"query-secret\"}");
  InitializeService(service, @"reinit");

  [service setBindingHeader:@"Authorization"];
  [service addSubstitutionHeader:@"Api-Key" requiredPrefix:@""];
  [service addSubstitutionQueryParam:@"api_key"];

  NSDictionary *reply = FetchNetworkReply(service,
                                          [NSString stringWithFormat:@"%@?api_key=query-key", TargetURL()],
                                          @{
                                            @"headers": @{
                                                @"Authorization": @"Bearer oauth-token",
                                                @"Api-Key": @"header-key",
                                            }
                                          });
  NSString *token = HeaderValue(reply, @"Approov-Token");
  NSDictionary *payload = DecodeJWTBody([token stringByReplacingOccurrencesOfString:@"Bearer " withString:@""]);

  AssertNotNil(token, @"Protected request should receive a token");
  AssertNotNil(HeaderValue(reply, @"Approov-TraceID"), @"Protected request should receive a trace ID");
  AssertEqualObjects(@"header-secret", HeaderValue(reply, @"Api-Key"), @"Header substitution should occur");
  AssertTrue([reply[@"url"] containsString:@"api_key=query-secret"], @"Query substitution should occur");
  AssertEqualObjects(SHA256Base64(@"Bearer oauth-token"), payload[@"pay"], @"Binding hash should be added to the token");
}

static void TestExcludedProtectedURLLeavesRequestUnmodified(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  [exclusionURLRegexs addObject:@"^.*excluded.*$"];
  NSDictionary *reply = FetchNetworkReply(service,
                                          [NSString stringWithFormat:@"%@/excluded", TargetURL()],
                                          @{});

  AssertNotNil(reply, @"Excluded protected URL should still succeed");
  AssertNil(HeaderValue(reply, @"Approov-Token"), @"Excluded protected URL should not receive a token");
  AssertNil(HeaderValue(reply, @"Approov-TraceID"), @"Excluded protected URL should not receive a trace ID");
  AssertTrue([reply[@"url"] containsString:@"/excluded"], @"Excluded URL path should be forwarded unchanged");
}

static void TestDirectPinningAllowsValidPins(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  ApproovTrustDecision decision = ApproovTrustDecisionNotPinned;
  NSError *error = nil;
  NSDictionary *reply = PerformPinnedRequest(service, TargetURL(), &decision, &error);

  AssertNil(error, @"Valid pins should allow the protected worker request");
  AssertNotNil(reply, @"Valid pins should return a worker reply");
  AssertEqualObjects(@(ApproovTrustDecisionAllow), @(decision), @"Pinning delegate should allow valid pins");
}

static void TestDirectPinningBlocksInvalidPins(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  [MiniSDKAttesterProxyController setNextPinningDirectiveJSON:@"{\"operation\": \"getPins\", \"shouldFail\": true}"];

  ApproovTrustDecision decision = ApproovTrustDecisionNotPinned;
  NSError *error = nil;
  NSDictionary *reply = PerformPinnedRequest(service, TargetURL(), &decision, &error);

  AssertNil(reply, @"Invalid pins should not return a worker reply");
  AssertNotNil(error, @"Invalid pins should fail the protected worker request");
  AssertEqualObjects(@(ApproovTrustDecisionBlock), @(decision), @"Pinning delegate should block invalid pins");
}

static void TestFetchTokenReturnsSignedTokenWithExpectedClaims(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  NSDictionary *result = AwaitResolved(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service fetchToken:TargetURL() resolver:resolve rejecter:reject];
  });
  NSDictionary *payload = DecodeJWTBody(result[@"value"]);

  AssertEqualObjects(@"81.149.55.236", payload[@"ip"], @"Token should contain the mini-sdk IP");
  AssertEqualObjects(@"daIvmEWBA2gvZny7a/RC/w==", payload[@"did"], @"Token should contain the mini-sdk device ID");
  AssertEqualObjects(@"j3AWy6", payload[@"mskid"], @"Token should contain the signing key ID");
  AssertEqualObjects(@"IXPSB7TRK26LXE3M", payload[@"arc"], @"Token should contain the ARC");
}

static void TestFetchSecureStringAndCustomJWTUseMiniSDK(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  [MiniSDKAttesterProxyController setNextAttestationDirectiveJSON:@"{\"operation\":\"fetchSecureString\",\"response\":{\"status\":\"SUCCESS\",\"secureString\":\"mini-secret\"}}"];
  NSDictionary *secure = AwaitResolved(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service fetchSecureString:@"api-key" newDef:nil resolver:resolve rejecter:reject];
  });
  AssertEqualObjects(@"mini-secret", secure[@"value"], @"Secure string should resolve via mini-sdk");

  NSDictionary *jwt = AwaitResolved(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service fetchCustomJWT:@"{\"role\":\"tester\"}" resolver:resolve rejecter:reject];
  });
  NSDictionary *payload = DecodeJWTBody(jwt[@"value"]);
  AssertEqualObjects(@"tester", payload[@"role"], @"Custom JWT payload should be preserved");
}

static void TestFetchWithApproovRejectsInvalidMethods(void) {
  ApproovService *service = FreshService();
  NSDictionary *result = AwaitRejected(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service fetchWithApproov:TargetURL() options:@{@"method": @(YES)} resolver:resolve rejecter:reject];
  });
  AssertEqualObjects(@"bad_request", result[@"code"], @"Should reject bad_request");
  AssertEqualObjects(@"fetchWithApproov method must be a string when provided", result[@"message"], @"Message mismatch");
}

static void TestFetchWithApproovRejectsInvalidHeaders(void) {
  ApproovService *service = FreshService();
  NSDictionary *result = AwaitRejected(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service fetchWithApproov:TargetURL() options:@{@"headers": @(YES)} resolver:resolve rejecter:reject];
  });
  AssertEqualObjects(@"bad_request", result[@"code"], @"Should reject bad_request");
  AssertEqualObjects(@"fetchWithApproov headers must be an object when provided", result[@"message"], @"Message mismatch");
}

static void TestFetchWithApproovRejectsInvalidHeaderNames(void) {
  ApproovService *service = FreshService();
  NSDictionary *result = AwaitRejected(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service fetchWithApproov:TargetURL() options:@{@"headers": @{@(YES): @"value"}} resolver:resolve rejecter:reject];
  });
  AssertEqualObjects(@"bad_request", result[@"code"], @"Should reject bad_request");
  AssertEqualObjects(@"fetchWithApproov header names must be strings", result[@"message"], @"Message mismatch");
}

static void TestFetchWithApproovRejectsInvalidHeaderValues(void) {
  ApproovService *service = FreshService();
  NSDictionary *result = AwaitRejected(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service fetchWithApproov:TargetURL() options:@{@"headers": @{@"X-Header": @(YES)}} resolver:resolve rejecter:reject];
  });
  AssertEqualObjects(@"bad_request", result[@"code"], @"Should reject bad_request");
  AssertEqualObjects(@"fetchWithApproov header values must be strings", result[@"message"], @"Message mismatch");
}

static void TestFetchWithApproovRejectsInvalidBodies(void) {
  ApproovService *service = FreshService();
  NSDictionary *result = AwaitRejected(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service fetchWithApproov:TargetURL() options:@{@"body": @(YES)} resolver:resolve rejecter:reject];
  });
  AssertEqualObjects(@"bad_request", result[@"code"], @"Should reject bad_request");
  AssertEqualObjects(@"fetchWithApproov body must be a string when provided", result[@"message"], @"Message mismatch");
}

static void TestFetchWithApproovAcceptsValidRequests(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  NSDictionary *reply = FetchNetworkReply(service, TargetURL(), @{
    @"method": @"POST",
    @"headers": @{
      @"X-Custom-Header": @"custom-value",
      @"Accept": @"application/json"
    },
    @"body": @"{\"test\":\"payload\"}"
  });

  AssertEqualObjects(@"POST", reply[@"method"], @"Backend should receive POST method");
  AssertEqualObjects(@"custom-value", HeaderValue(reply, @"X-Custom-Header"), @"Backend should receive custom header");
  AssertEqualObjects(@"{\"test\":\"payload\"}", reply[@"body"], @"Backend should receive string body");
}

static void TestFetchWithApproovUsesDefaultHeaders(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  NSDictionary *reply = FetchNetworkReply(service, TargetURL(), @{});
  AssertNotNil(HeaderValue(reply, @"Approov-Token"), @"Should use default token header");
  AssertNotNil(HeaderValue(reply, @"Approov-TraceID"), @"Should use default trace ID header");
}

static void TestFetchWithApproovUsesCustomHeaders(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  [service setTokenHeader:@"Custom-Token" prefix:@"Prefix "];
  [service setTraceIDHeader:@"Custom-TraceID"];

  NSDictionary *reply = FetchNetworkReply(service, TargetURL(), @{});
  NSString *token = HeaderValue(reply, @"Custom-Token");
  NSString *traceID = HeaderValue(reply, @"Custom-TraceID");

  AssertNotNil(token, @"Should use custom token header");
  AssertTrue([token hasPrefix:@"Prefix "], @"Should use custom token prefix");
  AssertNotNil(traceID, @"Should use custom trace ID header");
  AssertNil(HeaderValue(reply, @"Approov-Token"), @"Should NOT use default token header");
  AssertNil(HeaderValue(reply, @"Approov-TraceID"), @"Should NOT use default trace ID header");
}

static void TestFetchWithApproovCanDisableTraceID(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  [service setTraceIDHeader:nil];

  NSDictionary *reply = FetchNetworkReply(service, TargetURL(), @{});
  AssertNotNil(HeaderValue(reply, @"Approov-Token"), @"Should still have token");
  AssertNil(HeaderValue(reply, @"Approov-TraceID"), @"Should NOT have trace ID header");
}

static void TestRemoveSubstitutionHeaderRevertsBehavior(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(@"\"initialSecureStrings\":{\"header-key\":\"header-secret\"}");
  InitializeService(service, @"reinit");

  [service addSubstitutionHeader:@"Api-Key" requiredPrefix:@""];

  // 1. Verify substitution happens
  NSDictionary *reply1 = FetchNetworkReply(service, TargetURL(), @{@"headers": @{@"Api-Key": @"header-key"}});
  AssertEqualObjects(@"header-secret", HeaderValue(reply1, @"Api-Key"), @"Should substitute header");

  // 2. Remove substitution and verify it reverts
  [service removeSubstitutionHeader:@"Api-Key"];
  NSDictionary *reply2 = FetchNetworkReply(service, TargetURL(), @{@"headers": @{@"Api-Key": @"header-key"}});
  AssertEqualObjects(@"header-key", HeaderValue(reply2, @"Api-Key"), @"Should revert header substitution");
}

static void TestRemoveSubstitutionQueryParamRevertsBehavior(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(@"\"initialSecureStrings\":{\"query-key\":\"query-secret\"}");
  InitializeService(service, @"reinit");

  [service addSubstitutionQueryParam:@"api_key"];

  // 1. Verify substitution happens
  NSDictionary *reply1 = FetchNetworkReply(service, [TargetURL() stringByAppendingString:@"?api_key=query-key"], @{});
  AssertTrue([reply1[@"url"] containsString:@"api_key=query-secret"], @"Should substitute query param");

  // 2. Remove substitution and verify it reverts
  [service removeSubstitutionQueryParam:@"api_key"];
  NSDictionary *reply2 = FetchNetworkReply(service, [TargetURL() stringByAppendingString:@"?api_key=query-key"], @{});
  AssertTrue([reply2[@"url"] containsString:@"api_key=query-key"], @"Should revert query substitution");
}

static void TestRemoveExclusionURLRegexRevertsBehavior(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  NSString *regex = @"^.*excluded.*$";
  [service addExclusionURLRegex:regex];

  // 1. Verify exclusion happens (no token)
  NSDictionary *reply1 = FetchNetworkReply(service, [TargetURL() stringByAppendingString:@"/excluded"], @{});
  AssertNil(HeaderValue(reply1, @"Approov-Token"), @"Should be excluded (no token)");

  // 2. Remove exclusion and verify it is protected again
  [service removeExclusionURLRegex:regex];
  NSDictionary *reply2 = FetchNetworkReply(service, [TargetURL() stringByAppendingString:@"/excluded"], @{});
  AssertNotNil(HeaderValue(reply2, @"Approov-Token"), @"Should be protected again (has token)");
}

static void TestFetchWithApproovInjectsStatusWhenNoToken(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  useApproovStatusIfNoToken = YES;
  [MiniSDKAttesterProxyController setNextAttestationDirectiveJSON:@"{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"NO_APPROOV_SERVICE\"}}"];

  NSDictionary *reply = FetchNetworkReply(service, TargetURL(), @{});
  NSString *token = HeaderValue(reply, @"Approov-Token");
  
  AssertEqualObjects(@"no approov service", token, @"Should inject status when token is missing");
}

static void TestFetchWithApproovInjectsStatusWithCustomMutator(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  useApproovStatusIfNoToken = YES;
  
  // Set custom mutator handler to allow POOR_NETWORK
  ApproovMutatorBridgeSetFetchTokenHandler(^BOOL(id result, NSString *url, NSError **error) {
    ApproovTokenFetchResult *fetchResult = (ApproovTokenFetchResult *)result;
    if (fetchResult.status == ApproovTokenFetchStatusPoorNetwork) {
      return YES;
    }
    return NO;
  });

  [MiniSDKAttesterProxyController setNextAttestationDirectiveJSON:@"{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"POOR_NETWORK\"}}"];

  NSDictionary *reply = FetchNetworkReply(service, TargetURL(), @{});
  NSString *token = HeaderValue(reply, @"Approov-Token");
  
  AssertEqualObjects(@"poor network", token, @"Should inject POOR_NETWORK status when allowed by mutator");
  
  ApproovMutatorBridgeReset();
}

static void TestSetDataHashInTokenDirect(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  AwaitResolved(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service setDataHashInToken:@"manual-data-hash" resolver:resolve rejecter:reject];
  });

  NSDictionary *reply = FetchNetworkReply(service, TargetURL(), @{});
  NSString *token = HeaderValue(reply, @"Approov-Token");
  AssertNotNil(token, @"Token should not be nil");

  NSDictionary *payload = DecodeJWTBody(token);
  AssertEqualObjects(SHA256Base64(@"manual-data-hash"), payload[@"pay"], @"Data hash should match manual value");
}

static void TestSetDataHashInTokenEmptyClearsPayClaim(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  AwaitResolved(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service setDataHashInToken:@"" resolver:resolve rejecter:reject];
  });

  NSDictionary *reply = FetchNetworkReply(service, TargetURL(), @{});
  NSString *token = HeaderValue(reply, @"Approov-Token");
  AssertNotNil(token, @"Token should not be nil");

  NSDictionary *payload = DecodeJWTBody(token);
  AssertNil(payload[@"pay"], @"Pay claim should be missing for an empty manual data hash");
}

static void TestSetDataHashInTokenNilClearsPayClaim(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  AwaitResolved(^(RCTPromiseResolveBlock resolve, RCTPromiseRejectBlock reject) {
    [service setDataHashInToken:nil resolver:resolve rejecter:reject];
  });

  NSDictionary *reply = FetchNetworkReply(service, TargetURL(), @{});
  NSString *token = HeaderValue(reply, @"Approov-Token");
  AssertNotNil(token, @"Token should not be nil");

  NSDictionary *payload = DecodeJWTBody(token);
  AssertNil(payload[@"pay"], @"Pay claim should be missing after clearing the manual data hash");
}

static void TestSetBindingHeaderMissing(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  [service setBindingHeader:@"X-Custom-Binding"];

  // Request WITHOUT X-Custom-Binding
  NSDictionary *reply = FetchNetworkReply(service, TargetURL(), @{});
  NSString *token = HeaderValue(reply, @"Approov-Token");
  AssertNotNil(token, @"Token should not be nil");

  NSDictionary *payload = DecodeJWTBody(token);
  AssertNil(payload[@"pay"], @"Pay claim should be missing when binding header is absent");
}

static void TestSetBindingHeaderEmpty(void) {
  ApproovService *service = FreshService();
  LoadProtectedDomainScenario(nil);
  InitializeService(service, @"reinit");

  [service setBindingHeader:@"X-Custom-Binding"];

  NSDictionary *reply = FetchNetworkReply(service, TargetURL(), @{@"headers": @{@"X-Custom-Binding": @""}});
  NSString *token = HeaderValue(reply, @"Approov-Token");
  AssertNotNil(token, @"Token should not be nil");

  NSDictionary *payload = DecodeJWTBody(token);
  AssertNil(payload[@"pay"], @"Pay claim should be missing when binding header value is empty");
}

int main(void) {
  @autoreleasepool {
    NSArray<void (^)(void)> *tests = @[
      ^{ TestInitializeWithEmptyConfigForwardsWithoutApproov(); },
      ^{ TestGetDeviceIDReturnsMiniSDKDeviceID(); },
      ^{ TestGetPinningDiagnosticsReturnsExpectedShape(); },
      ^{ TestFetchWithApproovAddsTokenTraceAndSubstitutions(); },
      ^{ TestExcludedProtectedURLLeavesRequestUnmodified(); },
      ^{ TestDirectPinningAllowsValidPins(); },
      ^{ TestDirectPinningBlocksInvalidPins(); },
      ^{ TestFetchTokenReturnsSignedTokenWithExpectedClaims(); },
      ^{ TestFetchSecureStringAndCustomJWTUseMiniSDK(); },
      ^{ TestFetchWithApproovRejectsInvalidMethods(); },
      ^{ TestFetchWithApproovRejectsInvalidHeaders(); },
      ^{ TestFetchWithApproovRejectsInvalidHeaderNames(); },
      ^{ TestFetchWithApproovRejectsInvalidHeaderValues(); },
      ^{ TestFetchWithApproovRejectsInvalidBodies(); },
      ^{ TestFetchWithApproovAcceptsValidRequests(); },
      ^{ TestFetchWithApproovUsesDefaultHeaders(); },
      ^{ TestFetchWithApproovUsesCustomHeaders(); },
      ^{ TestFetchWithApproovCanDisableTraceID(); },
      ^{ TestRemoveSubstitutionHeaderRevertsBehavior(); },
      ^{ TestRemoveSubstitutionQueryParamRevertsBehavior(); },
      ^{ TestRemoveExclusionURLRegexRevertsBehavior(); },
      ^{ TestFetchWithApproovInjectsStatusWhenNoToken(); },
      ^{ TestFetchWithApproovInjectsStatusWithCustomMutator(); },
      ^{ TestSetDataHashInTokenDirect(); },
      ^{ TestSetDataHashInTokenEmptyClearsPayClaim(); },
      ^{ TestSetDataHashInTokenNilClearsPayClaim(); },
      ^{ TestSetBindingHeaderMissing(); },
      ^{ TestSetBindingHeaderEmpty(); },
    ];

    for (void (^testBlock)(void) in tests) {
      testBlock();
    }

    if (gFailureCount > 0) {
      NSLog(@"%lu iOS native mini-sdk test(s) failed", (unsigned long)gFailureCount);
      return 1;
    }

    NSLog(@"All iOS native mini-sdk tests passed");
    return 0;
  }
}
