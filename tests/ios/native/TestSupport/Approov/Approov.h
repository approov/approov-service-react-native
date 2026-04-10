#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef NS_ENUM(NSInteger, ApproovTokenFetchStatus) {
  ApproovTokenFetchStatusSuccess = 0,
  ApproovTokenFetchStatusNoNetwork,
  ApproovTokenFetchStatusMITMDetected,
  ApproovTokenFetchStatusPoorNetwork,
  ApproovTokenFetchStatusNoApproovService,
  ApproovTokenFetchStatusBadURL,
  ApproovTokenFetchStatusUnknownURL,
  ApproovTokenFetchStatusUnprotectedURL,
  ApproovTokenFetchStatusNotInitialized,
  ApproovTokenFetchStatusRejected,
  ApproovTokenFetchStatusDisabled,
  ApproovTokenFetchStatusUnknownKey,
  ApproovTokenFetchStatusBadKey,
  ApproovTokenFetchStatusBadPayload,
  ApproovTokenFetchStatusInternalError
};

@interface ApproovTokenFetchResult : NSObject

@property(nonatomic, assign) ApproovTokenFetchStatus status;
@property(nonatomic, copy, nullable) NSString *token;
@property(nonatomic, copy, nullable) NSString *loggableToken;
@property(nonatomic, copy, nullable) NSString *secureString;
@property(nonatomic, copy, nullable) NSString *traceID;
@property(nonatomic, copy, nullable) NSString *ARC;
@property(nonatomic, copy, nullable) NSString *rejectionReasons;
@property(nonatomic, assign) BOOL isConfigChanged;

+ (instancetype)resultWithStatus:(ApproovTokenFetchStatus)status
                           token:(nullable NSString *)token
                   loggableToken:(nullable NSString *)loggableToken
                    secureString:(nullable NSString *)secureString
                         traceID:(nullable NSString *)traceID
                             ARC:(nullable NSString *)ARC
                rejectionReasons:(nullable NSString *)rejectionReasons
                   configChanged:(BOOL)configChanged;

@end

typedef void (^ApproovTokenFetchCallback)(ApproovTokenFetchResult *result);

@interface Approov : NSObject

+ (void)initialize:(NSString *)config
      updateConfig:(NSString *)updateConfig
           comment:(nullable NSString *)comment
             error:(NSError *_Nullable *_Nullable)error;
+ (void)setUserProperty:(NSString *)property;
+ (NSString *)getDeviceID;
+ (void)setDataHashInToken:(NSString *)data;
+ (void)setDevKey:(NSString *)devKey;
+ (void)setInstallAttrsInToken:(NSString *)attrs;
+ (void)setInstallAttrsInToken:(NSString *)attrs
                         error:(NSError *_Nullable *_Nullable)error;
+ (ApproovTokenFetchResult *)fetchApproovTokenAndWait:(NSString *)host;
+ (ApproovTokenFetchResult *)fetchSecureStringAndWait:(NSString *)key
                                                     :(nullable NSString *)newDef;
+ (void)fetchSecureString:(ApproovTokenFetchCallback)callback
                         :(NSString *)key
                         :(nullable NSString *)newDef;
+ (void)fetchApproovToken:(ApproovTokenFetchCallback)callback :(NSString *)host;
+ (void)fetchCustomJWT:(ApproovTokenFetchCallback)callback :(NSString *)payload;
+ (void)fetchConfig;
+ (NSDictionary<NSString *, NSArray<NSString *> *> *)getPins:(NSString *)pinType;
+ (NSString *)stringFromApproovTokenFetchStatus:(ApproovTokenFetchStatus)status;
+ (NSString *)getInstallMessageSignature:(NSString *)message;
+ (NSString *)getMessageSignature:(NSString *)message;

@end

FOUNDATION_EXPORT void ApproovTestReset(void);
FOUNDATION_EXPORT void ApproovTestEnqueueTokenResult(
    ApproovTokenFetchResult *result);
FOUNDATION_EXPORT void ApproovTestEnqueueSecureStringResult(
    ApproovTokenFetchResult *result);
FOUNDATION_EXPORT NSUInteger ApproovTestFetchConfigCallCount(void);
FOUNDATION_EXPORT NSUInteger ApproovTestFetchApproovTokenCallCount(void);
FOUNDATION_EXPORT NSString *_Nullable ApproovTestLastDataHash(void);
FOUNDATION_EXPORT NSString *_Nullable ApproovTestLastDevKey(void);
FOUNDATION_EXPORT NSString *_Nullable ApproovTestLastInstallAttrs(void);

NS_ASSUME_NONNULL_END
