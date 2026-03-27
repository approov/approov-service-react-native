#import "ios/ApproovPinningDelegate.h"

@interface PinningURLSessionDelegate ()
@property(nonatomic, strong, nullable) ApproovService *approovService;
@property(nonatomic, strong, nullable)
    id<NSURLSessionDataDelegate> originalDelegate;
@end

@implementation PinningURLSessionDelegate

+ (instancetype)createWithDelegate:(id<NSURLSessionDataDelegate>)delegate
                    approovService:(ApproovService *)approovService {
  return [[self alloc] initWithDelegate:delegate approovService:approovService];
}

- (instancetype)initWithDelegate:(id<NSURLSessionDataDelegate>)delegate
                  approovService:(ApproovService *)approovService {
  self = [super init];
  if (self != nil) {
    _originalDelegate = delegate;
    _approovService = approovService;
  }
  return self;
}

- (void)URLSession:(NSURLSession *)session
          dataTask:(NSURLSessionDataTask *)dataTask
didReceiveResponse:(NSURLResponse *)response
 completionHandler:(void (^)(NSURLSessionResponseDisposition disposition))
                       completionHandler {
  if ([_originalDelegate
          respondsToSelector:@selector(URLSession:
                                   dataTask:
                         didReceiveResponse:
                          completionHandler:)]) {
    [_originalDelegate URLSession:session
                         dataTask:dataTask
               didReceiveResponse:response
                completionHandler:completionHandler];
    return;
  }
  completionHandler(NSURLSessionResponseAllow);
}

- (void)URLSession:(NSURLSession *)session
          dataTask:(NSURLSessionDataTask *)dataTask
    didReceiveData:(NSData *)data {
  if ([_originalDelegate respondsToSelector:@selector(URLSession:
                                                   dataTask:
                                             didReceiveData:)]) {
    [_originalDelegate URLSession:session dataTask:dataTask didReceiveData:data];
  }
}

- (void)URLSession:(NSURLSession *)session
              task:(NSURLSessionTask *)task
didCompleteWithError:(NSError *)error {
  if ([_originalDelegate respondsToSelector:@selector(URLSession:
                                                       task:
                                         didCompleteWithError:)]) {
    [_originalDelegate URLSession:session task:task didCompleteWithError:error];
  }
}

@end
