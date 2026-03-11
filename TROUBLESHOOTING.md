# Approov React Native Troubleshooting Guide

Integrating security SDKs into cross-platform frameworks like React Native can present unique networking challenges. React Native handles networking in a highly abstracted, optimized way. This design choice, while excellent for app performance and ease of use, means that underlying HTTP clients (like `OkHttp` on Android and `NSURLSession` on iOS) are often shared, reconfigured, or modified dynamically.

This guide explains these architectural quirks, how to diagnose if the Approov service layer functionality has been overridden, and how to verify that your integration is active.

## 1. The React Native Networking Architecture

### Android: The Shared `OkHttpClient` Override Issue
React Native on Android relies on a single, shared instance of `OkHttpClient` provided by the `OkHttpClientProvider`. 

**The Problem**: Because this is a singleton factory, any native SDK (for example, analytics, performance monitoring libraries, or secondary networking libraries) can call `OkHttpClientProvider.setOkHttpClientFactory()` to inject its own custom HTTP client. If this happens *after* Approov has been initialized, the new factory will overwrite the Approov-protected client, causing all subsequent React Native `fetch()` calls to be sent without Approov tokens or certificate pinning.

**The Solution (`getPinningDiagnostics` & `updateClientFactory`)**:
To combat this dynamic environment, we provide diagnostic and healing methods. **It is crucial that this native pre-flight check is performed exactly once, BEFORE the very first `fetch()` or `axios` request is made in your application.**

```javascript
import { ApproovService } from '@approov/approov-service-react-native';

async function verifyNetworkingHealth() {
  const status = await ApproovService.getPinningDiagnostics();
  
  // On Android, check if the Approov Interceptor is still present in the chain
  if (!status.isInterceptorPresent) {
    console.warn("Approov Interceptor is missing! Another SDK likely overwrote the OkHttpClient.");
    
    // Manually heal the client. Passing 'true' preserves the other SDK's interceptors 
    // while layering Approov protection back on top.
    await ApproovService.updateClientFactory(true);
    console.log("OkHttpClient has been successfully restored.");
  }
}
```

### iOS: Swizzling and Delegate Hijacking
On iOS, React Native uses `NSURLSession` for networking. To intercept these requests, Approov uses "Method Swizzling" on the `RCTHTTPRequestHandler` to wrap the session delegate with an `ApproovPinningDelegate`. The current design uses a passive `+load` probe for startup diagnostics only; the actual swizzles are installed later when the native `ApproovService` starts.

**The Problem**: Other native iOS SDKs often use swizzling to intercept network traffic as well. If another SDK swizzles the same methods after our interceptor starts, or dynamically changes the `NSURLSessionDelegate` after the session is created, the Approov delegate might be entirely bypassed. Furthermore, if a third-party React Native library implements its own custom `NSURLSessionDelegate` class (instead of using the standard React Native ones), Approov will ignore it by default to prevent crashes.

**The Solution (`getPinningDiagnostics`, Passive Probe, Auto-Recovery, & Whitelisting)**:
To combat this, the iOS implementation does two separate things:
1. `+load` emits passive startup probe logs so you can see early runtime state without changing behavior.
2. Once the interceptor is active, it monitors its execution chain. If it detects another SDK has swizzled over the top of the Approov hooks, it will attempt an **Auto-Recovery** by re-swizzling itself back to the top of the chain (up to 3 times by default, configurable via `ApproovService.setMaxReswizzleAttempts(attempts)`).

If your sessions are continuously bypassed despite the auto-recovery, you can analyze the tracked sessions or completely bypass the React Native `NetworkingModule` by switching specific API calls to use `ApproovService.fetchWithApproov` instead. Furthermore, the Xcode console logs are your best tool during development.

```javascript
  const status = await ApproovService.getPinningDiagnostics();
  console.log(`Pinned iOS Sessions: ${status.sessionsWithPinning}`);
  console.log(`Unpinned iOS Sessions: ${status.sessionsWithoutPinning}`);
```

**Analyzing iOS Logs**:
During development, ensure `ApproovService.setLogLevel(ApproovService.Log.DEBUG)` is enabled.

*   **SUCCESS (What you want to see)**: When an API request is made, look for logs from `ApproovPinningDelegate`. You should see successful pinning checks.
*   **WARNING (What to look out for)**: If a custom network module is bypassing Approov, you will see a warning like: `SKIPPING session creation with <CustomDelegateClassName> delegate (not in interception policy)`. If you see this, and you *want* that traffic protected, you must explicitly add it using `ApproovService.addAllowedDelegate("<CustomDelegateClassName>")` before initialization.

## 2. Developer Workflow: Verifying Integration & Resolving Conflicts

