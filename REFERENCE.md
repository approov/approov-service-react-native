# Reference
This provides a reference for all of the methods defined on `ApproovService`. These are available if you import:

```Javascript
import { ApproovProvider, ApproovService } from '@approov/approov-service-react-native';
```

Many of the methods execute asynchronously and return a `Promise`. This is resolved to indicate success, or rejected with an `error` otherwise. The `error` is a map that provides:

* `message`: A descriptive error message.
* `userInfo.type`: Type of the error which may be `general`, `network` or `rejection`. If the type is `network` then this indicates that the error was caused by a temporary networking issue, so an option should be provided to the user to retry.
* `userInfo.rejectionARC`: Only provided for a `rejection` error type. Provides the [Attestation Response Code](https://approov.io/docs/latest/approov-usage-documentation/#attestation-response-code), which could be provided to the user for communication with your app support to determine the reason for failure, without this being revealed to the end user.
* `userInfo.rejectionReasons`: Only provided for a `rejection` error type. If the [Rejection Reasons](https://approov.io/docs/latest/approov-usage-documentation/#rejection-reasons) feature is enabled, this provides a comma separated list of reasons why the app attestation was rejected.

## initialize
You will not generally need to call this function directly, since this is called automatically if you use the `ApproovProvider` component. It is only included here for completeness.

Initializes the Approov SDK and thus enables the Approov features. The `config` will have been provided in the initial onboarding or email or can be [obtained](https://approov.io/docs/latest/approov-usage-documentation/#getting-the-initial-sdk-configuration) using the Approov CLI. This will generate an error if a second attempt is made at initialization with a different `config` but will succeed if called multiple times with the same `config`.

```Javascript
ApproovService.initialize(config: string);
```

This function returns a `Promise` that is resolved when the operation is completed. You should always make this call soon after your app is started. Other network requests may be delayed for a short period until this call is made.

## fetchWithApproov
Provides a drop-in replacement for the standard JavaScript API `fetch()`, executed entirely on an isolated, natively protected HTTP client. Use this if standard `fetch()` interception via swizzling is failing due to conflicts with other observability SDKs. 

```Javascript
ApproovService.fetchWithApproov(input: string | Request, init?: RequestInit);
```

- `input` (string | Request): The URL to fetch, or a WHATWG `Request` object containing the URL and method.
- `init` (RequestInit, optional): An options object containing standard fetch properties like `method`, `headers`, and `body`.

This function returns a `Promise` that resolves to a standard WHATWG `Response` object. Note that because this wrapper is designed for standard JSON/Text API communication across the React Native bridge, it does not support streaming `FormData`, binary `Blob` bodies, or `AbortController` cancellation.

## setProceedOnNetworkFail
*OBSOLETE:* Do not use this method. It is deprecated and does nothing.

```Javascript
ApproovService.setProceedOnNetworkFail();
```

## setUseApproovStatusIfNoToken
Sets a flag indicating if the Approov fetch status should be used as the token header value if the actual token fetch fails or returns an empty token. This allows your backend to distinguish between different failure reasons (e.g., `NO_NETWORK`, `MITM_DETECTED`) even when the `Approov-Token` would otherwise be empty or missing.

```Javascript
ApproovService.setUseApproovStatusIfNoToken(shouldUse: boolean);
```

When enabled, if the Approov token fetch fails or returns an empty token, the `Approov-Token` header will be populated with the status string (with the configured prefix) instead of being left empty.

## setLogLevel
Sets the logging level for the native Approov SDK integration. This governs how much information is printed to the native console (Android Logcat or iOS OSLog/console).

```Javascript
ApproovService.setLogLevel(level: number);
```

The `level` should be one of the constants provided in `ApproovService.Log` (e.g., `ApproovService.Log.DEBUG`, `ApproovService.Log.INFO`, `ApproovService.Log.WARN`, `ApproovService.Log.ERROR`, `ApproovService.Log.EXTREME`, or `ApproovService.Log.NONE`).

## addAllowedDelegate
Registers a custom `NSURLSessionDelegate` class name (or a regex pattern matching class names) to be intercepted by Approov on iOS. By default, the React Native SDK automatically intercepts known delegates (like `RCTHTTPRequestHandler`). If you use a third-party networking library that employs its own custom `NSURLSessionDelegate`, you must add its class name here *before* initialization so Approov knows to protect those sessions.

```Javascript
ApproovService.addAllowedDelegate(delegatePattern: string);
```

* `delegatePattern` (string): The exact class name or a regular expression string matching the class name of the delegate you wish to intercept.

This method only affects the iOS networking stack; it is a no-op on Android.

## setSuppressLoggingUnknownURL
Indicates that logging should be suppressed for requests to domains that have not been added in Approov. These requests would normally cause a `UNKNOWN_URL` (Android) or `unknown URL` (iOS) to be generated. Use this option if you wish to reduce the amount of logging being generated.

```Javascript
ApproovService.setSuppressLoggingUnknownURL();
```

Note that this also suppresses logging generated for domains that match a criteria set with `addExclusionURLRegex`.

You are encouraged to make this call inside the `approovSetup` function called by the `ApproovProvider`, to ensure this is setup prior to Approov initialization.

## setTokenHeader
Sets the header that the Approov token is added on, as well as an optional prefix String (such as "`Bearer `"). Pass in an empty string if you do not wish to have a prefix. By default the token is provided on `Approov-Token` with no prefix.

```Javascript
ApproovService.setTokenHeader(header: string, prefix: string);
```

You are encouraged to make this call inside the `approovSetup` function called by the `ApproovProvider`, to ensure this is setup prior to Approov initialization.

## setTraceIDHeader
Sets a header to be used to include a trace ID in subsequent network requests. If a header is set, then a random identifier is added to the request headers when an Approov token is fetched natively, to uniquely identify the request for diagnostic purposes.

```Javascript
ApproovService.setTraceIDHeader(header: string);
```

You are encouraged to make this call inside the `approovSetup` function called by the `ApproovProvider`.

## getTraceIDHeader
Gets the trace ID header that was previously set by `setTraceIDHeader` or through the configuration properties.

```Javascript
ApproovService.getTraceIDHeader();
```

This function returns a `Promise` providing the result.

## setBindingHeader
Sets a [binding header](https://ext.approov.io/docs/latest/approov-usage-documentation/#token-binding) that may be present on requests being made. This is for the [token binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) feature. A header should be chosen whose value is unchanging for most requests (such as an Authorization header). If the header is present, then a hash of the header value is included in the issued Approov tokens to bind them to the value. This may then be verified by the backend API integration.

```Javascript
ApproovService.setBindingHeader(header: string);
```

You are encouraged to make this call inside the `approovSetup` function called by the `ApproovProvider`, to ensure this is setup prior to Approov initialization.

## addSubstitutionHeader
Adds the name of a header which should be subject to [secure strings](https://ext.approov.io/docs/latest/approov-usage-documentation/#secure-strings) substitution. This means that if the header is present then the value will be used as a key to look up a secure string value which will be substituted into the header value instead. This allows easy migration to the use of secure strings. A required prefix may be specified to deal with cases such as the use of "Bearer " prefixed before values in an authorization header.

```Javascript
ApproovService.addSubstitutionHeader(header: string, requiredPrefix: string);
```

You are encouraged to make this call inside the `approovSetup` function called by the `ApproovProvider`, to ensure this is setup prior to Approov initialization.

## removeSubstitutionHeader
Removes a header previously added using addSubstitutionHeader.

```Javascript
ApproovService.removeSubstitutionHeader(header: string);
```

## addSubstitutionQueryParam
Adds a `key` name for a query parameter that should be subject to [secure strings](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) substitution. This means that if the query parameter is present in a URL then the value will be used as a key to look up a secure string value which will be substituted as the query parameter value instead. This allows easy migration to the use of secure strings.

```Javascript
ApproovService.addSubstitutionQueryParam(key: string);
```

You are encouraged to make this call inside the `approovSetup` function called by the `ApproovProvider`, to ensure this is setup prior to Approov initialization.

## removeSubstitutionQueryParam
Removes a query parameter key name previously added using addSubstitutionQueryParam.

```Javascript
ApproovService.removeSubstitutionQueryParam(key: string);
```

## addExclusionURLRegex
Adds an exclusion URL regular expression. If a URL for a request matches this regular expression
then it will not be subject to any Approov protection. Note that this facility must be used with
*EXTREME CAUTION* due to the impact of dynamic pinning. Pinning may be applied to all domains added
using Approov, and updates to the pins are received when an Approov fetch is performed. If you
exclude some URLs on domains that are protected with Approov, then these will be protected with
Approov pins but without a path to update the pins until a URL is used that is not excluded. Thus
you are responsible for ensuring that there is always a possibility of calling a non-excluded
URL, or you should make an explicit call to fetchToken if there are persistent pinning failures.
Conversely, use of those option may allow a connection to be established before any dynamic pins have been received via Approov. thus potentially opening the channel to a MitM.

```Javascript
ApproovService.addExclusionURLRegex(urlRegex: string);
```

You are encouraged to make this call inside the `approovSetup` function called by the `ApproovProvider`, to ensure this is setup prior to Approov initialization.

## removeExclusionURLRegex
Removes an exclusion URL regular expression previously added using addExclusionURLRegex.

```Javascript
ApproovService.removeExclusionURLRegex(urlRegex: string);
```

## prefetch
*OBSOLETE:* the prefetch operation is now performed automatically by the platform SDK upon invoking `initialize`.

Prefetches to lower the effective latency of a subsequent token or secure string fetch by starting the operation earlier so the subsequent fetch may be able to use cached data.

```Javascript
ApproovService.prefetch();
```

## precheck
Helps developers verify that Approov is correctly integrated and the environment is set up properly, especially during initial development and onboarding. Performs a precheck to determine if the app will pass attestation. This requires [secure strings](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) to be enabled for the account, although no strings need to be set up. If the
attestation fails for any reason then the provided promise is rejected with a
description in the message field. If the userInfo.type field is "network" then that
indicates the failure was due to networking issues and a user initiated retry should be
allowed. If the userInfo.type field is "rejection" then this indicates the attestation was
rejected and the userInfo.rejectionARC and userInfo.rejectionReasons fields may provide
additional detail.

```Javascript
ApproovService.precheck();
```

This function returns a `Promise` that is resolved when the operation is completed. It is rejected if the `precheck` failed.

## getDeviceID
Gets the [device ID](https://approov.io/docs/latest/approov-usage-documentation/#extracting-the-device-id)  used by Approov to identify the particular device that the SDK is running on. Note
that different Approov apps on the same device will return a different ID. Moreover, the ID may be
changed by an uninstall and reinstall of the app.

```Javascript
ApproovService.getDeviceID();
```

This function returns a `Promise` providing the result.

## setDataHashInToken
Directly sets the [token binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) hash from the given `data` to be included in subsequently fetched Approov tokens. If the hash is
different from any previously set value then this will cause the next token fetch operation to
fetch a new token with the correct payload data hash. The hash appears in the
'pay' claim of the Approov token as a base64 encoded string of the SHA256 hash of the
data. Note that the data is hashed locally and never sent to the Approov cloud service.
This is an alternative to using `setBindingHeader` and you should not use both methods at the same time.

```Javascript
ApproovService.setDataHashInToken(data: string);
```

This function returns a `Promise` that is resolved when the operation is completed.

## setDevKey
[Sets a development key](https://approov.io/docs/latest/approov-usage-documentation/#using-a-development-key) in order to force an app to be passed. This can be used if the app has to be resigned in a test environment and would thus fail attestation otherwise.

```Javascript
ApproovService.setDevKey(devKey: string);
```

This function returns a `Promise` that is resolved when the operation is completed.

## fetchToken
Performs an Approov token fetch for the given `url`. This should be used in situations where it is not possible to use the networking interception to add the token. This will likely require network access so may take some time to complete.

```Javascript
ApproovService.fetchToken(url: string);
```

This function returns a `Promise` providing the result.

## getMessageSignature
Gets the [message signature](https://ext.approov.io/docs/latest/approov-usage-documentation/#account-message-signing) for the given `message`. This uses an account specific message signing key that is transmitted to the SDK after a successful fetch if the facility is enabled for the account. Note that if the attestation failed then the signing key provided is actually random so that the signature will be incorrect. An Approov token should always be included in the message being signed and sent alongside this signature to prevent replay attacks.

```Javascript
ApproovService.getMessageSignature(message: string);
```

This function returns a `Promise` providing the result.

## fetchSecureString
Fetches a [secure string](https://approov.io/docs/latest/approov-usage-documentation/#secure-strings) with the given `key`. If `newDef` is not `null` then a secure string for the particular app instance may be defined. In this case the new value is returned as the secure string. Use of an empty string for `newDef` removes the string entry. Note that the returned string should NEVER be cached by your app, you should call this function when it is needed.

```Javascript
ApproovService.fetchSecureString(key: string, newDef: string);
```

This function returns a `Promise` providing the result, which may be `null` if the `key` is not defined.

Most often, secure strings are placed in headers using `addSubstitutionHeader` for convenience. If you need to use a secure string in the body or another part of the request, call `fetchSecureString` directly and add the value where appropriate.

## fetchCustomJWT
Fetches a [custom JWT](https://approov.io/docs/latest/approov-usage-documentation/#custom-jwts) with the given marshaled JSON `payload`.

```Javascript
ApproovService.fetchCustomJWT(payload: string);
```

This function returns a `Promise` providing the result.

## getLastARC
Gets the last [Attestation Response Code](https://ext.approov.io/docs/latest/approov-usage-documentation/#attestation-response-code) code.

```Javascript
ApproovService.getLastARC();
```
This function returns a `Promise` providing the result.

The ARC code should ideally be returned from your server as part of a rejected API call (such as for an invalid JWT token or missing token). However, if you are unable to customize your server response to include the ARC code (for example, when using a WAF service), you can use this method to obtain the ARC code. Be aware that if the device has recently experienced a network transition or temporary connectivity loss and a request has been made without an Approov Token, you might receive an incorrect or outdated ARC code from this method if connectivity is available at the time the call is made.

## setInstallAttrsInToken
Sets an [install attributes token](https://ext.approov.io/docs/latest/approov-usage-documentation/#application-installation-attributes) to be sent to the server and associated with this particular
app installation for future Approov token fetches. The token must be signed, within its
expiry time and bound to the correct device ID for it to be accepted by the server.
Calling this method ensures that the next call to fetch an Approov
token will not use a cached version, so that this information can be transmitted to the server.

```Javascript
ApproovService.setInstallAttrsInToken(attrs: string);
```

## setMaxReswizzleAttempts
Sets the maximum number of times Approov should attempt to automatically re-swizzle its network interception hooks on iOS if it detects they have been hijacked or overwritten by another SDK (like Datadog or New Relic) at runtime.

```javascript
ApproovService.setMaxReswizzleAttempts(attempts)
```

- `attempts` (number): The maximum number of recovery attempts. Must be a positive integer or zero. The default value is 3.

## getMaxReswizzleAttempts
Gets the current maximum number of times Approov should attempt to automatically re-swizzle its network interception hooks on iOS.

```javascript
ApproovService.getMaxReswizzleAttempts().then((attempts) => { ... })
```

- Returns a `Promise<number>` resolving to the configured maximum reswizzle attempts.

## getPinningDiagnostics
Returns an object containing detailed diagnostics about the current state of certificate pinning and SDK interception. On Android, this specifically checks the `OkHttpClient` to ensure the `ApproovInterceptor` and `ApproovCertificatePinner` are active. On iOS, it provides statistics about the currently pinned and unpinned `NSURLSession` instances.

```Javascript
ApproovService.getPinningDiagnostics();
```

This function returns a `Promise` resolving to an object with the following structure:
* `isInterceptorPresent` (boolean): (Android only) True if the Approov HTTP interceptor is configured.
* `isPinnerPresent` (boolean): (Android only) True if the Approov Certificate Pinner is configured.
* `interceptors` (Array<string>): (Android only) A list of class names for all currently active interceptors.
* `sessionsWithPinning` (number): (iOS only) The number of `NSURLSession` instances currently protected by Approov pinning.
* `sessionsWithoutPinning` (number): (iOS only) The number of `NSURLSession` instances currently active without Approov pinning.
* `unpinnedSessions` (Array<{ sessionId: string; requestCount: number }>): (iOS only) Details of active sessions not currently intercepted.

## updateClientFactory
Manually forces the Approov SDK to rebuild and re-register its network client hooks. This is primarily useful on Android to recover the networking stack if a third-party SDK (like New Relic or Datadog) has overwritten the React Native `OkHttpClientFactory` *after* Approov initialization. Calling this safely layers Approov protection back onto the active network client. This method resolves immediately with `true` on iOS as no manual recovery is required.

```Javascript
ApproovService.updateClientFactory(wrapExisting: boolean);
```

* `wrapExisting` (boolean): If `true`, Approov will copy the existing client and its interceptors, preserving the functionality of the other SDK. If `false`, a completely fresh OkHttpClient is built. Usually, you should pass `true`.

This function returns a `Promise` that resolves to a boolean `true` when the operation is successfully completed.
