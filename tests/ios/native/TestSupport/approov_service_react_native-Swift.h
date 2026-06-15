#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface ApproovServiceMutatorBridge : NSObject

+ (instancetype)shared;
- (void)processRequest:(NSMutableURLRequest *)request
           tokenHeader:(NSString *_Nullable)tokenHeader
         traceIDHeader:(NSString *_Nullable)traceIDHeader;
- (BOOL)handleInterceptorFetchTokenResult:(id)result
                                      url:(NSString *)url
                             errorPointer:(NSError *_Nullable *_Nullable)errorPointer;
- (void)setServiceMutatorByType:(NSString *)type;
- (NSString *)getServiceMutatorType;
- (void)setMessageSigningEnabled:(BOOL)enabled;
- (BOOL)isMessageSigningEnabled;
- (void)addSignedHeader:(NSString *)header;

@end

FOUNDATION_EXPORT void ApproovMutatorBridgeReset(void);
FOUNDATION_EXPORT void ApproovMutatorBridgeSetProcessRequestHandler(
    void (^_Nullable handler)(NSMutableURLRequest *request,
                              NSString *_Nullable tokenHeader,
                              NSString *_Nullable traceIDHeader));
FOUNDATION_EXPORT void ApproovMutatorBridgeSetFetchTokenHandler(
    BOOL (^_Nullable handler)(id result, NSString *url,
                              NSError *_Nullable *_Nullable errorPointer));

NS_ASSUME_NONNULL_END
