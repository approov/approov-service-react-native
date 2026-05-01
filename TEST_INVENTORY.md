# Test Inventory

This document enumerates the current automated tests in `approov-service-react-native`.

Regression flag meaning:

- `Yes`: the test was written to stop a known bugfix or changelog item from regressing.
- `No`: the test mainly checks stable behavior or API contract rather than a specific past defect.

## Platform - React

React tests run under Jest and mock `NativeModules.ApproovService` so the JS layer can be validated independently from the native implementations.

### File: `__tests__/index.test.js`

- `fetchWithApproov forwards plain URL requests and returns a WHATWG Response` [Regression: No]: verifies that the JS wrapper normalizes headers, calls the native bridge with plain JS objects, and converts the native result back into a WHATWG `Response`. It works by mocking `NativeModules.ApproovService.fetchWithApproov`, calling `ApproovService.fetchWithApproov`, and asserting both the native call arguments and the returned response object.
- `fetchWithApproov extracts Request headers and body before crossing the native bridge` [Regression: No]: verifies that passing a real `Request` object preserves the original method/body and merges request headers with `init` headers before crossing the bridge. It works by building a browser-style `Request`, overriding a header in `init`, and asserting the native mock sees the merged header map and extracted body text.
- `fetchWithApproov preserves synthetic native retry responses as normal Responses` [Regression: Yes]: verifies that synthetic retry responses from the native layer are surfaced to JS as successful `Response` objects instead of promise rejections. It works by mocking the native bridge to return a `503` payload and asserting the JS wrapper returns a `Response` with status `503`.
- `fetchWithApproov warns and omits the body if Request extraction fails` [Regression: No]: verifies the defensive fallback when `Request.clone().text()` cannot read the body. It works by overriding `clone()` on a request to throw and asserting that a warning is logged and the native bridge is called without a body.
- `setProceedOnNetworkFail remains a deprecation no-op` [Regression: No]: verifies backward-compatible handling of the deprecated JS helper. It works by calling `ApproovService.setProceedOnNetworkFail()` and asserting it does not throw and only emits the deprecation warning.
- `exposes stable log level constants` [Regression: No]: verifies the JS export surface for native log levels. It works by reading `ApproovService.Log` and asserting the constant values are exactly the expected numeric mapping.

### File: `__tests__/approov-provider.test.js`

- `initializes Approov after onInit and exposes the ready state` [Regression: Yes]: verifies the `ApproovProvider` sequencing fix where async `onInit` must finish before native `initialize`, and the context must become ready afterward. It works by recording call order in mocked `onInit` and `initialize` functions and asserting the captured context state becomes `{ approovReady: true, approovError: null }`.
- `captures initialization failures in context and logs them` [Regression: No]: verifies that initialization failures are preserved in provider state and surfaced through native logging. It works by mocking `initialize` to reject and asserting the context carries the error and `logMessage` receives the failure message.
- `does not update or log after the provider is unmounted` [Regression: Yes]: verifies the mount-state guard added to prevent state/log updates after unmount. It works by creating a provider with a deferred initialization promise, unmounting it before resolution, then resolving it and asserting no native log call occurs.
- `useApproov throws when used outside the provider` [Regression: No]: verifies the hook contract. It works by rendering a component that calls `useApproov()` without a provider and asserting React throws the expected usage error.

### File: `__tests__/approov-monitor.test.js`

- `logs startup then ready once initialization succeeds` [Regression: No]: verifies the monitor component’s happy-path logging. It works by rendering `ApproovMonitor` inside `ApproovProvider` with a deferred `initialize` promise, then asserting `"starting"` is logged before resolution and `"ready"` after resolution.
- `logs the propagated initialization error` [Regression: No]: verifies the monitor component’s error logging path. It works by mocking `initialize` to reject and asserting the monitor logs both startup and the propagated error string.

## Platform - Android

Android tests run as JVM unit tests with JUnit and Mockito. They use mocked `Approov` SDK calls, mocked OkHttp chains, and mocked React Native bridge objects so service-layer logic can be exercised without the real SDK or network.

