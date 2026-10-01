/*
 * MIT License
 *
 * Copyright (c) 2016-present, CriticalBlue Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#import "ApproovPinningDelegate.h"
#import "ApproovUtils.h"
#if __has_include(                                                             \
    <approov_service_react_native/approov_service_react_native-Swift.h>)
#import <approov_service_react_native/approov_service_react_native-Swift.h>
#elif __has_include("approov_service_react_native-Swift.h")
#import "approov_service_react_native-Swift.h"
#else
// Fallback if the module name is different
#import <approov_service_react_native_Swift.h>
#endif

@interface PinningURLSessionDelegate ()

// the ApproovService that is able to interpret the trust decisions against the
// dynamic pins
@property(nonatomic, strong, nullable) ApproovService *approovService;

// the original delegate to which non-authentication calls are passed
@property(nullable) id<NSURLSessionDataDelegate> originalDelegate;

@end

// NSURLProtocol property keys holding a request's state before Approov
// processed it
static NSString *const kApproovPreRequestHeadersKey =
    @"io.approov.reactnative.preApproovHeaders";
static NSString *const kApproovPreRequestURLKey =
    @"io.approov.reactnative.preApproovURL";

// Header values compare equal regardless of surrounding whitespace, since the
// stack may store or send a value trimmed
static BOOL ApproovHeaderValuesMatch(NSString *a, NSString *b) {
  NSCharacterSet *ws = [NSCharacterSet whitespaceCharacterSet];
  return [[a stringByTrimmingCharactersInSet:ws]
      isEqualToString:[b stringByTrimmingCharactersInSet:ws]];
}

// Case-insensitive header lookup in a header dictionary
static NSString *_Nullable ApproovHeaderLookup(NSDictionary<NSString *, NSString *> *headers,
                                               NSString *name) {
  NSString *value = headers[name];
  if (value != nil)
    return value;
  for (NSString *key in headers) {
    if ([key caseInsensitiveCompare:name] == NSOrderedSame)
      return headers[key];
  }
  return nil;
}

@implementation PinningURLSessionDelegate

+ (void)recordPreApproovRequest:(NSURLRequest *)original
             onProcessedRequest:(NSMutableURLRequest *)processed {
  // A request can pass through the interceptor more than once (the task
  // swizzles sit on NSURLSession and on its private subclasses, and one may
  // call through to the other). Keep the first record, which holds the app's
  // values; recording again would capture the processed headers instead.
  NSDictionary *recorded = [NSURLProtocol propertyForKey:kApproovPreRequestHeadersKey
                                               inRequest:original];
  if (recorded != nil) {
    [NSURLProtocol setProperty:recorded
                        forKey:kApproovPreRequestHeadersKey
                     inRequest:processed];
    NSString *recordedURL = [NSURLProtocol propertyForKey:kApproovPreRequestURLKey
                                                inRequest:original];
    if (recordedURL != nil)
      [NSURLProtocol setProperty:recordedURL
                          forKey:kApproovPreRequestURLKey
                       inRequest:processed];
    return;
  }
  [NSURLProtocol setProperty:(original.allHTTPHeaderFields ?: @{})
                      forKey:kApproovPreRequestHeadersKey
                   inRequest:processed];
  NSString *url = original.URL.absoluteString;
  if (url != nil) {
    [NSURLProtocol setProperty:url
                        forKey:kApproovPreRequestURLKey
                     inRequest:processed];
  } else {
    [NSURLProtocol removePropertyForKey:kApproovPreRequestURLKey
                              inRequest:processed];
  }
}

/**
 * Builds the follow-up request for a redirect with everything Approov added
 * for the previous attempt taken back out. URLSession builds the redirect
 * request from the previous attempt's headers, so without this the token,
 * trace ID, signatures and substituted secrets issued for the original host
 * would be sent to the redirect target, whatever host that is.
 *
 * Every header whose value Approov changed is restored to the app's value, or
 * removed if the app had not set it, but only while the redirect request still
 * carries the value Approov applied: a header the stack dropped (Content-Type
 * and Content-Digest on a 303 to GET) stays dropped. If the redirect targets
 * the same URL, the app's URL is restored so no substituted query parameter
 * survives. A request Approov never processed carries no record and is
 * returned unchanged.
 */
