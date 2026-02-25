# Approov React Native Troubleshooting Guide

Integrating security SDKs into cross-platform frameworks like React Native can present unique networking challenges. React Native handles networking in a highly abstracted, optimized way. This design choice, while excellent for app performance and ease of use, means that underlying HTTP clients (like `OkHttp` on Android and `NSURLSession` on iOS) are often shared, reconfigured, or modified dynamically.

This guide explains these architectural quirks, how to diagnose if the Approov service layer functionality has been overridden, and how to verify that your integration is active.

## 1. The React Native Networking Architecture

### Android: The Shared `OkHttpClient` Override Issue
React Native on Android relies on a single, shared instance of `OkHttpClient` provided by the `OkHttpClientProvider`. 

**The Problem**: Because this is a singleton factory, any native SDK (for example, analytics, performance monitoring libraries, or secondary networking libraries) can call `OkHttpClientProvider.setOkHttpClientFactory()` to inject its own custom HTTP client. If this happens *after* Approov has been initialized, the new factory will overwrite the Approov-protected client, causing all subsequent React Native `fetch()` calls to be sent without Approov tokens or certificate pinning.

**The Solution (`getPinningDiagnostics` & `updateClientFactory`)**:
To combat this dynamic environment, we provide diagnostic and healing methods. You should routinely check the health of your networking stack before making the initial API call protected by Approov.

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
On iOS, React Native uses `NSURLSession` for networking. To intercept these requests, Approov uses "Method Swizzling" on the `RCTHTTPRequestHandler` to wrap the session delegate with an `ApproovPinningDelegate`.

**The Problem**: Other native iOS SDKs often use swizzling to intercept network traffic as well. If another SDK swizzles the same methods or dynamically changes the `NSURLSessionDelegate` after the session is created, the Approov delegate might be entirely bypassed. Furthermore, if a third-party React Native library implements its own custom `NSURLSessionDelegate` class (instead of using the standard React Native ones), Approov will ignore it by default to prevent crashes.

**The Solution (`getPinningDiagnostics` & Log Analysis)**:
Our diagnostics method provides realtime statistics on tracked sessions. Furthermore, the Xcode console logs are your best tool during development.

```javascript
  const status = await ApproovService.getPinningDiagnostics();
  console.log(`Pinned iOS Sessions: ${status.sessionsWithPinning}`);
  console.log(`Unpinned iOS Sessions: ${status.sessionsWithoutPinning}`);
```

**Analyzing iOS Logs**:
During development, ensure `ApproovService.setLogLevel(ApproovService.Log.DEBUG)` is enabled.

*   **SUCCESS (What you want to see)**: When an API request is made, look for logs from `ApproovPinningDelegate`. You should see successful pinning checks.
*   **WARNING (What to look out for)**: If a custom network module is bypassing Approov, you will see a warning like: `SKIPPING session creation with <CustomDelegateClassName> delegate (not in interception policy)`. If you see this, and you *want* that traffic protected, you must explicitly add it using `ApproovService.addAllowedDelegate("<CustomDelegateClassName>")` before initialization.

## 2. Using Diagnostics in Development
It is highly recommended to integrate `ApproovService.getPinningDiagnostics()` during your development and QA phases. Add an alert or visual indicator in your staging builds if `isInterceptorPresent` is false or if `sessionsWithoutPinning` is unexpectedly high. This allows you to immediately identify conflicting SDKs as soon as they are added to the project.

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

Your app will periodically make calls to teh Approov service before making your API calls. These calls will periodically fail, and Approov may retry these calls a few times before reporting a networking error. When Approov reports an error, it will be reported at the source of teh API call - a fetch() or axios call for example. If Approov believes the failure is temporary, for example, poor networking connectivity, the calls will return an HTTP response status code of 503 suggesting that the service is unavailable. If the networking appears to be permanently unavailable, for example, no networking permissions, then the service will throw an error. This is consistent with fetch() idioms, and should be compatible with your existing network failure handling strategy.