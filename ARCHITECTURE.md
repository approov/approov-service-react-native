# Approov React Native Service Layer Architecture

This document explains the design principles, historical challenges, and current robustness of the Approov React Native SDK’s network interception layer on iOS and Android.

## 1. Our Objective: Seamless Integration
Our primary goal is to provide a seamless, "free ride" integration experience for React Native developers. Instead of requiring developers to rewrite their application’s network requests using a proprietary, custom API, our service layer aims to automatically secure the standard, global React Native `fetch()` and `XMLHttpRequest` calls.

By hooking into the underlying native networking components that React Native uses, we can invisibly inject Approov tokens into headers and enforce dynamic certificate pinning. Developers get advanced security for all their existing API requests with minimal code changes.

To achieve this, the Approov service layer intercepts the following:
- **iOS:** React Native uses `NSURLSession` under the hood (specifically `RCTHTTPRequestHandler`). We use Objective-C Method Swizzling to intercept these sessions and inject our logic.
- **Android:** React Native uses a singleton `OkHttpClient`. We inject a custom `ApproovTokenInterceptor` (an OkHttp *application* interceptor that injects tokens and handles header/query mutations) and an `ApproovPinningInterceptor` (an OkHttp *network* interceptor that enforces dynamic certificate pinning against the peer-presented certificate chain) into the OkHttp client our factory builds.

---

## 2. iOS Interception Challenges and Solutions

On iOS, the interception relies on **Method Swizzling** — a technique that changes the implementation of Objective-C methods at runtime.

### The Architectural Timeline Constraint
While React Native itself exclusively builds `NSURLRequest` objects and calls `dataTaskWithRequest:`, the primary interception challenge comes from the inherent timeline of how React Native initializes on iOS:

1. App launches
2. Objective-C classes execute their `+load` methods
3. iOS creates the `RCTBridge`
4. The `RCTNetworking` module may create its `NSURLSession` instance very early
5. The React Native JavaScript bundle executes
6. `ApproovService.initialize(configString)` is eventually called from JavaScript

Because the core React Native networking session may be created *before* Javascript initialization completes, the iOS design now uses a passive `+load` probe to capture startup diagnostics without changing runtime behavior. The actual interceptor and swizzles are installed later, when the native `ApproovService` instance starts. This avoids the compatibility issues we observed when early `+load` performed real swizzling in apps that also use observability SDKs.

What `+load` solves:

* Early class/runtime state can be logged before the React Native bridge exists.
* It can prove that key `NSURLSession` classes and selectors already existed at startup.
* It can help diagnose whether an SDK had already modified runtime method implementations before Approov starts.

What `+load` does **not** solve:

* A request that leaves the device before Approov is initialized is not recoverable.
* An already-created early session cannot be retroactively registered, pinned, or mutated later.
* A later SDK can still overwrite or proxy over task creation or auth-challenge delivery.

### Protection Scenarios and Edge Cases
To handle this timeline and the presence of third-party SDKs, our swizzling strategy is designed to intercept tasks right as they are launched, even if we missed the session creation. The table below outlines exactly which React Native networking scenarios we successfully protect, and the edge cases that bypass interception.

| Scenario | Network Call Source | Swizzle Result | Is Protected? | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Standard Fetch (Post-Init)** | Standard JS `fetch()` called after `ApproovService.initialize()` | `dataTaskWithRequest:` is intercepted. Interceptor recognizes the React Native `RCTHTTPRequestHandler` session. | ✅ **Yes** | The happy path. Token is added, and pinning delegate processes the TLS handshake. |
| **Early Fetch (Pre-Init)** | JS `fetch()` called *before* `ApproovService.initialize()` finishes. | A passive `+load` probe may prove that startup networking primitives already existed, but the request itself can still leave the device before the interceptor is active. | ❌ **No** | Developers must ensure `fetch` calls await successful initialization — `await ApproovService.initialize(...)`, or the `ApproovProvider`/`useApproov()` readiness state. The request itself is not recoverable after it leaves the device. |
| **Late URL Convenience Methods** | A specialized 3rd party React Native native module (e.g., a fast image downloader) makes calls reusing the React Native `RCTHTTPRequestHandler` session, but chooses to call `dataTaskWithURL:` internally instead of `dataTaskWithRequest:`. | `dataTaskWithURL:` is intercepted, converted to an `NSURLRequest`, and funneled into standard processing. | ✅ **Yes** | Previously an edge case bypass. Because our old hook only watched the `*WithRequest:` door, traffic entering the same protected session through the `*WithURL:` door snuck out without an Approov token. |
| **Custom 3rd Party Session (Unrecognized Delegate)** | A 3rd party native module creates its completely own `NSURLSession` instance with a custom delegate. | Task is intercepted at the global class level, but Approov recognizes the delegate does not match React Native's `RCTHTTPRequestHandler`. | ❌ **No** | Approov explicitly skips interception to prevent crashing third-party logic. Developers must add the custom delegate class via `ApproovService.addAllowedDelegate()` to protect it. |
| **Aggressive Swizzling Conflicts** | An observability SDK (like Datadog) globally swizzles all `NSURLSession` methods to track metrics. If they swizzle *after* Approov, proxy the delegate, or swallow auth challenges, they may sever part or all of our hook chain. | Our hook may be bypassed, our task mutation may be skipped, or our Delegate may never receive the TLS challenge. | ❌ **No** | Runtime auto-recovery is opt-in. If enabled, Approov can log `IMP CONFLICT` warnings and attempt recovery, but a fully bypassed first request is still not recoverable. Use startup diagnostics metadata and `fetchWithApproov` for critical calls if the race cannot be controlled. |
| **Custom Network Stack** | 3rd party framework completely bypasses `NSURLSession` (e.g., uses low-level sockets or CFNetwork directly). | No swizzles are triggered at all. | ❌ **No** | Approov only protects `NSURLSession`-based networking on iOS. |

