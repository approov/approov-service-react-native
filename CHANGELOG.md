# Changelog

All notable changes to this project will be documented in this file.



## [3.5.10]
- **iOS Mutator Bridge Refinement**: Fixed a condition in the iOS mutator bridge where trace ID components were incorrectly required for signing even when the trace header was absent. Header mutation keys are now only set if the corresponding header is present and non-empty.
- **Custom iOS Mutator Reliability**: Fixed an issue where custom iOS mutators could not reliably block requests during `NO_APPROOV_SERVICE` events. The native interceptor now correctly honors and propagates errors from the mutator bridge.
- **Network-Risk Handling Standardized**: Refined the default mutator behavior on both Android and iOS to consistently block/retry for `NO_NETWORK`, `POOR_NETWORK`, and `MITM_DETECTED` statuses, independent of the `setUseApproovStatusIfNoToken` flag.
- **Async ApproovProvider**: The `ApproovProvider` component now correctly `await`s asynchronous `onInit` functions and includes mount-state guards to prevent state updates on unmounted components.
- **Android fetchWithApproov Fix**: Resolved an issue on Android where `fetchWithApproov` incorrectly handled POST/PUT request bodies in some scenarios.
- **Simplified Cross-Platform API**: Added a no-op `addAllowedDelegate` method on Android to match the iOS bridge, ensuring JS-level calls are safe across both platforms.
- **Documentation & Types Sync**: Synchronized `REFERENCE.md` and TypeScript definitions with actual native diagnostic payloads, improving accuracy for `getPinningDiagnostics`.
- **iOS Swizzle Auto-Recovery**: The iOS interceptor now actively monitors its hooks during execution. If a third-party SDK overwrites the Approov hooks at runtime, it will perform an auto-recovery by re-swizzling itself back to the top of the chain.
- **Configurable Reswizzle Attempts**: Introduced `ApproovService.setMaxReswizzleAttempts(attempts)` and `ApproovService.getMaxReswizzleAttempts()` to allow developers to configure the number of times the iOS auto-recovery will trigger (defaults to 3).
- **dataTaskWithURL Coverage**: Added swizzle interception for `dataTaskWithURL:` on iOS, ensuring that 3rd party native React Native modules (like image downloaders or video players) that bypass the standard `NSMutableURLRequest` flow are still funneled through the Approov core.
- **Initialization Race Conditions Fixed**: Added early `isInitialized()` checks in the Android OkHttp interceptor and iOS `NSURLSession` interceptor to bypass the existing `Thread.sleep` / `[NSThread sleepForTimeInterval:]` blocking loops once JS React Native initialization has completed. If initialization is not awaited or never completes, both platforms still fall back gracefully with a one-time critical error log.
- **Trace ID Documentation**: Documented the `setTraceIDHeader` and `getTraceIDHeader` JS/native bridging methods in `REFERENCE.md`.
- **Developer Documentation Guides**: Significantly expanded `ARCHITECTURE.md` and `TROUBLESHOOTING.md` to explain 3rd party SDK conflicts (Android OkHttp factory overrides, iOS custom delegates), how to diagnose them via native logs/stats, and exact workflows to safely resolve them during integration.
- **iOS Message Signing with Custom Token Header**: Fixed a bug where iOS message signing (Signature/Signature-Input headers) was silently skipped when the token header was customized via `setTokenHeader`. The signing gate now uses the dynamic header name instead of the hardcoded `Approov-Token`.
- **Android PinChangeListener Leak**: Fixed a memory leak where each `fetchWithApproov` call created a new `ApproovClientBuilder` that permanently registered as a `PinChangeListener`. Ephemeral builders used for one-shot fetches now skip listener registration.
- **Android Interceptor Stacking**: Fixed a bug where repeated `updateClientFactory(true)` calls would duplicate Approov interceptors in the OkHttp chain, causing multiple token fetches and signature generations per request. The cloned builder now strips existing `ApproovInterceptor` instances before adding a fresh one.
- **iOS Mutator Bridge Full Request Copy-Back**: The iOS `ApproovServiceMutatorBridge` now copies back all request properties (URL, method, body, timeout) from the mutator's processed request, not just headers. This ensures custom mutators that modify non-header fields work correctly on iOS.

## [3.5.9]
- **iOS Bridging Header Fix**: Resolved an issue where React Native apps failed to compile with the error `'approov_service_react_native-Swift.h' file not found`. The import now correctly prefers modular framework headers (`<approov_service_react_native/approov_service_react_native-Swift.h>`) and falls back to the quoted header when needed during CocoaPods compilation.
- **iOS Dynamic Framework Support**: Added `s.static_framework = true` and `s.dependency "React-Core"` to the `approov-service-react-native.podspec`. This prevents `_RCTRegisterModule` linker errors when consumers explicitly enable `use_frameworks! :linkage => :dynamic` in their Podfile, ensuring broad compatibility across standard and dynamic React Native setups.

## [3.5.8]
- **iOS Interceptor**: Redesigned `ApproovRCTInterceptor` to fix session callback handling. It now correctly intercepts sessions even when the delegate is set after session creation, ensuring compatibility with SDKs like Sentry and New Relic that use method swizzling.
- **Pinning Delegate**: Enhanced `ApproovPinningDelegate` to robustly proxy calls to the original delegate, ensuring that all `NSURLSessionDelegate` methods (including completion handlers) are correctly forwarded.
- **Dynamic Logging**: Added `ApproovService.setLogLevel(level)` to control native log verbosity at runtime. Default log level for pinning and initialization is now `INFO` (visible by default).
- **Log Constants**: Exported `ApproovService.Log` constants (DEBUG, INFO, WARN, ERROR, NONE) to JavaScript.
- **Custom Delegates**: Improved support for custom `NSURLSessionDelegate` implementations by ensuring `ApproovPinningDelegate` correctly mimics the response chain of the wrapped delegate. Added `ApproovService.addAllowedDelegate(pattern)` to register custom delegates for interception.
- **Documentation**: Added `LOGGING.md` with guides for capturing Approov logs via Sentry, Datadog, and New Relic.
- **Android OkHttp Recovery**: Introduced `ApproovService.updateClientFactory(wrapExisting)`, allowing developers to manually recover the OkHttp client and re-apply Approov interceptors if another SDK (like New Relic or Datadog) overwrites the `OkHttpClientFactory`.
- **Android Pinning Diagnostics**: Exposed `getPinningDiagnostics()` on Android to programmatically check if the `ApproovInterceptor` and `ApproovCertificatePinner` are currently active on the shared `OkHttpClient`.
- **Approov Status Fallback**: Added `ApproovService.setUseApproovStatusIfNoToken(true)`. When enabled, network failures during token fetch will allow the request to proceed, injecting the Approov Fetch status (e.g., `NO_NETWORK`, `MITM_DETECTED`) into the `Approov-Token` header.
- **HTTP Message Signing**: HTTP Message Signing is now opt-in.
- **Service Mutators**: Added comprehensive support for native `ApproovServiceMutator` implementations on both Android and iOS, mapped natively into React Native.

## [3.5.7]
- Fix iOS brace style

## [3.5.6]
- Initial changelog creation.
- Add support for New Relic NRMA session interception.
- Fixed missing promise return on success in `ApproovService.initialize` for both Android (Java) and iOS implementations.
- Add `REFERENCE.md` file documenting the public interface.

