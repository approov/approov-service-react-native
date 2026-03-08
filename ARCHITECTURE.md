# Approov React Native Service Layer Architecture

This document explains the design principles, historical challenges, and current robustness of the Approov React Native SDK’s network interception layer on iOS and Android.

## 1. Our Objective: Seamless Integration
Our primary goal is to provide a seamless, "free ride" integration experience for React Native developers. Instead of requiring developers to rewrite their application’s network requests using a proprietary, custom API, our service layer aims to automatically secure the standard, global React Native `fetch()` and `XMLHttpRequest` calls.

By hooking into the underlying native networking components that React Native uses, we can invisibly inject Approov tokens into headers and enforce dynamic certificate pinning. Developers get advanced security for all their existing API requests with minimal code changes.

To achieve this, the Approov service layer intercepts the following:
- **iOS:** React Native uses `NSURLSession` under the hood (specifically `RCTHTTPRequestHandler`). We use Objective-C Method Swizzling to intercept these sessions and inject our logic.
- **Android:** React Native uses a singleton `OkHttpClient`. We inject a custom `ApproovInterceptor` and `ApproovCertificatePinner` into the OkHttp builder factory to secure the requests.

---

## 2. iOS Interception Challenges and Solutions

On iOS, the interception relies on **Method Swizzling** — a technique that changes the implementation of Objective-C methods at runtime.

### The Architectural Timeline Constraint
While React Native itself exclusively builds `NSURLRequest` objects and calls `dataTaskWithRequest:`, the primary interception challenge comes from the inherent timeline of how React Native initializes on iOS:

1. App launches
2. iOS creates the `RCTBridge`
3. The `RCTNetworking` module loads and **creates its `NSURLSession` instance immediately** (securing its internal delegate).
4. The React Native JavaScript bundle executes.
5. `ApproovService.initialize(configString)` is eventually called from JavaScript.

Because the core React Native networking session is created *before* the Javascript code even executes to initialize Approov, we cannot hook the `sessionWithConfiguration:delegate:delegateQueue:` method to capture that initial session creation.

### Protection Scenarios and Edge Cases
To handle this timeline and the presence of third-party SDKs, our swizzling strategy is designed to intercept tasks right as they are launched, even if we missed the session creation. The table below outlines exactly which React Native networking scenarios we successfully protect, and the edge cases that bypass interception.

| Scenario | Network Call Source | Swizzle Result | Is Protected? | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Standard Fetch (Post-Init)** | Standard JS `fetch()` called after `ApproovService.initialize()` | `dataTaskWithRequest:` is intercepted. Interceptor recognizes the React Native `RCTHTTPRequestHandler` session. | ✅ **Yes** | The happy path. Token is added, and pinning delegate processes the TLS handshake. |
| **Early Fetch (Pre-Init)** | JS `fetch()` called *before* `ApproovService.initialize()` finishes. | Interceptor is bypassed because swizzles are not active yet, or because Approov refuses to add a token before config is ready. | ❌ **No** | Developers must ensure `fetch` calls await the `ApproovProvider` initialization completion. |
| **Late URL Convenience Methods** | A specialized 3rd party React Native native module (e.g., a fast image downloader) makes calls reusing the React Native `RCTHTTPRequestHandler` session, but chooses to call `dataTaskWithURL:` internally instead of `dataTaskWithRequest:`. | `dataTaskWithURL:` is intercepted, converted to an `NSURLRequest`, and funneled into standard processing. | ✅ **Yes** | Previously an edge case bypass. Because our old hook only watched the `*WithRequest:` door, traffic entering the same protected session through the `*WithURL:` door snuck out without an Approov token. |
| **Custom 3rd Party Session (Unrecognized Delegate)** | A 3rd party native module creates its completely own `NSURLSession` instance with a custom delegate. | Task is intercepted at the global class level, but Approov recognizes the delegate does not match React Native's `RCTHTTPRequestHandler`. | ❌ **No** | Approov explicitly skips interception to prevent crashing third-party logic. Developers must add the custom delegate class via `ApproovService.addAllowedDelegate()` to protect it. |
| **Aggressive Swizzling Conflicts** | An observability SDK (like Datadog) globally swizzles all `NSURLSession` methods to track metrics. If they swizzle *after* Approov, or wrap/hijack the specific React Native task delegates globally, they sever our hooks. | Our hook is bypassed or our Delegate is swallowed by the observability SDK. | ❌ **No** | We log `IMP CONFLICT` warnings. Use `fetchWithApproov` to guarantee protection if the race condition cannot be won. |
| **Custom Network Stack** | 3rd party framework completely bypasses `NSURLSession` (e.g., uses low-level sockets or CFNetwork directly). | No swizzles are triggered at all. | ❌ **No** | Approov only protects `NSURLSession`-based networking on iOS. |