### What Could Go Wrong Before
Previously, the Approov React Native interceptor had several vulnerabilities to these complex environments:
1. **Late Initialization:** Swizzling was performed when the React Native module initialized natively (`startWithApproovService:`). This could be delayed or happen after other aggressive SDKs had already hooked the network stack.
2. **Missing Swizzle Targets:** We primarily swizzled `dataTaskWithRequest:`, occasionally missing alternative convenience APIs like `dataTaskWithURL:` which some libraries or modified React Native networking stacks might use.
3. **Silent Failures:** If another SDK overwrote our swizzled implementation pointers (IMPs) later in the app's lifecycle, there was no visibility into the broken state. This led to silent failures where Approov appeared active but was simply being bypassed.

### Current State and Fixes
To fortify the iOS interceptor against these race conditions, several critical improvements were implemented:
1. **Passive `+load` Startup Probe:** We now use `+load` only for diagnostic probing. It logs startup class and selector state without creating the interceptor or installing any swizzles. This preserves compatibility with aggressive observability SDKs while still giving us early evidence during debugging.
2. **Runtime Swizzle Installation:** The actual interceptor and swizzles are installed when the native `ApproovService` starts. This keeps the protection behavior aligned with the stable `3.5.9` lifecycle while preserving the broader interception fixes introduced on this branch.
3. **Comprehensive Hooking:** We expanded the swizzles to cover `dataTaskWithURL:` and its completion handler variants, ensuring all entry points to session tasks are intercepted and routed through our protection logic once the interceptor is active.
4. **Dynamic Service Recovery:** Pinning delegates and task interception resolve the live `ApproovService` when it becomes available, instead of permanently capturing a stale `nil` service reference.
5. **IMP Integrity Checking and Auto-Recovery:** We implemented a system that records the implementation pointers (IMPs) of the swizzled methods after runtime interception is active. When intercepted sessions are registered later, the interceptor can verify that the IMPs have not been overwritten, log a conspicuous warning (`IMP CONFLICT`) if another SDK has compromised our hooks, and attempt to re-swizzle on top again. This runtime recovery path is opt-in and disabled by default unless `setMaxReswizzleAttempts(...)` is set above `0`.

### What the Public Diagnostics Can and Cannot Prove
The public `ApproovService.getPinningDiagnostics()` metadata is designed to complement native logging during rollout:

* **Android:** it tells you whether the active shared `OkHttpClient` still contains the Approov interceptor and pinner before the first request.
* **iOS:** it tells you whether requests were observed on registered sessions without verified pinning after the first protected request.

Important limitation:

* On iOS, `getPinningDiagnostics()` only knows about sessions that actually reached Approov and were registered. A completely bypassed first request may not appear in the session metadata at all, so early rollout should always pair diagnostics metadata with native `ApproovService` logs.

---

## 3. Android Interception Challenges and Solutions

While iOS relies on Objective-C method swizzling, the Android networking layer in React Native is built entirely upon OkHttp. The networking stack uses a singleton `OkHttpClient` that is dynamically managed by the `OkHttpClientProvider`.

### How Interception Works on Android
OkHttp utilizes an **Interceptor Chain**. When a network request is fired, it passes through a sequence of registered interceptors before hitting the actual network. 

To protect React Native traffic on Android, Approov injects itself into this pipeline by:
1. Registering a custom `OkHttpClientFactory` with `OkHttpClientProvider`.
2. When the React Native networking module requests an HTTP client, our factory builds one that includes the `ApproovTokenInterceptor` (an application interceptor, to inject tokens and handle header/query mutations) and the `ApproovPinningInterceptor` (a network interceptor that enforces TLS pinning against the peer-presented certificate chain).