+ (NSMutableURLRequest *)requestByUndoingApproovChangesIn:(NSURLRequest *)redirect
                                          previousAttempt:(NSURLRequest *_Nullable)previous {
  NSMutableURLRequest *followUp = [redirect mutableCopy];
  NSDictionary<NSString *, NSString *> *before =
      [NSURLProtocol propertyForKey:kApproovPreRequestHeadersKey
                          inRequest:redirect];
  if (before == nil && previous != nil)
    before = [NSURLProtocol propertyForKey:kApproovPreRequestHeadersKey
                                 inRequest:previous];
  if (before == nil || previous == nil)
    return followUp;

  NSDictionary<NSString *, NSString *> *applied = previous.allHTTPHeaderFields;
  for (NSString *name in applied) {
    NSString *appliedValue = applied[name];
    NSString *appValue = ApproovHeaderLookup(before, name);
    if (appValue != nil && [appValue isEqualToString:appliedValue])
      continue;
    NSString *carried = [followUp valueForHTTPHeaderField:name];
    if (carried == nil || !ApproovHeaderValuesMatch(carried, appliedValue))
      continue;
    [followUp setValue:appValue forHTTPHeaderField:name];
  }

  NSString *appURL = [NSURLProtocol propertyForKey:kApproovPreRequestURLKey
                                         inRequest:redirect];
  if (appURL == nil)
    appURL = [NSURLProtocol propertyForKey:kApproovPreRequestURLKey
                                 inRequest:previous];
  if (appURL != nil && [followUp.URL isEqual:previous.URL]) {
    NSURL *restored = [NSURL URLWithString:appURL];
    if (restored != nil)
      followUp.URL = restored;
  }

  [NSURLProtocol removePropertyForKey:kApproovPreRequestHeadersKey
                            inRequest:followUp];
  [NSURLProtocol removePropertyForKey:kApproovPreRequestURLKey
                            inRequest:followUp];
  return followUp;
}

/** Creates a pinning URL session delegate.
 *
 * @param delegate is the original delgate
 * @param approovService is the ApproovService that will provide the pinning
 * information
 */
+ (instancetype)createWithDelegate:(id<NSURLSessionDataDelegate> _Nullable)delegate
                    approovService:(ApproovService *_Nullable)approovService {
  return [[self alloc] initWithDelegate:delegate approovService:approovService];
}

/**
 * Initializes a pinning URL session delegate.
 *
 * @param delegate is the original delgate
 * @param approovService is the ApproovService that will provide the pinning
 * information
 */
- (instancetype)initWithDelegate:(id<NSURLSessionDataDelegate> _Nullable)delegate
                  approovService:(ApproovService *_Nullable)approovService {
  self = [super init];
  if (self) {
    _approovService = approovService;
    _originalDelegate = delegate;
  }
  ApproovLogI(@"ApproovService pinning NSURLSessionDelegate: %@",
              delegate ? NSStringFromClass([delegate class]) : @"<nil>");
  return self;
}

/**
 * Resolves the current ApproovService. Delegates created during early
 * swizzling may be initialized before the native module exists, so pinning
 * must recover the live service at challenge time.
 */
/**
 * Asks the service mutator whether Approov pinning applies (handlePinningShouldProcessRequest).
 * Server trust is a connection-level challenge on iOS, so pins are checked once per TLS
 * connection and usually no single request is in scope: unless the task is known, the mutator
 * is asked with a request for the connection's origin (https, host and port, no path, headers or
 * body). If no origin can be built, pinning is kept.
 */
- (BOOL)shouldPinForChallenge:(NSURLAuthenticationChallenge *)challenge
                         task:(NSURLSessionTask *_Nullable)task {
  NSURLRequest *request = task.currentRequest ?: task.originalRequest;
  if (request == nil) {
    NSURLComponents *origin = [[NSURLComponents alloc] init];
    origin.scheme = @"https";
    origin.host = challenge.protectionSpace.host;
    NSInteger port = challenge.protectionSpace.port;
    if ((port > 0) && (port != 443))
      origin.port = @(port);
    origin.path = @"/";
    NSURL *url = origin.URL;
    if (url == nil)
      return YES;
    request = [NSURLRequest requestWithURL:url];
  }
  return [[ApproovServiceMutatorBridge shared] handlePinningShouldProcessRequest:request];
}

