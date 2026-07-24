#import "approov_service_react_native-Swift.h"
#import "Approov/Approov.h"
#import "ios/ApproovService.h"

static void (^gProcessRequestHandler)(NSMutableURLRequest *, NSString *, NSString *);
static BOOL (^gFetchTokenHandler)(id, NSString *, NSError **);

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

// No-op stubs matching the Swift bridge helpers added for setServiceMutatorType.
// The native ObjC suites do not exercise mutator selection; these selectors only
// need to exist so ApproovService.m compiles and links against the stub bridge.
- (void)setPolicyMutator:(int32_t)mask sign:(BOOL)sign {
  (void)mask;
  (void)sign;
}

- (void)resetToDefault {
}

@end

void ApproovMutatorBridgeReset(void) {
  gProcessRequestHandler = nil;
  gFetchTokenHandler = nil;
}

void ApproovMutatorBridgeSetProcessRequestHandler(
    void (^handler)(NSMutableURLRequest *, NSString *, NSString *)) {
  gProcessRequestHandler = [handler copy];
}

void ApproovMutatorBridgeSetFetchTokenHandler(
    BOOL (^handler)(id, NSString *, NSError **)) {
  gFetchTokenHandler = [handler copy];
}
