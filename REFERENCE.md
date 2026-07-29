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
ApproovService.initialize(config: string, comment?: string | null);
```

This function returns a `Promise` that is resolved when the operation is completed. You should always make this call soon after your app is started. Other network requests may be delayed for a short period until this call is made.

Passing an empty config string leaves the React Native service layer initialized while disabling Approov SDK processing. In that mode requests are forwarded as standard network traffic without Approov token injection, secure string substitution, message signing, or dynamic pinning.

This empty-config mode is intended as a bootstrap or bypass state for advanced integrations. A later call to `initialize()` with a non-empty valid config string is allowed and will then enable the native Approov SDK. By contrast, reinitializing from one non-empty config string to a different non-empty config string still rejects unless you are intentionally using a supported `reinit...` comment flow with the same config.

The optional `comment` parameter is an advanced native SDK feature and most applications should omit it. It is primarily intended for specialist initialization flows supported by the underlying Approov SDK, such as:

* comments starting with `reinit` to explicitly allow supported same-config runtime reinitialization
* comments starting with `options:` to pass supported initialization options on the initial non-empty initialization call

Repeated `options:...` calls are not a general runtime update mechanism and may fail even if the config string is unchanged. If you do not have a specific need for these features, pass nothing and let the default `null` value be used.

## isInitialized
Returns whether the React Native Approov service layer has been initialized.

```Javascript
ApproovService.isInitialized();
```

This function returns a `Promise<boolean>`.

This reflects service-layer readiness, not whether the native Approov SDK is actively protecting requests. For example, if you initialize with an empty config string, `isInitialized()` resolves to `true` while `isApproovEnabled()` resolves to `false`.

## isApproovEnabled
Returns whether the native Approov SDK is active and request protection is enabled.

```Javascript
ApproovService.isApproovEnabled();
```

This function returns a `Promise<boolean>`.

This only resolves to `true` after a successful initialization with a non-empty configuration string. If initialization has not happened yet, failed, or completed with an empty configuration string, it resolves to `false`.

## isInterceptorActive
Returns whether the native networking interception is currently active for the platform's HTTP library.

```Javascript
ApproovService.isInterceptorActive();
```

This function returns a `Promise<boolean>`.

- **Android:** Returns `true` if the `ApproovInterceptor` is correctly configured in the active `OkHttpClient`.
- **iOS:** Returns `true` if swizzling is active and Approov is successfully monitoring `NSURLSession` creations.

If this returns `false`, Approov is not currently intercepting or protecting network requests. On Android, you can use `updateClientFactory(true)` to attempt recovery.

## fetchWithApproov
Provides a secure `fetch()`-compatible API, executed entirely on an isolated, natively protected HTTP client. Use this if standard `fetch()` interception via swizzling is failing due to conflicts with other observability SDKs.

```Javascript
ApproovService.fetchWithApproov(input: string | Request, init?: RequestInit);
```

- `input` (string | Request): The URL to fetch, or a WHATWG `Request` object containing the URL and method.
- `init` (RequestInit, optional): An options object containing standard fetch properties like `method`, `headers`, and `body`.

This function returns a `Promise` that resolves to a standard WHATWG `Response` object. `fetchWithApproov` is intended for JSON/text APIs and does not provide full React Native `NetworkingModule` parity:

* Only string request bodies are supported (`JSON.stringify(...)` or plain text).
* No multipart form uploads (`FormData`, including file/URI-backed form parts).
* No binary request bodies (`Blob`, `ArrayBuffer`) or request/response streaming.
* No `AbortController` cancellation.
* No React Native networking event model features (for example upload/download progress hooks).

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

When enabled, the `Approov-Token` header is populated with the status string (with the configured prefix) only when the mutator allows the request to proceed without a token (for example, default `NO_APPROOV_SERVICE` handling, or custom mutator overrides). If the mutator blocks the request, no outbound request is made.

## setSessionMetadataCollectionEnabled
Enables or disables the extended session metadata ledger used by `getSessionDiagnostics()` on iOS. This ledger records extra development-time information about registered and unregistered sessions, including skipped delegates, request counts, last observed URLs, and the owning image/bundle for delegate classes.

```Javascript
ApproovService.setSessionMetadataCollectionEnabled(enabled: boolean);
```

> [!WARNING]
> This ledger is for development and troubleshooting only.
> On iOS, call `ApproovService.setSessionMetadataCollectionEnabled(false)` early in startup for production builds.
> Android does not currently store an equivalent session ledger; there this API is a parity no-op.
> The iOS diagnostics ledger is internally capped to about 1 MB as a safety backstop, but you should still disable it in release builds.

The default is `true`. This does not disable the core pinning/session registry used by Approov itself.

## getSessionMetadataCollectionEnabled
Returns whether extended session metadata collection is currently enabled.

```Javascript
ApproovService.getSessionMetadataCollectionEnabled();
```

On Android, this reflects the parity flag only; it does not imply an iOS-style diagnostic ledger is being retained.

## setLogLevel
Sets the logging level for the native Approov SDK integration. This governs how much information is printed to the native console (Android Logcat or iOS OSLog/console).

```Javascript
ApproovService.setLogLevel(level: number);
```

The `level` should be one of the constants provided in `ApproovService.Log` (e.g., `ApproovService.Log.DEBUG`, `ApproovService.Log.INFO`, `ApproovService.Log.WARN`, `ApproovService.Log.ERROR`, `ApproovService.Log.EXTREME`, or `ApproovService.Log.NONE`).

## logMessage
Emits a message to native Approov logging (Android Logcat / iOS console) from JavaScript. This is useful for correlating JS lifecycle events with native interception logs.

```Javascript
ApproovService.logMessage(message: string, level?: number);
```

* `message` (string): The message text to log.
* `level` (number, optional): One of `ApproovService.Log.*`. Defaults to `INFO` if omitted.

## addAllowedDelegate
Registers a custom `NSURLSessionDelegate` class name (or a prefix pattern matching class names using a trailing `*`) to be intercepted by Approov on iOS. By default, the React Native SDK automatically intercepts known delegates (like `RCTHTTPRequestHandler`). If you use a third-party networking library that employs its own custom `NSURLSessionDelegate`, you must add its class name here *before* initialization so Approov knows to protect those sessions.

```Javascript
ApproovService.addAllowedDelegate(delegatePattern: string);
```

* `delegatePattern` (string): The exact class name or a prefix string ending with `*` (e.g., `MySDK*`) matching the class name of the delegate you wish to intercept.

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
Sets a [binding header](https://ext.approov.io/docs/latest/approov-usage-documentation/#token-binding) that may be present on requests being made. This is for the [token binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) feature. A header should be chosen whose value is unchanging for most requests (such as an Authorization header). If the header is present, then its SHA256 hash is supplied to Approov so the issued token can carry the corresponding `pay` claim and be bound to that value. This may then be verified by the backend API integration.

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
then it will not be subject to Approov request mutation such as token injection, trace headers,
message signing, or secure string substitution. Note that this facility must be used with
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

## setServiceMutatorType
Selects the active request-handling policy from JavaScript, replacing any previously installed service mutator, with no native mutator code required. The `mask` is a proceed-bitmask listing which Approov **failure** statuses may proceed rather than block the request; assemble it from `ApproovService.ReturnDecision` bits or pass an `ApproovService.MutatorPreset` value. This is the no-native-code alternative to registering a custom `ApproovServiceMutator`. Behaviour is identical on Android and iOS.

```Javascript
ApproovService.setServiceMutatorType(mask: number, options?: { sign?: boolean });
```

* `mask` (number): A bitwise-OR of `ApproovService.ReturnDecision` flags, or an `ApproovService.MutatorPreset` value. Each failure status **in** the mask proceeds; any failure status **not** in the mask blocks the request, which then surfaces as a failed `fetch()` (`IOException` / `Network request failed` on Android, an `NSError` failure on iOS).
* `options.sign` (boolean, optional): Defaults to `true`, so the installed policy mutator preserves HTTP Message Signing. Pass `{ sign: false }` to proceed per the mask but send the request unsigned (for apps that sign elsewhere or must not double-sign). Ignored for `MutatorPreset.DEFAULT`.

`SUCCESS` always proceeds with the signed token added, and `UNKNOWN_URL` / `UNPROTECTED_URL` always proceed unmodified (forwarded without a token). These three statuses are never maskable and are not exposed as flags. When a masked failure status proceeds, pair this call with `setUseApproovStatusIfNoToken(true)` to have the fetch-status string written into the token header; otherwise the token header is emitted empty.

`ApproovService.MutatorPreset.DEFAULT` (mask `-1`) restores the built-in default mutator, which performs message signing.

The mask is validated before it is applied. It must be a finite, integral 32-bit value, and it must not set any bit outside the defined `ReturnDecision` flags (bits 0-10, i.e. `0x7ff`) — apart from the `MutatorPreset.DEFAULT` sentinel `-1`. An undefined bit names no Approov status, so it could not grant proceed to anything; a mask carrying one would install a policy that silently blocks every failure status. Such a mask is rejected (the returned promise rejects with code `setServiceMutatorType`) rather than applied, so a typo cannot masquerade as a deliberate block-everything policy.

`setServiceMutatorType` uses replace semantics: the new policy wholly replaces any previously installed mutator (last wins), so use this JavaScript API or a native custom mutator, not both. A re-initialization with a *different* config resets the mutator back to the built-in default (re-apply `setServiceMutatorType` afterwards if needed); a same-config re-initialization preserves the installed mutator.

`ApproovService.ReturnDecision` — maskable failure-status bit flags:

| Flag | Value |
| :--- | :--- |
| `NO_APPROOV_SERVICE` | `1 << 0` |
| `BAD_URL` | `1 << 1` |
| `MITM_DETECTED` | `1 << 2` |
| `NO_NETWORK` | `1 << 3` |
| `POOR_NETWORK` | `1 << 4` |
| `REJECTED` | `1 << 5` |
| `UNKNOWN_KEY` | `1 << 6` |
| `INTERNAL_ERROR` | `1 << 7` |
| `NO_NETWORK_PERMISSION` | `1 << 8` |
| `MISSING_LIB_DEPENDENCY` | `1 << 9` |
| `DISABLED` | `1 << 10` |

`ApproovService.MutatorPreset` — named masks:

| Preset | Meaning |
| :--- | :--- |
| `DEFAULT` | Restore the built-in default (message-signing) mutator (mask `-1`). |
| `ALWAYS_PROCEED` | Proceed on every failure status. |
| `PROCEED_IF_UNAVAILABLE` | Proceed only when the Approov service is unavailable (`NO_APPROOV_SERVICE`). |
| `PROCEED_DEV_CLEARTEXT` | Proceed on `BAD_URL`, forwarding non-`https` traffic; development only. |

> [!WARNING]
> Including `MITM_DETECTED` or `REJECTED` in the mask removes Approov's protection for those cases: the request proceeds **without proof of attestation** (no valid Approov token). With `MITM_DETECTED` masked, a request the SDK reports as man-in-the-middle intercepted is still sent; with `REJECTED` masked, a request from an app that failed attestation (for example tampered, repackaged, or running in a compromised environment) is still sent. Prefer the named presets, and only set these bits in a production mask deliberately.

On iOS, `NO_NETWORK_PERMISSION` and `MISSING_LIB_DEPENDENCY` have no equivalent Approov status and are inert (harmless) if included.

This function returns a `Promise` that resolves once the selected policy has been installed.

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

This function returns a `Promise` that is resolved when the operation is completed. It is rejected if the `precheck` failed. Note: The React Native service layer must be initialized before calling this method; otherwise, the promise will immediately reject with an `approov_error` code.

## getDeviceID
Gets the [device ID](https://approov.io/docs/latest/approov-usage-documentation/#extracting-the-device-id)  used by Approov to identify the particular device that the SDK is running on. Note
that different Approov apps on the same device will return a different ID. Moreover, the ID may be
changed by an uninstall and reinstall of the app.

```Javascript
ApproovService.getDeviceID();
```

This function returns a `Promise` providing the result.

## setDataHashInToken
Directly sets the [token binding](https://approov.io/docs/latest/approov-usage-documentation/#token-binding) hash from the given `data` for subsequently fetched Approov tokens. If the hash is
different from any previously set value then this will cause the next token fetch operation to
fetch a new token with the correct payload data hash. The resulting token is expected to carry the
`pay` claim as a base64 encoded string of the SHA256 hash of the data. Note that the data is hashed locally and never sent to the Approov cloud service.
This is an alternative to using `setBindingHeader` and you should not use both methods at the same time.

```Javascript
ApproovService.setDataHashInToken(data: string);
```

This function returns a `Promise` that is resolved when the operation is completed. Note: The React Native service layer must be initialized before calling this method; otherwise, the promise will immediately reject with an `approov_error` code.

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

This function returns a `Promise` providing the result. Note: The React Native service layer must be initialized before calling this method; otherwise, the promise will immediately reject with an `approov_error` code.

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

This function returns a `Promise` providing the result, which may be `null` if the `key` is not defined. Note: The React Native service layer must be initialized before calling this method; otherwise, the promise will immediately reject with an `approov_error` code.

Most often, secure strings are placed in headers using `addSubstitutionHeader` for convenience. If you need to use a secure string in the body or another part of the request, call `fetchSecureString` directly and add the value where appropriate.

## fetchCustomJWT
Fetches a [custom JWT](https://approov.io/docs/latest/approov-usage-documentation/#custom-jwts) with the given marshaled JSON `payload`.

```Javascript
ApproovService.fetchCustomJWT(payload: string);
```

This function returns a `Promise` providing the result. Note: The React Native service layer must be initialized before calling this method; otherwise, the promise will immediately reject with an `approov_error` code. The promise will also immediately reject with an `IllegalArgument` error if the payload is malformed JSON.

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

- `attempts` (number): The maximum number of recovery attempts. Must be a positive integer or zero. The default value is `0`, which disables runtime IMP recovery unless you explicitly opt in.

## getMaxReswizzleAttempts
Gets the current maximum number of times Approov should attempt to automatically re-swizzle its network interception hooks on iOS.

```javascript
ApproovService.getMaxReswizzleAttempts().then((attempts) => { ... })
```

- Returns a `Promise<number>` resolving to the configured maximum reswizzle attempts. The default is `0` (disabled).

## getPinningDiagnostics
Returns an object containing diagnostics about the current state of certificate pinning and SDK interception. On Android, this checks the active shared `OkHttpClient` to ensure the `ApproovInterceptor` and certificate pinner are still present. On iOS, it reports metadata for intercepted `NSURLSession` instances, including whether requests were observed without verified pinning.

```Javascript
ApproovService.getPinningDiagnostics();
```

This function returns a `Promise` resolving to an object with the following structure:
* `isInterceptorPresent` (boolean): (Android only) True if the Approov HTTP interceptor is configured.
* `isPinnerPresent` (boolean): (Android only) True if the Approov Certificate Pinner is configured.
* `interceptors` (Array<string>): (Android only) A list of class names for all currently active interceptors.
* `totalAuthChallenges` (number): (iOS only) Total TLS auth challenges observed across intercepted sessions.
* `totalPinned` (number): (iOS only) Number of auth challenges where pinning validation succeeded.
* `totalBlocked` (number): (iOS only) Number of auth challenges blocked by pinning.
* `sessionsWithPinning` (number): (iOS only) The number of `NSURLSession` instances currently protected by Approov pinning.
* `sessionsWithoutPinning` (number): (iOS only) The number of `NSURLSession` instances currently active without Approov pinning.
* `unpinnedSessions` (Array<{ sessionPointer: string; delegateClassName: string; requestCount: number }>): (iOS only) Details of sessions where requests were observed but pinning was not verified.

Recommended usage:

* **Android:** call this immediately before the first protected request. If `isInterceptorPresent` or `isPinnerPresent` is `false`, call `ApproovService.updateClientFactory(true)` before proceeding.
* **iOS:** call this immediately after the first protected request and inspect `sessionsWithoutPinning` and `unpinnedSessions`.

Important limitations:

* On iOS, a pre-request baseline with zero sessions is normal.
* On iOS, this method only reports on sessions that Approov successfully intercepted and registered. A completely bypassed request may not appear in this metadata and must be diagnosed from native logs.

## updateClientFactory
Manually forces the Approov SDK to rebuild and re-register its network client hooks. This is primarily useful on Android to recover the networking stack if a third-party SDK (like New Relic or Datadog) has overwritten the React Native `OkHttpClientFactory` *after* Approov initialization. Calling this safely layers Approov protection back onto the active network client. This method resolves immediately with `true` on iOS as no manual recovery is required.

```Javascript
ApproovService.updateClientFactory(wrapExisting: boolean);
```

* `wrapExisting` (boolean): If `true`, Approov will copy the existing client and its interceptors, preserving the functionality of the other SDK. If `false`, a completely fresh OkHttpClient is built. Usually, you should pass `true`.

This function returns a `Promise` that resolves to a boolean `true` when the operation is successfully completed.