- (ApproovService *_Nullable)currentApproovService {
  ApproovService *service = _approovService ?: [ApproovService sharedService];
  if (_approovService == nil && service != nil) {
    _approovService = service;
  }
  return service;
}

/**
 * Forwards authentication challenges to the wrapped delegate when available.
 * Task-level callback is preferred when a task is available, then
 * session-level.
 */
- (void)forwardChallengeToOriginalDelegateForSession:(NSURLSession *)session
                                                task:(NSURLSessionTask
                                                          *_Nullable)task
                                           challenge:
                                               (NSURLAuthenticationChallenge *)
                                                   challenge
                                   completionHandler:
                                       (void (^)(
                                           NSURLSessionAuthChallengeDisposition
                                               disposition,
                                           NSURLCredential *credential))
                                           completionHandler
                                       challengeType:(NSString *)challengeType {
  NSString *host = challenge.protectionSpace.host ?: @"<unknown>";
  NSString *delegateClassName =
      _originalDelegate ? NSStringFromClass([_originalDelegate class])
                        : @"<nil>";

  if ((task != nil) &&
      [_originalDelegate respondsToSelector:@selector
                         (URLSession:
                                task:didReceiveChallenge:completionHandler:)]) {
    ApproovLogI(@"ApproovService forwarding %@ challenge for %@ to task "
                @"delegate %@",
                challengeType, host, delegateClassName);
    id<NSURLSessionTaskDelegate> taskDelegate =
        (id<NSURLSessionTaskDelegate>)_originalDelegate;
    [taskDelegate URLSession:session
                        task:task
         didReceiveChallenge:challenge
           completionHandler:completionHandler];
    return;
  }

  if ([_originalDelegate respondsToSelector:@selector
                         (URLSession:didReceiveChallenge:completionHandler:)]) {
    ApproovLogI(@"ApproovService forwarding %@ challenge for %@ to session "
                @"delegate %@",
                challengeType, host, delegateClassName);
    [_originalDelegate URLSession:session
              didReceiveChallenge:challenge
                completionHandler:completionHandler];
    return;
  }

  ApproovLogI(@"ApproovService no original delegate challenge handler for %@ "
              @"on %@, using default handling",
              challengeType, host);
  completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, NULL);
}

/**
 * Handles session authentication challenges. This is handled by Approov pinning
 * and not passed to the original delegate.
 *
 * @param session is the session containing the task whose request requires
 * authentication
 * @param challenge is an object that contains the request for authentication
 * @param completionHandler is a handler that must be called providing the
 * outcome of the decision
 */
- (void)URLSession:(NSURLSession *)session
               dataTask:(NSURLSessionDataTask *)dataTask
    didReceiveChallenge:(NSURLAuthenticationChallenge *)challenge
      completionHandler:
          (void (^)(NSURLSessionAuthChallengeDisposition disposition,
                    NSURLCredential *credential))completionHandler {
  NSString *host = challenge.protectionSpace.host ?: @"<unknown>";
  NSString *authMethod =
      challenge.protectionSpace.authenticationMethod ?: @"<unknown>";
  ApproovLogI(@"ApproovService received task challenge %@ for %@", authMethod,
              host);
  if ([challenge.protectionSpace.authenticationMethod
          isEqualToString:NSURLAuthenticationMethodServerTrust]) {
    ApproovService *service = [self currentApproovService];
    if (service == nil) {
      ApproovLogW(@"ApproovService unavailable for task server-trust "
                  @"challenge on %@, forwarding without pin verification",
                  host);
      [self forwardChallengeToOriginalDelegateForSession:session
                                                    task:dataTask
                                               challenge:challenge
                                       completionHandler:completionHandler
                                           challengeType:@"server-trust "
                                                         @"(service "
                                                         @"unavailable)"];
      return;
    }

    if (![self shouldPinForChallenge:challenge task:dataTask]) {
      ApproovLogI(@"pinning skipped by the service mutator for %@", host);
      [self forwardChallengeToOriginalDelegateForSession:session
                                                    task:dataTask
                                               challenge:challenge
                                       completionHandler:completionHandler
                                           challengeType:@"server-trust "
                                                         @"(pinning skipped)"];
      return;
    }

    ApproovTrustDecision trustDecision =
        [service verifyPins:challenge.protectionSpace.serverTrust forHost:host];

    // Notify interceptor that pinning was invoked
    if (self.authChallengeCallback) {
      self.authChallengeCallback(host, trustDecision);
    }

    if (trustDecision == ApproovTrustDecisionBlock) {
      ApproovLogW(@"ApproovService PINNING BLOCKED connection to %@", host);
      completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge,
                        NULL);
    } else {
      ApproovLogI(@"ApproovService pinning allowed connection to %@, chaining "
                  @"server-trust challenge to original delegate",
                  host);
      [self forwardChallengeToOriginalDelegateForSession:session
                                                    task:dataTask
                                               challenge:challenge
                                       completionHandler:completionHandler
                                           challengeType:@"server-trust"];
    }
  } else {
    [self forwardChallengeToOriginalDelegateForSession:session
                                                  task:dataTask
                                             challenge:challenge
                                     completionHandler:completionHandler
                                         challengeType:@"non-server-trust"];
  }
}