When integrating Approov into a complex app already containing Analytics, Observability, or Performance SDKs, you must verify that none of those pre-existing SDKs are silently bypassing Approov. 

We highly recommend building a temporary "Networking Health Check" into your application's startup sequence during development. This guarantees your iOS delegates and Android OkHttp factories are properly hooked before you release to production.

Here is the step-by-step workflow to verify your integration and fix any conflicts you find:

### Step 1: Capture Early Diagnostics Metadata
Capture diagnostics twice and keep the output in your logs or observability breadcrumbs:
1. immediately after `ApproovService.initialize()`
2. immediately after the first protected request

```javascript
import { ApproovService } from '@approov/approov-service-react-native';
import { Platform } from 'react-native';

const startup = await ApproovService.getPinningDiagnostics();
console.log("Approov startup diagnostics:", JSON.stringify(startup, null, 2));

if (Platform.OS === 'android' &&
    (!startup.isInterceptorPresent || !startup.isPinnerPresent)) {
  await ApproovService.updateClientFactory(true);
}

await fetch("https://api.example.com/secure-data");

const postRequest = await ApproovService.getPinningDiagnostics();
console.log("Approov post-request diagnostics:", JSON.stringify(postRequest, null, 2));
```

How to read it:
* **Android:** `isInterceptorPresent` and `isPinnerPresent` must both be `true` before the first protected request.
* **iOS:** a startup baseline with zero sessions is normal. The important signal is the post-request metadata. If `sessionsWithoutPinning` is greater than `0`, Approov saw requests on registered sessions without verified pinning.
* **iOS limitation:** a completely bypassed session may not appear in this metadata at all. In that case, native logs are essential.

### Step 2: Diagnose & Fix Android Overrides
Look at the Android output from the diagnostics check:

```javascript
import { ApproovService } from '@approov/approov-service-react-native';

const status = await ApproovService.getPinningDiagnostics();
console.log("Approov Native Networking Status:", JSON.stringify(status, null, 2));
```

* **SUCCESS:** If `isInterceptorPresent: true` and `isPinnerPresent: true`, your Android integration is perfect. Approov tokens and certificate pins are actively protecting the global `fetch()` client.
* **FAILURE:** If `isInterceptorPresent: false`, another Android SDK (e.g., Firebase, Datadog) has overwritten the React Native networking factory and severed Approov's hooks.
* **THE FIX:** You must tell Approov to "heal" the factory. Wait for the conflicting SDK to finish initializing, and then execute:
  ```javascript
  // Re-injects Approov on top of the foreign SDK's modifications
  await ApproovService.updateClientFactory(true); 
  ```

### Step 3: Diagnose & Fix iOS Custom Delegates and Missing Pinning
Look at the iOS console logs (via Xcode or the Mac Console App) while your app makes network requests.
* **SUCCESS:** You see logs from `ApproovPinningDelegate` confirming that the connection to your API domain is being secured and tokenized. If `status.sessionsWithPinning` is greater than `0`, the React Native hooks are active.
* **WARNING:** If `status.sessionsWithoutPinning` is greater than `0`, Approov saw requests on registered sessions but pinning was not verified for those sessions. Inspect `unpinnedSessions` and correlate with native logs.
* **FAILURE:** You see an explicit console warning: `SKIPPING session creation with <SomeThirdPartyDelegate> (not in interception policy)`. This means a native module inside your app is bypassing the standard React Native networking stack and using its own custom session.
* **THE FIX:** If you *want* that specific traffic protected by Approov, you must explicitly whitelist that custom delegate class name *before* calling initialize:
  ```javascript
  // Tells the iOS hook it is safe to intercept this 3rd party session
  ApproovService.addAllowedDelegate("SomeThirdPartyDelegate");
  await ApproovService.initialize("<config>");
  ```

### Step 4: Watch for iOS Swizzle and Task-Path Conflicts
Some failures do not show up as simple custom delegate issues:
* `+load passive probe ...` logs mean the startup probe ran and captured runtime selector state before the interceptor started.
* `IMP CONFLICT` or `IMP RECOVERY` logs mean another SDK has overwritten one of the active iOS hooks after interceptor startup.
* `skipping dataTaskWithRequest for unregistered session` means the task path is still visible but the session was never registered.
* `forwarding without pin verification` means a challenge reached the pinning delegate before a usable service was available.

If you see these logs during rollout, treat them as an integration warning even if requests still appear to succeed.

### Step 5: The Fallback (`fetchWithApproov`)
If you have applied the fixes above, but highly aggressive 3rd party SDKs are still somehow mutating global traffic or actively stripping the `Approov-Token` header downstream, you can abandon global interception for specific, high-security endpoints. 

