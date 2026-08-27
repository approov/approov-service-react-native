#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface ApproovServiceMutatorBridge : NSObject

+ (instancetype)shared;
- (void)processRequest:(NSMutableURLRequest *)request
           tokenHeader:(NSString *_Nullable)tokenHeader
         traceIDHeader:(NSString *_Nullable)traceIDHeader;
- (BOOL)processRequest:(NSMutableURLRequest *)request
           tokenHeader:(NSString *_Nullable)tokenHeader
         traceIDHeader:(NSString *_Nullable)traceIDHeader
          errorPointer:(NSError *_Nullable *_Nullable)errorPointer;
- (BOOL)handleInterceptorFetchTokenResult:(id)result
                                      url:(NSString *)url
                             errorPointer:(NSError *_Nullable *_Nullable)errorPointer;
- (BOOL)handleInterceptorHeaderSubstitutionResult:(id)result
                                           header:(NSString *)header
                                     errorPointer:(NSError *_Nullable *_Nullable)errorPointer;
- (BOOL)handleInterceptorQueryParamSubstitutionResult:(id)result
                                             queryKey:(NSString *)queryKey
                                         errorPointer:(NSError *_Nullable *_Nullable)errorPointer;
- (void)setPolicyMutator:(int32_t)mask sign:(BOOL)sign useAccountSigning:(BOOL)useAccountSigning;
- (void)resetToDefault;
@property (nonatomic, readonly) BOOL isDefaultMutator;

@end

FOUNDATION_EXPORT void ApproovMutatorBridgeReset(void);
FOUNDATION_EXPORT void ApproovMutatorBridgeSetProcessRequestHandler(
    void (^_Nullable handler)(NSMutableURLRequest *request,
                              NSString *_Nullable tokenHeader,
                              NSString *_Nullable traceIDHeader));
FOUNDATION_EXPORT void ApproovMutatorBridgeSetFetchTokenHandler(
    BOOL (^_Nullable handler)(id result, NSString *url,
                              NSError *_Nullable *_Nullable errorPointer));
FOUNDATION_EXPORT void ApproovMutatorBridgeSetHeaderSubstitutionHandler(
    BOOL (^_Nullable handler)(id result, NSString *header,
                              NSError *_Nullable *_Nullable errorPointer));
FOUNDATION_EXPORT void ApproovMutatorBridgeSetQueryParamSubstitutionHandler(
    BOOL (^_Nullable handler)(id result, NSString *queryKey,
                              NSError *_Nullable *_Nullable errorPointer));

// Call records for the mutator-selection helpers, reset by ApproovMutatorBridgeReset.
FOUNDATION_EXPORT NSUInteger ApproovMutatorBridgeResetToDefaultCount(void);
FOUNDATION_EXPORT NSUInteger ApproovMutatorBridgeSetPolicyMutatorCount(void);
FOUNDATION_EXPORT int32_t ApproovMutatorBridgeLastPolicyMutatorMask(void);
FOUNDATION_EXPORT BOOL ApproovMutatorBridgeLastPolicyMutatorSign(void);

NS_ASSUME_NONNULL_END
