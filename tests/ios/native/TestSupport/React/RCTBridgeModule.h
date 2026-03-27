#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^RCTPromiseResolveBlock)(id _Nullable result);
typedef void (^RCTPromiseRejectBlock)(NSString *_Nullable code,
                                      NSString *_Nullable message,
                                      NSError *_Nullable error);

@protocol RCTBridgeModule <NSObject>
@optional
+ (BOOL)requiresMainQueueSetup;
@end

#define RCT_EXPORT_MODULE(js_name)
#define RCT_EXPORT_METHOD(method) - (void)method

NS_ASSUME_NONNULL_END