/**
 * Handles session-level authentication challenges. This is called by some
 * frameworks (like New Relic) that use session-level challenge handling instead
 * of task-level. This is handled by Approov pinning and not passed to the
 * original delegate.
 *
 * @param session is the session containing the task whose request requires
 * authentication
 * @param challenge is an object that contains the request for authentication
 * @param completionHandler is a handler that must be called providing the
 * outcome of the decision
 */
- (void)URLSession:(NSURLSession *)session
    didReceiveChallenge:(NSURLAuthenticationChallenge *)challenge
      completionHandler:
          (void (^)(NSURLSessionAuthChallengeDisposition disposition,
                    NSURLCredential *credential))completionHandler {
  NSString *host = challenge.protectionSpace.host ?: @"<unknown>";
  NSString *authMethod =
      challenge.protectionSpace.authenticationMethod ?: @"<unknown>";
  ApproovLogI(@"ApproovService received session challenge %@ for %@",
              authMethod, host);
  if ([challenge.protectionSpace.authenticationMethod
          isEqualToString:NSURLAuthenticationMethodServerTrust]) {
    ApproovService *service = [self currentApproovService];
    if (service == nil) {
      ApproovLogW(@"ApproovService unavailable for session server-trust "
                  @"challenge on %@, forwarding without pin verification",
                  host);
      [self forwardChallengeToOriginalDelegateForSession:session
                                                    task:nil
                                               challenge:challenge
                                       completionHandler:completionHandler
                                           challengeType:@"server-trust "
                                                         @"(service "
                                                         @"unavailable)"];
      return;
    }

    if (![self shouldPinForChallenge:challenge task:nil]) {
      ApproovLogI(@"pinning skipped by the service mutator for %@ (session-level)",
                  host);
      [self forwardChallengeToOriginalDelegateForSession:session
                                                    task:nil
                                               challenge:challenge
                                       completionHandler:completionHandler
                                           challengeType:@"server-trust "
                                                         @"(pinning skipped)"];
      return;
    }

    ApproovTrustDecision trustDecision =
        [service verifyPins:challenge.protectionSpace.serverTrust forHost:host];

    // Notify interceptor that pinning was invoked
    if (self.authChallengeCallback) {
      self.authChallengeCallback(host, trustDecision);
    }

    if (trustDecision == ApproovTrustDecisionBlock) {
      ApproovLogW(
          @"ApproovService PINNING BLOCKED connection to %@ (session-level)",
          host);
      completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge,
                        NULL);
    } else {
      ApproovLogI(@"ApproovService pinning allowed connection to %@ "
                  @"(session-level), chaining server-trust challenge to "
                  @"original delegate",
                  host);
      [self forwardChallengeToOriginalDelegateForSession:session
                                                    task:nil
                                               challenge:challenge
                                       completionHandler:completionHandler
                                           challengeType:@"server-trust"];
    }
  } else {
    [self forwardChallengeToOriginalDelegateForSession:session
                                                  task:nil
                                             challenge:challenge
                                     completionHandler:completionHandler
                                         challengeType:@"non-server-trust"];
  }
}

