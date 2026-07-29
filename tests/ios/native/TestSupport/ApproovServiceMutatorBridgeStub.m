#import "approov_service_react_native-Swift.h"
#import "Approov/Approov.h"
#import "ios/ApproovService.h"

static void (^gProcessRequestHandler)(NSMutableURLRequest *, NSString *, NSString *);
static BOOL (^gFetchTokenHandler)(id, NSString *, NSError **);

// Call records for the mutator-selection helpers, so tests can assert that
// ApproovService.m actually reaches them.
static NSUInteger gResetToDefaultCount = 0;
static NSUInteger gSetPolicyMutatorCount = 0;
static int32_t gLastPolicyMutatorMask = 0;
static BOOL gLastPolicyMutatorSign = NO;

@implementation ApproovServiceMutatorBridge

+ (instancetype)shared {
  static ApproovServiceMutatorBridge *sharedBridge = nil;
  static dispatch_once_t onceToken = 0;
  dispatch_once(&onceToken, ^{
    sharedBridge = [[ApproovServiceMutatorBridge alloc] init];
  });
  return sharedBridge;
}

- (void)processRequest:(NSMutableURLRequest *)request
           tokenHeader:(NSString *)tokenHeader
         traceIDHeader:(NSString *)traceIDHeader {
  if (gProcessRequestHandler != nil) {
    gProcessRequestHandler(request, tokenHeader, traceIDHeader);
  }
}

- (BOOL)handleInterceptorFetchTokenResult:(id)result
                                      url:(NSString *)url
                             errorPointer:(NSError **)errorPointer {
  if (gFetchTokenHandler != nil) {
    return gFetchTokenHandler(result, url, errorPointer);
  }

  ApproovTokenFetchResult *fetchResult = (ApproovTokenFetchResult *)result;
  switch (fetchResult.status) {
  case ApproovTokenFetchStatusSuccess:
    return YES;
  case ApproovTokenFetchStatusNoNetwork:
  case ApproovTokenFetchStatusPoorNetwork:
  case ApproovTokenFetchStatusMITMDetected:
    return NO;
  case ApproovTokenFetchStatusNoApproovService:
    return [ApproovService sharedUseApproovStatusIfNoToken];
  case ApproovTokenFetchStatusUnknownURL:
  case ApproovTokenFetchStatusUnprotectedURL:
    return NO;
  default:
    if (errorPointer != NULL) {
      *errorPointer = [NSError errorWithDomain:@"io.approov.reactnative.tests"
                                          code:1
                                      userInfo:@{
                                        NSLocalizedDescriptionKey :
                                            [NSString stringWithFormat:
                                                          @"Approov token fetch "
                                                          @"for %@: %@",
                                                          url,
                                                          [Approov stringFromApproovTokenFetchStatus:
                                                                       fetchResult.status]]
                                      }];
    }
    return NO;
  }
}

// Recording stubs matching the Swift bridge helpers added for setServiceMutatorType.
// The real mutator behaviour is covered by the Swift suite; what the ObjC suites need
// to observe is whether ApproovService.m *calls* these helpers — in particular that a
// config-change re-initialization triggers the mutator reset. Swallowing the calls
// silently would leave that integration point untested.
- (void)setPolicyMutator:(int32_t)mask sign:(BOOL)sign {
  gSetPolicyMutatorCount += 1;
  gLastPolicyMutatorMask = mask;
  gLastPolicyMutatorSign = sign;
}

- (void)resetToDefault {
  gResetToDefaultCount += 1;
}

@end

void ApproovMutatorBridgeReset(void) {
  gProcessRequestHandler = nil;
  gFetchTokenHandler = nil;
  gResetToDefaultCount = 0;
  gSetPolicyMutatorCount = 0;
  gLastPolicyMutatorMask = 0;
  gLastPolicyMutatorSign = NO;
}

NSUInteger ApproovMutatorBridgeResetToDefaultCount(void) {
  return gResetToDefaultCount;
}

NSUInteger ApproovMutatorBridgeSetPolicyMutatorCount(void) {
  return gSetPolicyMutatorCount;
}

int32_t ApproovMutatorBridgeLastPolicyMutatorMask(void) {
  return gLastPolicyMutatorMask;
}

BOOL ApproovMutatorBridgeLastPolicyMutatorSign(void) {
  return gLastPolicyMutatorSign;
}

void ApproovMutatorBridgeSetProcessRequestHandler(
    void (^handler)(NSMutableURLRequest *, NSString *, NSString *)) {
  gProcessRequestHandler = [handler copy];
}

void ApproovMutatorBridgeSetFetchTokenHandler(
    BOOL (^handler)(id, NSString *, NSError **)) {
  gFetchTokenHandler = [handler copy];
}