### File: `android/src/test/java/io/approov/reactnative/ApproovInterceptorTest.java`

- `localhostRequestsBypassApproov` [Regression: No]: verifies that localhost traffic is never sent through Approov token fetching. It works by passing a localhost request through a mocked OkHttp `Chain` and asserting the static `Approov` API is never touched.
- `uninitializedRequestsForwardWhenTheStartupWindowHasExpired` [Regression: Yes]: verifies the initialization race-condition fix where the interceptor stops blocking once the startup window expires. It works by forcing `isInitialized()` to `false`, moving the earliest request time into the past, and asserting the request is forwarded unchanged.
- `successAddsTokenTraceHeadersSubstitutionsAndMutatorChanges` [Regression: No]: verifies the full happy path for token injection, trace IDs, header substitution, query substitution, token binding, and mutator post-processing. It works by mocking a successful token fetch and secure-string substitutions, then asserting the final request contains every expected mutation.
- `networkFailuresThrowIOExceptionWithTheApproovCause` [Regression: Yes]: verifies the standardized network-risk behavior for `NO_NETWORK` on the default interceptor path. It works by mocking a `NO_NETWORK` token result and asserting interception fails with an `IOException` whose cause is `ApproovNetworkException`.
- `noApproovServiceFallsThroughWithoutAddingATokenByDefault` [Regression: No]: verifies the default `NO_APPROOV_SERVICE` behavior in the interceptor. It works by mocking that status and asserting the request proceeds without an `Approov-Token` header.
- `customMutatorCanProceedOnMitmAndExposeTheStatusHeader` [Regression: Yes]: verifies the network-risk/status-header behavior when a custom mutator chooses to proceed on `MITM_DETECTED`. It works by enabling `useApproovStatusIfNoToken`, making the mutator return `true`, and asserting the configured Approov token header carries the status value using the current token prefix. In this test setup the header is the active Approov token header and the prefix is `Bearer `, so the value becomes `Bearer MITM_DETECTED`; with no prefix configured the same header would carry `MITM_DETECTED`.
- `configChangesRefreshPinsAndNotifyListeners` [Regression: No]: verifies the dynamic configuration refresh path. It works by mocking a successful token result with `isConfigChanged() == true` and asserting `Approov.fetchConfig()` and `notifyPinChangeListeners()` are called.
- `forceApplyPinsStopsTheRequestAndNotifiesListeners` [Regression: No]: verifies the force-apply-pins safety path. It works by mocking `isForceApplyPins() == true` and asserting interception aborts with the pin-update error while still notifying pin listeners.
- `headerSubstitutionNetworkFailureSkipsTheSubstitutionButStillProceeds` [Regression: No]: verifies that a substitution fetch networking failure does not destroy an otherwise valid request. It works by mocking token success plus secure-string `NO_NETWORK`, then asserting the original header value remains and the request still proceeds.
- `queryParameterRejectionStopsTheRequest` [Regression: No]: verifies that a rejected query-parameter substitution blocks the request. It works by mocking a substitution `REJECTED` result and asserting the interceptor throws with an `ApproovRejectionException` cause.

### File: `android/src/test/java/io/approov/reactnative/ApproovServiceMutatorTest.java`