### The Interference: The OkHttpClientFactory Race
Similar to the swizzling race condition on iOS, there is a fundamental initialization race condition on Android when multiple SDKs (like Datadog, New Relic, or Firebase) try to monitor network traffic.

Third-party observability SDKs also need to inject their own OkHttp interceptors to track network analytics. They do this exactly the same way Approov does: by calling `OkHttpClientProvider.setOkHttpClientFactory()` to register a custom factory that attaches their interceptors.

**The Fatal Override:**
The `OkHttpClientProvider` only holds a **single** factory at a time. The last SDK to call `setOkHttpClientFactory()` wins. 

If a 3rd party SDK initializes *after* Approov (for instance, dynamically from Javascript or later in the native React application lifecycle), their factory completely overwrites Approov's factory. When React Native natively constructs its HTTP client, it uses the 3rd party SDK's factory, resulting in a client that completely lacks the `ApproovTokenInterceptor` and `ApproovPinningInterceptor`.

The result is exactly the same as an iOS bypass: React Native traffic flows normally, but it flows without Approov tokens and completely circumvents dynamic TLS pinning because the active OkHttp instance knows nothing about Approov.

### Current State and Fixes: Diagnosis and Healing
Because Android provides no global `+load` equivalent that guarantees absolute first-execution-order natively out of the box, we resolve this factory override problem dynamically and safely using **Diagnostics and Healing**:

1. **Diagnostics (Active Chain Verification):**
   Because we cannot prevent a 3rd party SDK from overwriting the factory later in the lifecycle, we provide native diagnostic routines so the app can verify its own protection status. This checks the *currently active* `OkHttpClient` singleton inside React Native to verify that the `ApproovTokenInterceptor` and `ApproovPinningInterceptor` class instances are genuinely present in the OkHttp execution chain.

2. **Safe Healing (`updateClientFactory` API):**
   If a bypass is detected (i.e., another SDK stole the factory registration), Approov provides a recovery mechanism.
   When the healing API is invoked, Approov retrieves the *currently active* custom factory (the one injected by the 3rd party SDK), proxies it to append the `ApproovTokenInterceptor` and `ApproovPinningInterceptor` to the output of their builder, and re-registers this combined factory back into the `OkHttpClientProvider`. 

This safely layers Approov directly on top of the foreign SDK's modifications. It ensures that observability metrics and tracing continue to function correctly for the 3rd party, while mathematically guaranteeing that Approov Token injection and TLS Pinning fire natively on every single Android `fetch()` request.

---

## 4. The `fetchWithApproov` Alternative

Despite our robust hooking mechanisms, highly aggressive third-party SDKs might still find ways to circumvent global interceptions or severely mutate the global network clients, leading to persistent drops in protection for critical API calls.

To provide a guaranteed, conflict-free path for sensitive requests, we introduced the **`fetchWithApproov` API**.

### How it Fits as an Alternative
`ApproovService.fetchWithApproov` is a secure `fetch()`-compatible API for sensitive calls. Instead of routing through React Native's global `NetworkingModule` (which is subject to swizzling and OkHttp factory overrides), it bridges directly to isolated, natively protected HTTP clients:
- On Android, it builds and utilizes an independent `OkHttpClient` configured directly with the `ApproovClientBuilder`.
- On iOS, it uses a standalone `NSURLSession` with its own dedicated pinning delegate.

By completely bypassing the shared React Native networking stack, these isolated clients are immune to the global swizzling or factory overrides applied by other SDKs. For critical authentication or transaction endpoints, `fetchWithApproov` ensures 100% protection reliability.

### Inconveniences and Trade-offs
Because `fetchWithApproov` bypasses the core React Native networking bridge events and handles data transformation independently across the native bridge, it has major limitations compared to the standard `fetch()`:
- **No `FormData` Streaming:** It does not support streaming or uploading multipart `FormData` (e.g. uploading large images via URIs).
- **No Binary `Blob` Support:** It cannot handle binary `Blob` or `ArrayBuffer` bodies natively; everything must be stringified or base64 encoded by the caller.
- **No Request Cancellation:** It does not support the `AbortController` API to cancel inflight requests.
- **No React Native Networking Event Model:** Features tied to RN `NetworkingModule`/`XMLHttpRequest` events (such as progress callbacks) are not available.
- **Memory Buffering Constraints:** Large responses are buffered entirely in memory before crossing the React Native bridge, rather than streaming in chunks.

As a result, `fetchWithApproov` is specifically designed for standard JSON / Text REST API payloads where guaranteed security is paramount, serving as a reliable fallback when global interception is untenable in complex app environments.
