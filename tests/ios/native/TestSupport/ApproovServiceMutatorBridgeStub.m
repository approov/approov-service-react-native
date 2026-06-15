#import "approov_service_react_native-Swift.h"
#import "Approov/Approov.h"
#import "ios/ApproovService.h"

static void (^gProcessRequestHandler)(NSMutableURLRequest *, NSString *, NSString *);
static BOOL (^gFetchTokenHandler)(id, NSString *, NSError **);
static NSString *gServiceMutatorType = nil;
static BOOL gMessageSigningEnabled = YES;

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

// Minimal stubs for the mutator-selection / message-signing controls. The legacy native interceptor
// tests do not exercise these directly; they exist so ApproovService.m compiles and links.
- (void)setServiceMutatorByType:(NSString *)type {
  gServiceMutatorType = [type copy];
}

- (NSString *)getServiceMutatorType {
  return gServiceMutatorType != nil ? gServiceMutatorType : @"DEFAULT";
}

- (void)setMessageSigningEnabled:(BOOL)enabled {
  gMessageSigningEnabled = enabled;
}

- (BOOL)isMessageSigningEnabled {
  return gMessageSigningEnabled;
}

- (void)addSignedHeader:(NSString *)header {
  gMessageSigningEnabled = YES;
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
