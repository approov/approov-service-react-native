#import "ios/ApproovRCTInterceptor.h"

@interface ApproovRCTInterceptor ()
@property(readwrite, nonatomic, strong) ApproovService *approovService;
@end

static NSInteger gMaxReswizzleAttempts = 0;
static BOOL gSessionMetadataCollectionEnabled = YES;

@implementation ApproovRCTInterceptor

+ (instancetype)startWithApproovService:(ApproovService *)approovService {
  ApproovRCTInterceptor *interceptor = [[ApproovRCTInterceptor alloc] init];
  interceptor.approovService = approovService;
  return interceptor;
}

+ (void)setInterceptionMode:(NSInteger)mode {
  (void)mode;
}

+ (void)setMaxReswizzleAttempts:(NSInteger)attempts {
  gMaxReswizzleAttempts = attempts;
}

+ (NSInteger)maxReswizzleAttempts {
  return gMaxReswizzleAttempts;
}

+ (void)setSessionMetadataCollectionEnabled:(BOOL)enabled {
  gSessionMetadataCollectionEnabled = enabled;
}

+ (BOOL)sessionMetadataCollectionEnabled {
  return gSessionMetadataCollectionEnabled;
}

+ (void)addAllowedDelegate:(NSString *)delegatePattern {
  (void)delegatePattern;
}

+ (void)addExcludedDelegate:(NSString *)delegatePattern {
  (void)delegatePattern;
}

+ (void)removeAllowedDelegate:(NSString *)delegatePattern {
  (void)delegatePattern;
}

+ (void)removeExcludedDelegate:(NSString *)delegatePattern {
  (void)delegatePattern;
}

+ (NSDictionary *)getSessionDiagnostics {
  return @{ @"sessions" : @[] };
}

+ (NSDictionary *)getPinningDiagnostics {
  return @{ @"sessionsWithPinning" : @0, @"sessionsWithoutPinning" : @0 };
}

+ (void)validatePinningIsActive {
}

+ (NSDictionary *)getIMPIntegrityDiagnostics {
  return @{ @"integrity" : @"stubbed" };
}

@end