### What Could Go Wrong Before
Previously, the Approov React Native interceptor had several vulnerabilities to these complex environments:
1. **Late Initialization:** Swizzling was performed when the React Native module initialized natively (`startWithApproovService:`). This could be delayed or happen after other aggressive SDKs had already hooked the network stack.
2. **Missing Swizzle Targets:** We primarily swizzled `dataTaskWithRequest:`, occasionally missing alternative convenience APIs like `dataTaskWithURL:` which some libraries or modified React Native networking stacks might use.
3. **Silent Failures:** If another SDK overwrote our swizzled implementation pointers (IMPs) later in the app's lifecycle, there was no visibility into the broken state. This led to silent failures where Approov appeared active but was simply being bypassed.

### Current State and Fixes
To fortify the iOS interceptor against these race conditions, several critical improvements were implemented:
1. **`+load` Time Swizzling:** We migrated the swizzle installation to the Objective-C class `+load` method. This guarantees our hooks are installed at the absolute earliest possible moment during runtime initialization, ensuring we sit securely at the bottom of the swizzle stack and intercept before other SDKs.
2. **Comprehensive Hooking:** We expanded the swizzles to cover `dataTaskWithURL:` and its completion handler variants, ensuring all entry points to session tasks are intercepted and routed through our protection logic.
3. **IMP Integrity Checking:** We implemented a system that records the implementation pointers (IMPs) of the original methods during `+load`. When sessions are fired later, the interceptor verifies that the IMPs have not been overwritten and logs a conspicuous warning (`IMP CONFLICT`) if another SDK has compromised our hooks.

---

## 3. Android Interception Challenges and Solutions

On Android, React Native networking relies on a singleton `OkHttpClient` managed dynamically by the `OkHttpClientProvider`.

### Why Issues Occur in Complex Apps
Similar to iOS, small applications face no issues as the default client is built and retained. However, the `OkHttpClientProvider` allows other native React Native modules to invoke `OkHttpClientProvider.setOkHttpClientFactory()` to inject their own custom HTTP client builders. 

If another observability or network inspection SDK sets a new factory *after* Approov has injected its interceptor and pinner, the entire client is overwritten. The new client provided by the third-party SDK will not have the Approov components attached, silently bypassing security.

### Current State and Fixes
To detect and recover from these issues, diagnostics and a safe healing mechanism were introduced:
1. **Diagnostics (`getPinningDiagnostics`):** Developers can query the runtime state from JavaScript to continuously monitor if the Approov `ApproovInterceptor` and `ApproovCertificatePinner` are still present in the active `OkHttpClient` chain.
2. **Healing (`updateClientFactory`):** If a bypass is detected, this API allows the app to dynamically re-inject Approov protections back into the *current* `OkHttpClient`. This safely layers Approov on top of the other SDK's modifications without breaking their observability functionality.

---

## 4. The `fetchWithApproov` Alternative

Despite our robust hooking mechanisms, highly aggressive third-party SDKs might still find ways to circumvent global interceptions or severely mutate the global network clients, leading to persistent drops in protection for critical API calls.

To provide a guaranteed, conflict-free path for sensitive requests, we introduced the **`fetchWithApproov` API**.

### How it Fits as an Alternative
`ApproovService.fetchWithApproov` is a JavaScript drop-in replacement for the standard `fetch()` API. Instead of routing through React Native's global `NetworkingModule` (which is subject to swizzling and OkHttp factory overrides), it bridges directly to isolated, natively protected HTTP clients:
- On Android, it builds and utilizes an independent `OkHttpClient` configured directly with the native iOS `ApproovClientBuilder`.
- On iOS, it uses a standalone `NSURLSession` with its own dedicated pinning delegate.

By completely bypassing the shared React Native networking stack, these isolated clients are immune to the global swizzling or factory overrides applied by other SDKs. For critical authentication or transaction endpoints, `fetchWithApproov` ensures 100% protection reliability.

### Inconveniences and Trade-offs
Because `fetchWithApproov` bypasses the core React Native networking bridge events and handles data transformation independently across the native bridge, it has major limitations compared to the standard `fetch()`:
- **No `FormData` Streaming:** It does not support streaming or uploading multipart `FormData` (e.g. uploading large images via URIs).
- **No Binary `Blob` Support:** It cannot handle binary `Blob` or `ArrayBuffer` bodies natively; everything must be stringified or base64 encoded by the caller.
- **No Request Cancellation:** It does not support the `AbortController` API to cancel inflight requests.
- **Memory Buffering Constraints:** Large responses are buffered entirely in memory before crossing the React Native bridge, rather than streaming in chunks.

As a result, `fetchWithApproov` is specifically designed for standard JSON / Text REST API payloads where guaranteed security is paramount, serving as a reliable fallback when global interception is untenable in complex app environments.