- `handlePrecheckAllowsSuccessAndUnknownKey` [Regression: No]: verifies the default mutator accepts successful and `UNKNOWN_KEY` precheck results. It works by calling `handlePrecheckResult()` with mocked statuses and asserting no exception is thrown.
- `handlePrecheckThrowsRejectionExceptionForRejectedStatus` [Regression: No]: verifies rejection prechecks become `ApproovRejectionException`. It works by mocking `REJECTED` plus ARC metadata and asserting the thrown message includes both.
- `handlePrecheckThrowsNetworkExceptionForNetworkFailures` [Regression: Yes]: verifies the default mutator treats precheck networking statuses as retryable network failures. It works by passing `NO_NETWORK` to `handlePrecheckResult()` and asserting an `ApproovNetworkException` is thrown.
- `handleFetchTokenThrowsFetchStatusExceptionForPermanentFailures` [Regression: No]: verifies permanent fetch-token failures are surfaced as `ApproovFetchStatusException`. It works by passing `BAD_URL` to `handleFetchTokenResult()` and asserting the exception message includes the status.
- `handleFetchSecureStringAllowsUnknownKeyButRejectsRejectedResults` [Regression: No]: verifies the mutator’s secure-string decision matrix. It works by first passing `UNKNOWN_KEY` successfully, then passing `REJECTED` and asserting rejection details are included in the thrown exception.
- `handleInterceptorShouldProcessRequestSkipsExcludedUrls` [Regression: No]: verifies exclusion regex handling. It works by mocking an exclusion map on `ApproovService` and asserting excluded URLs are skipped while public URLs are still processed.
- `handleInterceptorFetchTokenResultAllowsConfiguredNoApproovServiceFallback` [Regression: Yes]: verifies the `setUseApproovStatusIfNoToken(true)` fallback path for `NO_APPROOV_SERVICE`. It works by enabling the flag on a mocked service and asserting the mutator returns `true`.
- `handleInterceptorFetchTokenResultSkipsUnknownAndUnprotectedUrls` [Regression: No]: verifies that `UNKNOWN_URL` and `UNPROTECTED_URL` are skipped rather than treated as hard failures. It works by passing both statuses through the default mutator and asserting `false` is returned.

### File: `android/src/test/java/io/approov/reactnative/ApproovClientBuilderTest.java`

- `longLivedBuildersRegisterForPinChangeNotifications` [Regression: Yes]: verifies the long-lived builder path still subscribes for dynamic pin updates. It works by constructing `ApproovClientBuilder` with `ephemeral=false` and asserting `addPinChangeListener()` is called with the builder.
- `ephemeralBuildersSkipPinChangeListenerRegistration` [Regression: Yes]: verifies the memory-leak fix for one-shot builders used by `fetchWithApproov`. It works by constructing `ApproovClientBuilder` with `ephemeral=true` and asserting no pin-change listener is registered.

### File: `android/src/test/java/io/approov/reactnative/ApproovServiceRegressionTest.java`

- `fetchWithApproovRejectsNonStringMethods` [Regression: Yes]: verifies the Android `fetchWithApproov` method-type validation added in the changelog. It works by passing a mocked `ReadableMap` whose `method` key is boolean and asserting the promise is rejected with `bad_request`.
- `fetchWithApproovRejectsNonStringBodies` [Regression: Yes]: verifies the Android `fetchWithApproov` body-type validation. It works by passing a mocked `ReadableMap` whose `body` key is boolean and asserting the promise is rejected with `bad_request`.
- `fetchWithApproovPreservesPostBodiesForLocalRequests` [Regression: Yes]: verifies the POST/PUT body handling fix on Android `fetchWithApproov`. It works by spinning up a tiny localhost socket server, issuing a `POST` through `fetchWithApproov`, and asserting the raw request body arriving at the server matches the original JSON payload.
- `updateClientFactoryWrapExistingStripsDuplicateApproovInterceptors` [Regression: Yes]: verifies the interceptor-stacking recovery fix. It works by mocking an existing OkHttp client that already contains an `ApproovInterceptor`, running `updateClientFactory(true)`, capturing the replacement factory, and asserting the recovered client contains exactly one `ApproovInterceptor`.

### File: `android/src/test/java/io/approov/reactnative/ApproovDefaultMessageSigningTest.java`