/**
 * Periodically informs the delegate of the progress of sending body content to
 * the server. This is simply passed to the original delegate.
 *
 * https://developer.apple.com/documentation/foundation/urlsessiontaskdelegate/1408299-urlsession?language=objc
 */
- (void)URLSession:(NSURLSession *)session
                        task:(NSURLSessionTask *)task
             didSendBodyData:(int64_t)bytesSent
              totalBytesSent:(int64_t)totalBytesSent
    totalBytesExpectedToSend:(int64_t)totalBytesExpectedToSend {
  if ([_originalDelegate
          respondsToSelector:@selector(URLSession:
                                             task:didSendBodyData:totalBytesSent
                                                 :totalBytesExpectedToSend:)])
    [_originalDelegate URLSession:session
                             task:task
                  didSendBodyData:bytesSent
                   totalBytesSent:totalBytesSent
         totalBytesExpectedToSend:totalBytesExpectedToSend];
}

/**
 * Tells the delegate that the remote server requested an HTTP redirect.
 *
 * A redirect is a new request to a new URL, and Approov protection is decided
 * per API domain, so the follow-up is classified afresh: everything Approov
 * added for the previous attempt is taken out, then the follow-up goes through
 * the same processing as a new request. A redirect to a domain Approov does not
 * protect therefore carries no Approov token, trace ID, signature or
 * substituted secret, and one to a protected domain gets a token and signature
 * issued for that URL. Processing may fetch a token, so it runs off the
 * delegate queue, and the result is delivered back on it.
 *
 * The original delegate, if it implements this callback, decides where the
 * redirect goes, so it is offered the follow-up with Approov's changes taken
 * out, and the request it completes with is the one processed: a delegate that
 * sends the redirect elsewhere gets protection decided for that destination,
 * and one that refuses the redirect (nil) is honoured. Without such a delegate
 * the follow-up is processed directly. Either way the completion handler is
 * called here: this delegate implements the method, so URLSession waits for
 * the handler, and leaving it uncalled would stall the task indefinitely.
 *
 * https://developer.apple.com/documentation/foundation/nsurlsessiontaskdelegate/1411626-urlsession?language=objc
 */
- (void)URLSession:(NSURLSession *)session
                          task:(NSURLSessionTask *)task
    willPerformHTTPRedirection:(NSHTTPURLResponse *)response
                    newRequest:(NSURLRequest *)request
             completionHandler:(void (^)(NSURLRequest *))completionHandler {
  NSURLRequest *previous = task.currentRequest ?: task.originalRequest;
  NSMutableURLRequest *followUp =
      [PinningURLSessionDelegate requestByUndoingApproovChangesIn:request
                                                  previousAttempt:previous];
  ApproovService *service = [self currentApproovService];
  id<NSURLSessionDataDelegate> originalDelegate = _originalDelegate;

  void (^protectAndComplete)(NSURLRequest *) = ^(NSURLRequest *chosen) {
    if (chosen == nil) {
      completionHandler(nil);
      return;
    }
    // A delegate may build its request from the task's current request, which
    // still carries what Approov applied, so take that out again.
    NSMutableURLRequest *clean =
        [PinningURLSessionDelegate requestByUndoingApproovChangesIn:chosen
                                                    previousAttempt:previous];
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
      NSURLRequest *prepared = clean;
      NSString *failure = nil;
      if (service != nil) {
        ApproovInterceptorResult *result = [service interceptRequest:clean];
        if (result.action == ApproovInterceptorActionProceed) {
          NSMutableURLRequest *processed = [result.request mutableCopy];
          NSError *mutatorError = nil;
          if ([[ApproovServiceMutatorBridge shared]
                  processRequest:processed
                     tokenHeader:[ApproovService sharedTokenHeader]
                   traceIDHeader:[ApproovService sharedTraceIDHeader]
                    errorPointer:&mutatorError]) {
            [PinningURLSessionDelegate recordPreApproovRequest:clean
                                            onProcessedRequest:processed];
            prepared = processed;
          } else {
            failure = mutatorError.localizedDescription ?:
                @"mutator processing failed";
          }
        } else {
          failure = result.message ?: @"request rejected";
        }
      }

      void (^deliver)(void) = ^{
        if (failure != nil) {
          // Fail the task rather than hand the app the redirect response as
          // if it were the final one.
          ApproovLogE(@"redirect to %@ not followed: %@", clean.URL, failure);
          [task cancel];
          completionHandler(nil);
          return;
        }
        ApproovLogD(@"redirect %@ -> %@ reprocessed", previous.URL, prepared.URL);
        completionHandler(prepared);
      };
      NSOperationQueue *queue = session.delegateQueue;
      if (queue != nil)
        [queue addOperationWithBlock:deliver];
      else
        deliver();
    });
  };

  if ([originalDelegate respondsToSelector:@selector
                        (URLSession:
                               task:willPerformHTTPRedirection:newRequest
                                   :completionHandler:)])
    [originalDelegate URLSession:session
                            task:task
      willPerformHTTPRedirection:response
                      newRequest:followUp
               completionHandler:protectAndComplete];
  else
    protectAndComplete(followUp);
}