Simply replace `fetch('https://api.mybank.com/transfer')` with `ApproovService.fetchWithApproov('https://api.mybank.com/transfer')`. This routes the payload through completely isolated, natively built OkHttp/NSURLSession instances that are entirely immune to global swizzling or factory overrides.

## 3. Practical Guide: Testing the Integration
The ultimate test of the Approov integration is ensuring that certificate pinning blocks invalid connections. 

To verify this, you should configure a deliberate pinning failure for a staging API.

1.  **Force a Pinning Mismatch**: Use the Approov CLI to add a generic, safe test domain (for example, `stage-example.com`) and assign it an intentionally incorrect, dummy certificate pin: `approov api -add stage-example.com -pin AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=`. This approach is safer than modifying your actual staging API configuration.
2.  **Make the Request**: Launch the app and attempt to trigger a `fetch()` request to that test domain (e.g., `fetch('https://stage-example.com')`).
3.  **Observe the Failure**: 
    *   The network request should immediately fail.
    *   In the Javascript `catch` block, the error should have a `userInfo.type` of `"network"`.
    *   In the native logs (Android Logcat or Xcode Console), you should see a clear error stating that the certificate pinning check failed and the connection was dropped.

## 4. Troubleshooting Approov rejections
What can you do if you have integrated your app with Approov and your API calls are still being blocked? Consider the following steps:

1. **Check propagation delay**: Your app is attested every five minutes when active. If you recently added an app signing certificate when an app which is running then it won't have propagated. Relaunching the app may be all that is required.
2. **Verify app signing**: Is your app definitely being signed with the correct certificate?
3. **Check development environments**: Are you running with a debugger attached or on an Android emulator? You can get valid tokens by marking the signing certificate as being for development.
4. **iOS Simulator**: Are you running on an iOS simulator? These apps are not signed and are thus not recognized by default. You can get valid Approov tokens on a specific device by ensuring you are forcing a device ID to pass. As a shortcut, you can use the latest as discussed so that the device ID doesn't need to be extracted from the logs or an Approov token.
5. **Verify Security Policies**: Is the device you are using consistent with your Security Policies? For example, apps running on an Android emulator will be rejected by the default security policy. You may change devices or check the approov security policies and/or change security policies for an individual device.
6. **Live Metrics**: You can also check live metrics to identify the cause of attestation failures.
7. **Decoding Rejection Codes**: Check the `arc` code from the console logs and decode it using a `curl` command obtained from the Approov cli command `approov token -showArcInfoCurl`

## 5. Handling network errors
Networking calls are not 100% reliable, and regardless of Approov, you should have a strategy in place for when your API calls fail.

Your app will occasionally make calls to the Approov service before making your API calls to fetch a fresh token. If these fetches fail due to temporary conditions (like `NO_NETWORK` or `POOR_NETWORK`) or permanent issues (like `NO_APPROOV_SERVICE`), the behavior is entirely controlled by the native `ApproovServiceMutator`.

By default, transient network failures during an Approov token fetch (`NO_NETWORK`, `POOR_NETWORK`, `MITM_DETECTED`) cause the mutator to abort the request before it leaves the device. This is surfaced to your Javascript `fetch()` or `axios` call as a standard network error (e.g., throwing a `TypeError: Network request failed` or an `IOException`). This is consistent with standard networking paradigms and should trigger your app's existing retry or offline fallback logic.

`NO_APPROOV_SERVICE` is different: by default the request proceeds without an Approov token so your backend can apply its own policy.

**Customizing Network Failure Behavior:**
By default, the `ApproovServiceMutator.DEFAULT` blocks on network-risk statuses (`NO_NETWORK`, `POOR_NETWORK`, `MITM_DETECTED`) and proceeds on `NO_APPROOV_SERVICE`. If you prefer different behavior for any status (for example, allowing or blocking specific statuses for specific hosts), you must perform a two-step configuration:

1. **Implement a Custom Native Mutator (Required):** You must write a custom `ApproovServiceMutator` in Java/Swift that overrides `handleInterceptorFetchTokenResult` and returns `true` for the specific statuses or hostnames you want to allow through. (See `USAGE.md` for examples).
2. **Use `setUseApproovStatusIfNoToken(true)` (Optional):** Whenever the mutator allows a request to proceed without a token (either by default behavior such as `NO_APPROOV_SERVICE`, or by your custom override), this JS function causes the interceptor to write the failure status (e.g., `NO_APPROOV_SERVICE`, `MITM_DETECTED`, `POOR_NETWORK`) into the `Approov-Token` header. If you skip this step, the header is simply omitted or left empty.