- `defaultSigningAddsRequiredHeadersAndComputesTheSignature` [Regression: Yes]: verifies Android message signing adds `Content-Digest`, `Signature`, and `Signature-Input`, preserves token and trace headers, and does not leave stale signing headers behind. It works by using a deterministic test signer that captures the signature base and returns a known signature payload.
- `signingTheSameRequestTwiceDoesNotProduceDoubleSignatures` [Regression: Yes]: verifies the idempotent signing fix. It works by signing the same request twice and asserting only one `Signature` and one `Signature-Input` entry remain.
- `signingCanonicalizesRootTargetUris` [Regression: Yes]: verifies the root-path canonicalization fix for `@target-uri`. It works by signing `https://api.example.com?hello=world` and asserting the captured signature base contains `https://api.example.com/?hello=world`.
- `signingUsesDynamicTokenHeadersWithoutRequiringTraceHeaders` [Regression: Yes]: verifies dynamic token-header signing and optional trace-header handling. It works by setting `changes.tokenHeaderKey` to `X-Approov-Token`, removing the trace key, then asserting the signature base includes the custom token header and omits `approov-traceid`.

## Platform - iOS

iOS coverage is split between two custom native runners:

- `tests/ios/native/ApproovNativeTests.m` compiles Objective-C service/interceptor code against local test stubs and exercises the real native service layer without the live Approov SDK.
- `tests/ios/swift/ApproovMessageSigningTests.swift` compiles the Swift mutator/message-signing layer against lightweight stub modules and exercises real signing and bridge logic.

### File: `tests/ios/native/ApproovNativeTests.m`

- `TestInterceptRequestFailsOnBadURL` [Regression: No]: verifies `interceptRequest:` rejects requests with no valid host. It works by passing a `file:///` request and asserting the result action is `Fail` with message `BAD_URL`.
- `TestInterceptRequestForwardsLocalhost` [Regression: No]: verifies localhost bypass on iOS. It works by intercepting `https://localhost/health` and asserting the action is `Proceed` with the unchanged-forward message.
- `TestInterceptRequestForwardsWhenUninitialized` [Regression: Yes]: verifies the startup race-condition fix for the uninitialized interceptor path. It works by expiring the startup window and asserting an uninitialized request is forwarded instead of blocked.
- `TestInterceptRequestAddsTokenTraceAndFetchesConfig` [Regression: No]: verifies the successful token-fetch path. It works by queueing a successful stub token result with `configChanged = YES`, then asserting the request gains token and trace headers and `fetchConfig` is called once.
- `TestInterceptRequestCanProceedOnMitmWithStatusHeader` [Regression: Yes]: verifies the iOS mutator can explicitly proceed on `MITM_DETECTED` while exposing the fetch status in the configured Approov token header. It works by enabling `useApproovStatusIfNoToken`, installing a custom mutator bridge handler that returns `YES`, and asserting the active Approov token header carries the status string with the current token prefix applied. This test explicitly sets the prefix to `Bearer `, so the value becomes `Bearer MITM_DETECTED`; with the default empty prefix the same header would simply carry `MITM_DETECTED`.
- `TestInterceptRequestRetriesOnNetworkFailure` [Regression: Yes]: verifies the standardized retry behavior for `NO_NETWORK`. It works by queueing a `NO_NETWORK` token result and asserting the interceptor returns `Retry` with message `NO_NETWORK`.
- `TestInterceptRequestDefaultsNoApproovServiceToProceed` [Regression: No]: verifies the default `NO_APPROOV_SERVICE` path on iOS. It works by queueing that status and asserting the interceptor action is `Proceed`.
- `TestInterceptRequestHonorsCustomNoApproovServiceBlocks` [Regression: Yes]: verifies the custom-mutator reliability fix for `NO_APPROOV_SERVICE`. It works by installing a mutator bridge handler that returns `NO` and sets an `NSError`, then asserting the interceptor fails with the custom message.
- `TestHeaderSubstitutionNetworkFailureRetries` [Regression: Yes]: verifies network-risk handling for header substitution on iOS. It works by queueing a successful token fetch followed by a `NO_NETWORK` secure-string result and asserting the overall interceptor action is `Retry`.
- `TestQueryParameterSubstitutionUpdatesTheURL` [Regression: No]: verifies successful query-parameter substitution. It works by registering a substitution key, queueing a secure-string success, and asserting the returned request URL contains the substituted value.
- `TestMockStatusCompletionHandlersFire` [Regression: Yes]: verifies the `completionHandler` preservation fix for synthetic status responses. It works by creating a mock status task with a completion block and asserting the block fires with HTTP `503` and no error.
- `TestMockErrorCompletionHandlersFire` [Regression: Yes]: verifies the `completionHandler` preservation fix for synthetic error responses. It works by creating a mock error task with a completion block and asserting the block receives an `NSError` containing the encoded message.
- `TestMockUploadCompletionHandlersFire` [Regression: Yes]: verifies the upload completion-handler fix for synthetic responses. It works by creating a mock upload task and asserting the upload completion block fires with the expected HTTP status.
- `TestReactFetchStyleDataTaskWithURLReturnsSyntheticResponse` [Regression: Yes]: verifies the `dataTaskWithURL:` coverage and non-recursive synthetic retry behavior. It works by queueing `POOR_NETWORK`, creating a React-style intercepted session, calling `dataTaskWithURL:`, and asserting a single synthetic `503` response is returned after exactly one token fetch and one mutator callback.
- `TestReactFetchStylePoorNetworkReturnsSyntheticResponseWithoutRecursion` [Regression: Yes]: verifies the main iOS recursion fix for request-based intercepted tasks. It works by queueing `POOR_NETWORK`, calling `dataTaskWithRequest:` on an intercepted session, and asserting the task completes with a single synthetic `503` response and no repeated token fetch loop.
- `TestFetchWithApproovRejectsInvalidURLs` [Regression: Yes]: verifies the early iOS `fetchWithApproov` URL validation fix. It works by calling `fetchWithApproov` with `file:///tmp/no-host` and asserting the reject block fires with code `bad_url` and a descriptive message.