/**
 * Tells the delegate that the data task received the initial reply (headers)
 * from the server. This is passed to the original delegate if it implements
 * it; otherwise the response is allowed here, which is URLSession's own
 * default, since an uncalled handler would stall the task.
 *
 * https://developer.apple.com/documentation/foundation/urlsessiondatadelegate/1410027-urlsession?language=objc
 */
- (void)URLSession:(NSURLSession *)session
              dataTask:(NSURLSessionDataTask *)dataTask
    didReceiveResponse:(NSURLResponse *)response
     completionHandler:(void (^)(NSURLSessionResponseDisposition disposition))
                           completionHandler {
  if ([_originalDelegate respondsToSelector:@selector
                         (URLSession:
                             dataTask:didReceiveResponse:completionHandler:)])
    [_originalDelegate URLSession:session
                         dataTask:dataTask
               didReceiveResponse:response
                completionHandler:completionHandler];
  else
    completionHandler(NSURLSessionResponseAllow);
}

/**
 * Tells the delegate that the data task has received some of the expected data.
 * This is simply passed to the original delegate.
 *
 * https://developer.apple.com/documentation/foundation/urlsessiondatadelegate/1411528-urlsession?language=objc
 */
- (void)URLSession:(NSURLSession *)session
          dataTask:(NSURLSessionDataTask *)dataTask
    didReceiveData:(NSData *)data {
  if ([_originalDelegate
          respondsToSelector:@selector(URLSession:dataTask:didReceiveData:)])
    [_originalDelegate URLSession:session
                         dataTask:dataTask
                   didReceiveData:data];
}

/**
 * Tells the delegate that the task finished transferring data. This is simply
 * passed to the original delegate.
 *
 * https://developer.apple.com/documentation/foundation/nsurlsessiontaskdelegate/1411610-urlsession?language=objc
 */
- (void)URLSession:(NSURLSession *)session
                    task:(NSURLSessionTask *)task
    didCompleteWithError:(NSError *)error {
  if ([_originalDelegate
          respondsToSelector:@selector(URLSession:task:didCompleteWithError:)])
    [_originalDelegate URLSession:session task:task didCompleteWithError:error];
  if (error) {
    ApproovLogE(@"session task completed with error: %@",
                error.debugDescription);
  }
}

/**
 * Tells the URL session that the session has been invalidated. This is simply
 * passed to the original delegate.
 *
 * https://developer.apple.com/documentation/foundation/nsurlsessiondelegate/1407776-urlsession
 */
- (void)URLSession:(NSURLSession *)session
    didBecomeInvalidWithError:(NSError *)error {
  if ([_originalDelegate respondsToSelector:@selector(URLSession:
                                                didBecomeInvalidWithError:)])
    [_originalDelegate URLSession:session didBecomeInvalidWithError:error];
  if (error) {
    ApproovLogE(@"session did become invalid with error: %@",
                error.debugDescription);
  }
}

@end
