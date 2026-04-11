#import "Approov.h"

static NSMutableArray<ApproovTokenFetchResult *> *gTokenResults;
static NSMutableArray<ApproovTokenFetchResult *> *gSecureStringResults;
static NSUInteger gFetchConfigCallCount;
static NSUInteger gFetchApproovTokenCallCount;
static NSString *gLastDataHash;
static NSString *gLastDevKey;
static NSString *gLastInstallAttrs;
static NSError *gInitializationError;

@implementation ApproovTokenFetchResult

+ (instancetype)resultWithStatus:(ApproovTokenFetchStatus)status
                           token:(NSString *)token
                   loggableToken:(NSString *)loggableToken
                    secureString:(NSString *)secureString
                         traceID:(NSString *)traceID
                             ARC:(NSString *)ARC
                rejectionReasons:(NSString *)rejectionReasons
                   configChanged:(BOOL)configChanged {
  ApproovTokenFetchResult *result = [[ApproovTokenFetchResult alloc] init];
  result.status = status;
  result.token = token;
  result.loggableToken = loggableToken;
  result.secureString = secureString;
  result.traceID = traceID;
  result.ARC = ARC;
  result.rejectionReasons = rejectionReasons ?: @"";
  result.isConfigChanged = configChanged;
  return result;
}

@end

static ApproovTokenFetchResult *ApproovPopResult(
    NSMutableArray<ApproovTokenFetchResult *> *queue,
    ApproovTokenFetchStatus fallbackStatus) {
  if (queue.count == 0) {
    return [ApproovTokenFetchResult resultWithStatus:fallbackStatus
                                              token:@""
                                      loggableToken:@""
                                       secureString:@""
                                            traceID:@""
                                                ARC:@""
                                   rejectionReasons:@""
                                      configChanged:NO];
  }

  ApproovTokenFetchResult *result = queue.firstObject;
  [queue removeObjectAtIndex:0];
  return result;
}

void ApproovTestReset(void) {
  gTokenResults = [[NSMutableArray alloc] init];
  gSecureStringResults = [[NSMutableArray alloc] init];
  gFetchConfigCallCount = 0;
  gFetchApproovTokenCallCount = 0;
  gLastDataHash = nil;
  gLastDevKey = nil;
  gLastInstallAttrs = nil;
  gInitializationError = nil;
}

void ApproovTestEnqueueTokenResult(ApproovTokenFetchResult *result) {
  if (gTokenResults == nil) {
    ApproovTestReset();
  }
  [gTokenResults addObject:result];
}

void ApproovTestEnqueueSecureStringResult(ApproovTokenFetchResult *result) {
  if (gSecureStringResults == nil) {
    ApproovTestReset();
  }
  [gSecureStringResults addObject:result];
}

NSUInteger ApproovTestFetchConfigCallCount(void) { return gFetchConfigCallCount; }

NSUInteger ApproovTestFetchApproovTokenCallCount(void) {
  return gFetchApproovTokenCallCount;
}

NSString *ApproovTestLastDataHash(void) { return gLastDataHash; }

NSString *ApproovTestLastDevKey(void) { return gLastDevKey; }

NSString *ApproovTestLastInstallAttrs(void) { return gLastInstallAttrs; }

void ApproovTestSetInitializationError(NSError *error) {
  gInitializationError = [error copy];
}

void ApproovTestClearInitializationError(void) { gInitializationError = nil; }

@implementation Approov

+ (void)initialize:(NSString *)config
      updateConfig:(NSString *)updateConfig
           comment:(NSString *)comment
             error:(NSError *__autoreleasing  _Nullable *)error {
  (void)config;
  (void)updateConfig;
  (void)comment;
  if (error != NULL) {
    *error = gInitializationError;
  }
}

+ (void)setUserProperty:(NSString *)property {
  (void)property;
}

+ (NSString *)getDeviceID {
  return @"device-id";
}

+ (void)setDataHashInToken:(NSString *)data {
  gLastDataHash = [data copy];
}

+ (void)setDevKey:(NSString *)devKey {
  gLastDevKey = [devKey copy];
}

+ (void)setInstallAttrsInToken:(NSString *)attrs {
  gLastInstallAttrs = [attrs copy];
}

+ (void)setInstallAttrsInToken:(NSString *)attrs
                         error:(NSError *__autoreleasing  _Nullable *)error {
  gLastInstallAttrs = [attrs copy];
  if (error != NULL) {
    *error = nil;
  }
}

+ (ApproovTokenFetchResult *)fetchApproovTokenAndWait:(NSString *)host {
  (void)host;
  gFetchApproovTokenCallCount += 1;
  return ApproovPopResult(gTokenResults, ApproovTokenFetchStatusSuccess);
}

+ (ApproovTokenFetchResult *)fetchSecureStringAndWait:(NSString *)key
                                                     :(NSString *)newDef {
  (void)key;
  (void)newDef;
  return ApproovPopResult(gSecureStringResults,
                          ApproovTokenFetchStatusUnknownKey);
}

+ (void)fetchSecureString:(ApproovTokenFetchCallback)callback
                         :(NSString *)key
                         :(NSString *)newDef {
  (void)key;
  (void)newDef;
  if (callback != nil) {
    callback([self fetchSecureStringAndWait:key :newDef]);
  }
}

+ (void)fetchApproovToken:(ApproovTokenFetchCallback)callback :(NSString *)host {
  if (callback != nil) {
    callback([self fetchApproovTokenAndWait:host]);
  }
}

+ (void)fetchCustomJWT:(ApproovTokenFetchCallback)callback :(NSString *)payload {
  (void)payload;
  if (callback != nil) {
    callback([self fetchApproovTokenAndWait:@"custom-jwt"]);
  }
}

+ (void)fetchConfig {
  gFetchConfigCallCount += 1;
}

+ (NSDictionary<NSString *,NSArray<NSString *> *> *)getPins:(NSString *)pinType {
  (void)pinType;
  return @{ @"example.com" : @[ @"pin" ] };
}

+ (NSString *)stringFromApproovTokenFetchStatus:(ApproovTokenFetchStatus)status {
  switch (status) {
  case ApproovTokenFetchStatusSuccess:
    return @"SUCCESS";
  case ApproovTokenFetchStatusUnknownURL:
    return @"UNKNOWN_URL";
  case ApproovTokenFetchStatusUnprotectedURL:
    return @"UNPROTECTED_URL";
  case ApproovTokenFetchStatusNoNetwork:
    return @"NO_NETWORK";
  case ApproovTokenFetchStatusPoorNetwork:
    return @"POOR_NETWORK";
  case ApproovTokenFetchStatusMITMDetected:
    return @"MITM_DETECTED";
  case ApproovTokenFetchStatusNoApproovService:
    return @"NO_APPROOV_SERVICE";
  case ApproovTokenFetchStatusBadURL:
    return @"BAD_URL";
  case ApproovTokenFetchStatusRejected:
    return @"REJECTED";
  case ApproovTokenFetchStatusUnknownKey:
    return @"UNKNOWN_KEY";
  }
}

+ (NSString *)getInstallMessageSignature:(NSString *)message {
  (void)message;
  return @"";
}

+ (NSString *)getMessageSignature:(NSString *)message {
  (void)message;
  return @"";
}

@end