### File: `tests/ios/swift/ApproovMessageSigningTests.swift`

- `testDefaultSigningAddsRequiredHeadersAndComputesTheSignature` [Regression: Yes]: verifies the default Swift signer adds the required signing headers, preserves token/trace headers, replaces stale signing headers, and emits raw IEEE-P1363 install signatures. It works by running the real signer against a deterministic fixture and reading the captured signature base and signed headers from the test stubs.
- `testDefaultSigningIsIdempotentAndDoesNotDoubleSign` [Regression: Yes]: verifies the Swift idempotent-signing fix. It works by signing the same request twice and asserting the resulting `Signature` and `Signature-Input` headers contain only one signing entry each.
- `testDefaultSigningCanonicalizesRootTargetUri` [Regression: Yes]: verifies the root-path `@target-uri` fix in the Swift signer. It works by signing `https://api.example.com?hello=world` and asserting the captured signature base contains `https://api.example.com/?hello=world`.
- `testMutatorBridgeSignsWithCustomTokenHeaderAndOptionalTrace` [Regression: Yes]: verifies the iOS mutator-bridge fix that uses the dynamic token-header name and does not require a trace header when absent. It works by running the real `ApproovServiceMutatorBridge` with `X-Approov-Token` and asserting the signed request includes a signature whose base mentions the custom header but not `approov-traceid`.
- `testMutatorBridgeCopiesBackFullRequestState` [Regression: Yes]: verifies the full request copy-back fix in the mutator bridge. It works by installing a custom mutator that rewrites URL, method, body, timeout, and headers, then asserting all of those fields are copied back to the original mutable request.
- `testMutatorBridgePreservesHttpBodyStreams` [Regression: Yes]: verifies the iOS request-body bridge refinement for stream-backed bodies. It works by installing a custom mutator that replaces `httpBody` with `httpBodyStream` and then asserting the stream content, method, and mutated headers all survive copy-back.
- `testMutatorBridgePropagatesCustomFetchTokenErrors` [Regression: Yes]: verifies the mutator-bridge error propagation fix for custom fetch-token decisions. It works by installing a mutator that throws a permanent error for `NO_APPROOV_SERVICE`, then asserting the bridge returns `false` and populates the error pointer with the expected message and `type`.
