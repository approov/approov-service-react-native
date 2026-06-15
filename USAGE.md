# Usage

This document describes the features and functionality of the Approov Service for React Native. It provides details on how to interact with the service layer and customize its behavior to suit your application's needs.

## Empty Config Initialization
You can initialize the `ApproovService` with an empty configuration string if you want the React Native environment to behave as a standard set of networking APIs (like `fetch` or `XMLHttpRequest`) without active Approov protection. This is useful for remote activation of Approov or when you need a bypass state (e.g., for specific deployment environments).

> ```javascript
> // Initialize with an empty string to operate as a standard network client
> ApproovService.initialize("");
> ```

When initialized this way, all network requests made through the native React Native networking module will proceed without Approov token injection, message signing, secure string substitution, or dynamic pinning. You can enable full Approov protection later in the application lifecycle by calling `ApproovService.initialize(config)` with a valid configuration string.

## ⚠️ Critical: Initialization Timing & Network Requests

The Approov SDK must fully complete its native initialization sequence and receive your configuration string before it can protect your network traffic.

If your application executes a `fetch()` or `axios` request *before* `ApproovService.initialize()` has successfully completed, that specific request may proceed without an Approov token and without a reliable pinning guarantee. On iOS, the passive `+load` probe may still log evidence that startup networking primitives already existed, but the request itself is not recoverable after it has left the device.

> You must await `useApproov()` / `approovReady` (or `await ApproovService.initialize(...)`) **before** making protected `fetch()` calls.
> A request that leaves the device before initialization completes may be forwarded without an Approov token.
> If you intentionally initialize with `""`, that request path is treated as an explicit no-Approov bootstrap mode: requests will proceed without Approov protection until you later call `ApproovService.initialize("<valid-config>")`.
> This empty-first then valid-config-later flow is supported for advanced service-layer integrations, but switching directly between different non-empty config strings is still rejected.
> The extended session metadata ledger exposed by `getSessionDiagnostics()` is intended only for development and troubleshooting startup/interception issues.
> On iOS, **turn it off for production** with `ApproovService.setSessionMetadataCollectionEnabled(false)` as part of startup.
> Android currently does not persist an equivalent session ledger; this toggle is retained there only for API parity.
> The iOS ledger now has an internal safety cap of about 1 MB, but that cap is only a backstop. It is **not** a reason to leave session metadata collection enabled in release builds.

To guarantee all requests are protected, you **must** strictly gate your network activity behind the initialization state. We provide two ways to do this:

### Option 1: Using the `useApproov()` Hook (Recommended)
If you are using the `<ApproovProvider>` wrapper at the root of your app, the provider automatically handles initialization. You can use the `useApproov()` hook in your child components to wait until Approov is fully ready before fetching data or rendering.

```javascript
import React, { useEffect, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { useApproov } from '@approov/approov-service-react-native';

const MainScreen = () => {
  // Extract the async initialization state
  const { approovReady, approovError } = useApproov();
  const [data, setData] = useState(null);

  useEffect(() => {
    // Only fetch your critical API data once Approov is definitively initialized
    if (approovReady && !approovError) {
      fetch("https://api.example.com/data")
        .then(res => res.json())
        .then(setData);
    }
  }, [approovReady, approovError]);

  if (approovError) return <Text>Approov failed to start</Text>;
  if (!approovReady) return <ActivityIndicator size="large" />; 

  return <View> /* Render your data */ </View>;
}
```

### Option 2: Awaiting the Promise (For Headless/Service logic)
If you are manually initializing Approov outside of the React component tree (e.g., in a background service or a dedicated API wrapper module), `ApproovService.initialize()` returns a Promise. You should `await` it before making any subsequent network calls.

```javascript
import { Platform } from 'react-native';
import { ApproovService } from '@approov/approov-service-react-native';

async function bootstrapAppAndFetch() {
  try {
    // 1. Wait for Approov to finish initializing its Native modules
    await ApproovService.initialize("<your-config-string>");
    
    // 2. Capture startup diagnostics metadata.
    // On Android this is the pre-flight health check. On iOS this is a baseline.
    const diagnostics = await ApproovService.getPinningDiagnostics();
    console.log("Approov startup diagnostics:", diagnostics);

    // 3. CRITICAL ON ANDROID: heal the shared OkHttpClient before the first request.
    if (Platform.OS === "android" &&
        diagnostics &&
        (!diagnostics.isInterceptorPresent || !diagnostics.isPinnerPresent)) {
      console.warn("Approov hooks are missing from the active OkHttpClient. Healing...");
      await ApproovService.updateClientFactory(true);
    }
    
    // 4. Make the first protected request
    const response = await fetch("https://api.example.com/secure-data");

    // 5. On iOS, inspect session metadata immediately after the first protected request.
    const afterFirstRequest = await ApproovService.getPinningDiagnostics();
    console.log("Approov post-request diagnostics:", afterFirstRequest);
  } catch (error) {
    console.error("Failed to initialize Approov", error);
  }
}
```

### Handling Initialization Failures

`ApproovService.initialize()` parses the configuration **locally** and does **not** require network connectivity to succeed — a valid config initializes even when the device is offline (the SDK performs its first attestation fetch asynchronously in the background). It rejects its promise only in the cases below, so always `try/catch` it and do **not** make protected requests on failure:

| Cause | What it means | What to do |
| :--- | :--- | :--- |
| **Malformed / invalid config string** | The string is not a valid Approov configuration (a typo, truncation, or wrong value). This is deterministic — it will fail every time, online or offline. | Use the exact config string from your Approov onboarding email or the `approov` CLI. |
| **Already initialized with a *different* config** | Another Approov service layer (or an earlier call) already initialized the native SDK with a different configuration. The new config may itself be perfectly valid — the *conflict* is the problem. | Use one consistent config throughout the app. Re-initializing with the **same** config is a safe no-op. To deliberately switch accounts, pass a `comment` starting with `"reinit"`. |
| **(App-level) config fetched online while the device is offline** | This is **not** an Approov failure. If you provision the config string remotely (e.g. Firebase Remote Config), your *own* fetch can fail on a flaky/absent connection, leaving you with no config to pass. | Prefer **bundling the static config string in the app** — it rarely changes and remote provisioning only adds a startup failure mode. If you must provision remotely, cache the last-known-good value and fall back to it, and gate protected traffic until a valid config has initialized. **Never pass `""` just because the fetch failed** — `""` is the explicit [bypass mode](#empty-config-initialization) that *disables* Approov protection. |

> **Tip:** the Approov configuration string is static per account and changes very rarely, so in the vast majority of apps it should simply be embedded in the app rather than fetched at runtime.

### Recommended Early Diagnostics Metadata Capture
During development, staging, and the first production rollout of a new app build, you should capture `ApproovService.getPinningDiagnostics()` metadata twice:

1. **Immediately after initialization and before the first protected request**
2. **Immediately after the first protected request**

This gives you visibility into two different failure classes:

1. **Android factory override problems before the first request**
2. **iOS session registration, delegate, and pinning-verification problems after the first request**

If you are also using `ApproovService.getSessionDiagnostics()`, treat it as a temporary troubleshooting aid. It is most useful while validating a new integration, tracking down third-party `NSURLSessionDelegate` conflicts, or confirming that a startup request escaped before initialization completed. After that work is done, disable the extended ledger in production:

```javascript
ApproovService.setSessionMetadataCollectionEnabled(false);
```

> Do not leave `getSessionDiagnostics()` metadata collection enabled in production on iOS.
> The ledger is capped internally to about 1 MB to prevent unbounded growth, but it still stores extra diagnostic state that should only exist during development, staging, or short-lived rollout debugging.
> On Android this toggle is currently a no-op for ledger storage.

```javascript
import { Platform } from 'react-native';
import { ApproovService } from '@approov/approov-service-react-native';

async function captureApproovDiagnostics(stage) {
  const diagnostics = await ApproovService.getPinningDiagnostics();
  console.log(`[Approov ${stage}]`, JSON.stringify(diagnostics, null, 2));

  if (Platform.OS === 'android' &&
      (!diagnostics.isInterceptorPresent || !diagnostics.isPinnerPresent)) {
    await ApproovService.updateClientFactory(true);
  }

  return diagnostics;
}

await ApproovService.initialize("<your-config-string>");
await captureApproovDiagnostics("pre-first-fetch");

await fetch("https://api.example.com/secure-data");

await captureApproovDiagnostics("post-first-fetch");
```

Interpret the metadata as follows:

* **Android:** `isInterceptorPresent` and `isPinnerPresent` must both be `true` before the first protected request. If not, another SDK likely replaced the shared `OkHttpClientFactory`.
* **iOS:** a pre-request baseline with zero sessions is normal. The important signal is the metadata *after* the first protected request. If `sessionsWithoutPinning` is greater than `0`, then Approov observed requests on intercepted sessions but pinning was not verified for those sessions.
* **iOS limitation:** `getPinningDiagnostics()` only knows about sessions that actually reached the Approov interceptor. A completely bypassed first request may still require inspection of native `ApproovService` logs for `IMP CONFLICT`, `SKIPPING session creation`, or `skipping ... unregistered session`.

You should send this metadata to your observability platform during early adoption. It is one of the fastest ways to spot request mutation or pinning regressions introduced by new monitoring SDKs, networking wrappers, or startup ordering changes.

### Pre-Flight Native Network Health Check (Android)
React Native heavily optimizes Android networking by using a single, globally shared `OkHttpClient`. If another observability or analytics SDK (e.g., Datadog, New Relic) initializes *after* Approov does, that SDK might programmatically overwrite the networking factory, completely stripping away the Approov protection without throwing an error.

For robust Android deployments, it is **highly recommended** to perform the `ApproovService.getPinningDiagnostics()` health-check exactly once, just before you execute the very first API request of the application session, as demonstrated in Option 2 above. If the interceptor is missing, calling `ApproovService.updateClientFactory(true)` will instantly heal the active client, preserving the foreign SDK's hooks while adding the Approov protection back on top.

## Message Signing

**Message signing is ON by default.** Unless you disable it, the service layer automatically adds a message signature to every protected outbound request that carries an Approov token — you do not need to opt in or call anything to enable it. (Actual signatures are produced when your Approov account has message signing enabled; see below to disable it or to customise what is signed.)

Message signing makes HTTP requests verifiable to ensure message integrity and authenticity. There are two types of message signing available:

1.  [Installation Message Signing](https://ext.approov.io/docs/latest/approov-usage-documentation/#installation-message-signing): Uses an installation-specific key (held in the device's Secure Enclave/TEE) to sign requests. This provides strong non-repudiation as the signing key never leaves the device and is unique to that specific installation.
2.  [Account Message Signing](https://ext.approov.io/docs/latest/approov-usage-documentation/#account-message-signing): Uses a shared account-specific secret key (HMAC-SHA256) to sign requests. This key is delivered to the SDK only upon successful attestation.

**Advantages of Message Signing:**
*   **Integrity:** Ensures that the request parameters (headers, body, URL) have not been tampered with during transit.
*   **Authenticity:** Proves that the request originated from a genuine, attested application instance.

Message signing is **enabled by default** and is **decoupled from the service mutator** — it is applied as a separate step after the mutator, so it composes with any of the off-the-shelf or custom mutators below. If your Approov account has message signing enabled, the SDK will automatically add the message signature headers to your outbound requests.

You can control message signing directly from JavaScript:

```javascript
import { ApproovService } from '@approov/approov-service-react-native'

// Disable message signing (it is on by default)
await ApproovService.setMessageSigningEnabled(false)

// Query the current state
const signing = await ApproovService.isMessageSigningEnabled()

// Add a header to be covered by the signature *only when present* on a request (re-enables the
// default signer if signing was disabled). Call at startup.
ApproovService.addSignedHeader('X-My-Header')
```

For advanced, fully custom signing configuration (custom algorithms, per-host factories) see the [Approov Service Mutator](#approov-service-mutator) section below.

## Token Binding

[Token Binding](https://ext.approov.io/docs/latest/approov-usage-documentation/#token-binding) allows you to bind the Approov token to a specific piece of data, such as an OAuth token or a user session identifier. This adds an extra layer of security by ensuring that the Approov token can only be used in conjunction with the bound data. The `ApproovService` calculates a hash of the binding data locally and includes this hash in the Approov token claims. It is important to note that the actual binding data is never sent to the Approov cloud service; only the hash is transmitted.

To set up token binding, specify a header name. The value of this header in your requests will be used for the binding.

### Example: Bind to Authorization Header

```javascript
// Bind the Approov token to the Authorization header (e.g., for OAuth)
ApproovService.setBindingHeader("Authorization");
```

If the value of the binding header changes (e.g., the user logs in and gets a new OAuth token), the SDK automatically invalidates the current Approov token and fetches a new one with the updated binding on the next request.


## Use Approov Status as Token

When an actual token cannot be obtained, the Approov fetch status (e.g., `NO_NETWORK`, `MITM_DETECTED`, `NO_APPROOV_SERVICE`) can be sent to your backend in place of the token. This allows your backend to distinguish between different failure reasons even when the `Approov-Token` would otherwise be empty or missing.

**This feature is ON by default** in `@approov/approov-service-react-native`. (Note: this intentionally differs from the sibling Approov service layers — okhttp, swift6-urlsession and ios-swift-asynchttpclient — which default it off.)

When enabled, the `Approov-Token` header is populated with the status string (with the configured prefix) only when the mutator allows the request to proceed without a token (for example, default `NO_APPROOV_SERVICE` handling, or custom mutator overrides). If the mutator blocks the request, no outbound request is made.

To disable it (so the header is left empty/omitted instead of carrying the status string):

```javascript
ApproovService.setUseApproovStatusIfNoToken(false);
```

## Using `fetchWithApproov` (Alternative to Swizzling)

By default, the `@approov/approov-service-react-native` package uses **swizzling (iOS)** and **OkHttpClient factory overrides (Android)** to automatically intercept all React Native `fetch()` calls and inject Approov tokens.

However, in some complex applications, other observability SDKs (like New Relic, Datadog, or Firebase) might aggressively hook into the same networking layer in a way that conflicts with or bypasses Approov's security checks.

If you encounter such conflicts, you can use the `ApproovService.fetchWithApproov` API. It is a secure `fetch`-compatible API for sensitive calls, executed natively on isolated, protected HTTP clients that cannot be interfered with by other React Native modules.

```javascript
import { ApproovService } from '@approov/approov-service-react-native';

// Use it exactly like you would use standard `fetch`
const response = await ApproovService.fetchWithApproov('https://api.yourdomain.com/data', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json'
    },
    body: JSON.stringify({ key: 'value' })
});

const data = await response.json();
```

### Limitations of `fetchWithApproov`
The `fetchWithApproov` wrapper is designed for standard JSON and text API payloads. Because it bypasses React Native's full `NetworkingModule` stack, it does not support:
- Multipart form uploads (`FormData`, including file/URI-backed form parts)
- Binary request bodies (`Blob` or `ArrayBuffer`)
- Request cancellation via `AbortController`
- React Native networking event model features (such as upload/download progress hooks)
- Streaming enormous files (the entire response is buffered into memory)

In practice, pass string bodies (`JSON.stringify(...)` or plain text) and use this API for security-critical REST calls rather than complex form/file transfer flows.

For critical security and authentication calls, `fetchWithApproov` provides guaranteed protection.

## Default Behavior

By default, the `ApproovService` processes requests based on the attestation status returned by the platform SDK. The SDK provides a cryptographically signed JWT token as proof of attestation. Requesting this token typically returns immediately from a local cache; however, a network connection to the Approov cloud is required on app launch or when the token is nearing expiration. The SDK can only determine whether a token was obtained — it cannot verify the token's validity, as that check is performed by your backend.

The behavior described here applies to the Approov token fetch that occurs inside the network interceptor for every protected request. For the complete set of fetch statuses and their meanings, see the official documentation: [Approov Token Fetch Results](https://approov.io/docs/latest/approov-usage-documentation/#approov-token-fetch-results). The equivalent behavior table for the `approov-service-okhttp` layer is documented in [Default Behavior — approov-service-okhttp](https://github.com/approov/approov-service-okhttp/blob/main/USAGE.md#default-behavior).

The table below covers only the statuses relevant to the network interceptor path:

| Approov Fetch Status | Action | Result |
| :--- | :--- | :--- |
| **Success** | Proceed | The request is sent with the `Approov-Token` header populated with the signed JWT. |
| **No Network / Poor Network / MITM Detected** | Fail (temporary) | `fetch()` rejects with `TypeError: Network request failed`. The request should be retried. |
| **No Approov Service** | Proceed | By default the request carries the fetch status string in the `Approov-Token` header (`setUseApproovStatusIfNoToken` is **on by default**); if disabled with `setUseApproovStatusIfNoToken(false)` the header is left **empty**. |
| **Unknown URL / Unprotected URL** | Proceed (unmodified) | The request is forwarded as-is with **no** `Approov-Token` header added. The URL is not registered under Approov protection. |

> **Note:** `REJECTION` statuses are only relevant to explicit `precheck()` calls, not to the interceptor path.

---

# Approov Service Mutator

The `ApproovServiceMutator` decides the token/substitution policy of the Approov network layer at key points in the request lifecycle. Message signing is a **separate, on-by-default** concern (see [Message Signing](#message-signing)) applied after the mutator.

## Off-the-Shelf Mutators (selectable from JavaScript)

Three ready-made policies are implemented in the platform code and can be selected from JavaScript by type identifier — no native code required:

| Type (`ApproovService.Mutator`) | Policy | Behavior |
| --- | --- | --- |
| `DEFAULT` | Standard fail-closed (installed by default) | Token on success; proceed token-less for unprotected URLs (and `NO_APPROOV_SERVICE` when `setUseApproovStatusIfNoToken(true)`); block on network failures and rejection. |
| `ALWAYS_PROCEED` | Fail-open | Always send the request; attach a token only on success; never throw. Use only when your backend enforces token presence. |
| `REQUIRE_ATTESTATION` | Strict fail-closed | Like `DEFAULT`, but also blocks when the Approov service is unreachable (`NO_APPROOV_SERVICE`). |

```javascript
import { ApproovService } from '@approov/approov-service-react-native'

// fail-open: never block app traffic on attestation problems
ApproovService.setServiceMutator(ApproovService.Mutator.ALWAYS_PROCEED)

// strict: never let a protected request through unattested
ApproovService.setServiceMutator(ApproovService.Mutator.REQUIRE_ATTESTATION)

// query the active policy ("DEFAULT" | "ALWAYS_PROCEED" | "REQUIRE_ATTESTATION" | "CUSTOM")
const active = await ApproovService.getServiceMutatorType()
```

## Custom Mutators (native only)

**Important Architecture Note:** Because the React Native bridge does not support passing complex executable code or classes, *custom* mutators (beyond the off-the-shelf types above) must be implemented and registered directly in your platform-native code (Java/Kotlin for Android, Swift/Objective-C for iOS). A custom mutator installed natively reports as `"CUSTOM"` from `getServiceMutatorType()`.

If you need to change other networking behaviors, you can do so natively by setting a custom `ApproovServiceMutator`.

## Customizing Request Handling with Native Mutators

You may want to modify the network behavior to suit specific app requirements. A common use case is handling `NO_APPROOV_SERVICE` statuses to enforce that an Approov Token must always be present, or proceeding on failures while preserving HTTP Message Signing.

### Proceed On Selected Failure Statuses

The example below shows a custom mutator that:

- uses the normal Approov token flow on `SUCCESS`
- allows requests to continue on `MITM_DETECTED` and `NO_APPROOV_SERVICE`

A custom mutator only implements the **decision policy**. Message signing is a separate concern that is applied automatically *after* the mutator (it is on by default), so the mutator must **not** invoke the signer itself — doing so would sign the request twice.

When the request is allowed to continue without a real token, the service layer places the failure status string into the configured token header (for example `Approov-Token: MITM_DETECTED`) because `setUseApproovStatusIfNoToken` is **on by default**. Call `ApproovService.setUseApproovStatusIfNoToken(false)` if you do not want this.


### Android Implementation (Java)

```java
package com.yourcompany.yourapp;

import com.criticalblue.approovsdk.Approov;

import io.approov.reactnative.ApproovException;
import io.approov.reactnative.ApproovService;
import io.approov.reactnative.ApproovServiceMutator;

// A decision-only mutator. Message signing is applied separately and automatically,
// so this class does not extend or call the signer.
public class ProceedOnSelectedStatusesMutator implements ApproovServiceMutator {

    @Override
    public boolean handleInterceptorFetchTokenResult(ApproovService service,
            Approov.TokenFetchResult approovResults,
            String url) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();

        if (status == Approov.TokenFetchStatus.SUCCESS ||
            status == Approov.TokenFetchStatus.MITM_DETECTED ||
            status == Approov.TokenFetchStatus.NO_APPROOV_SERVICE) {
            return true;
        }

        return ApproovServiceMutator.super
            .handleInterceptorFetchTokenResult(service, approovResults, url);
    }
}
```

Register it before React Native networking starts:

```java
import android.app.Application;

import io.approov.reactnative.ApproovService;

public class MainApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        ApproovService.setServiceMutator(new ProceedOnSelectedStatusesMutator());
    }
}
```

### iOS Implementation (Swift)

```swift
import Foundation
import approov_service_react_native

// A decision-only mutator. Message signing is applied separately and automatically,
// so this class does not hold or call a signer.
final class ProceedOnSelectedStatusesMutator: ApproovServiceMutator {
    func handleInterceptorFetchTokenResult(_ approovResults: ApproovTokenFetchResult,
                                           url: String) throws -> Bool {
        switch approovResults.status {
        case .success, .mitmDetected, .noApproovService:
            return true
        default:
            return try ApproovServiceMutatorDefault.shared
                .handleInterceptorFetchTokenResult(approovResults, url: url)
        }
    }
}
```

Register it during app startup:

```swift
import approov_service_react_native

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        ApproovServiceMutatorBridge.shared.serviceMutator = ProceedOnSelectedStatusesMutator()
        return true
    }
}
```

No extra JavaScript is required to send the status: `setUseApproovStatusIfNoToken` is **on by default**, so when the mutator allows the request to proceed without a real token the failure status is placed in the token header automatically. Call `ApproovService.setUseApproovStatusIfNoToken(false)` if you do not want that.

### Customizing Mutators with Message Signing

Message signing is **decoupled** from the mutator and applied as a separate, on-by-default step *after* the mutator, so a custom mutator no longer needs to invoke the signer itself — signing runs regardless of which mutator is installed and covers any headers your mutator adds.

> ⚠️ **Do not call the signer from a mutator.** The examples in this subsection wrap an
> `ApproovDefaultMessageSigning` and call `handleInterceptorProcessedRequest` from inside the mutator;
> that is the **pre-3.6.0 pattern and is no longer correct** — the default signer also runs afterwards
> and will overwrite the mutator's signature, so the signing you configured inside the mutator is wasted
> (and your custom signing config is silently ignored). Instead:
> - implement **decision policy only** in the mutator (e.g. `handleInterceptorFetchTokenResult`), and
> - to **customise** signing, install a configured signer with
>   `ApproovService.setMessageSigner(...)`; to **disable** it use `setMessageSigningEnabled(false)`; to
>   add a signed header use `addSignedHeader(...)`.
>
> The examples below are retained for reference but should be adapted to remove the signer wrapping.

### Android Implementation (Java)

On Android, create a class implementing `io.approov.reactnative.ApproovServiceMutator`.

```java
package com.yourcompany.yourapp;

import io.approov.reactnative.ApproovServiceMutator;
import io.approov.reactnative.ApproovServiceMutatorDefault;
import com.approov.service.ApproovService;
import com.approov.service.TokenFetchResult;
import okhttp3.Request;
import java.io.IOException;

public class EnforceTokenMutator implements ApproovServiceMutator {
    
    private ApproovServiceMutator signer;

    public EnforceTokenMutator(ApproovServiceMutator signer) {
        this.signer = signer;
    }

    @Override
    public boolean handleInterceptorFetchTokenResult(TokenFetchResult approovResults, String url) throws IOException {
        if (approovResults.getStatus() == TokenFetchResult.Status.NO_APPROOV_SERVICE) {
            throw new IOException("Approov service not available. Token required.");
        }
        return ApproovServiceMutatorDefault.getInstance().handleInterceptorFetchTokenResult(approovResults, url);
    }
    
    @Override
    public boolean handleInterceptorShouldProcessRequest(Request request) throws IOException {
        return ApproovServiceMutatorDefault.getInstance().handleInterceptorShouldProcessRequest(request);
    }

    @Override
    public Request handleInterceptorProcessedRequest(Request request, ApproovServiceMutator.ApproovRequestMutations changes) throws IOException {
        // We MUST call the signer here so that message signing still works!
        if (signer != null) {
            return signer.handleInterceptorProcessedRequest(request, changes);
        }
        return request;
    }
}
```

Register this mutator combined with the message signer configuration before initializing your React Native application (e.g., in `MainApplication.java` inside `onCreate`):

```java
import io.approov.reactnative.ApproovService;
import io.approov.reactnative.ApproovDefaultMessageSigning;

public class MainApplication extends Application implements ReactApplication {
  @Override
  public void onCreate() {
    super.onCreate();
    
    // 1. Configure the signer natively (or skip if disabling signing)
    ApproovDefaultMessageSigning.SignatureParametersFactory factory = 
        ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory();
    ApproovDefaultMessageSigning signer = new ApproovDefaultMessageSigning();
    signer.setDefaultFactory(factory);

    // 2. Wrap it with your custom mutator
    EnforceTokenMutator myMutator = new EnforceTokenMutator(signer);

    // 3. Register custom mutator BEFORE React Native networking starts
    ApproovService.setServiceMutator(myMutator);
    
    // ... remaining React Native init
  }
}
```

### iOS Implementation (Swift)

On iOS, you can define your mutator in Swift using the `ApproovServiceMutator` protocol from `approov_service_react_native`.

```swift
import Foundation
import approov_service_react_native

class EnforceTokenMutator: ApproovServiceMutator {
    private let signer: ApproovServiceMutator?

    init(signer: ApproovServiceMutator?) {
        self.signer = signer
    }

    func handleInterceptorFetchTokenResult(_ approovResults: ApproovTokenFetchResult, url: String) throws -> Bool {
        if approovResults.status == .noApproovService {
            throw NSError(domain: "ApproovService", code: 1, userInfo: [NSLocalizedDescriptionKey: "Approov service not available."])
        }
        return true
    }
    
    func handleInterceptorProcessedRequest(_ request: URLRequest, changes: ApproovRequestMutations) throws -> URLRequest {
        if let signer = signer {
            return try signer.handleInterceptorProcessedRequest(request, changes: changes)
        }
        return request
    }
    
    func handleInterceptorShouldProcessRequest(_ request: URLRequest) throws -> Bool { return true }
    func handlePrecheckResult(_ approovResults: ApproovTokenFetchResult) throws {}
    func handleFetchTokenResult(_ approovResults: ApproovTokenFetchResult) throws {}
    func handleFetchSecureStringResult(_ approovResults: ApproovTokenFetchResult, operation: String, key: String) throws {}
    func handleFetchCustomJWTResult(_ approovResults: ApproovTokenFetchResult) throws {}
    func handleInterceptorHeaderSubstitutionResult(_ approovResults: ApproovTokenFetchResult, header: String) throws -> Bool { return true }
    func handleInterceptorQueryParamSubstitutionResult(_ approovResults: ApproovTokenFetchResult, queryKey: String) throws -> Bool { return true }
    func handlePinningShouldProcessRequest(_ request: URLRequest) -> Bool { return true }
}
```

Register your mutator in `AppDelegate.swift` or `AppDelegate.mm`:

```swift
import approov_service_react_native

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        
        // 1. Configure the signer natively
        let factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
        let signer = ApproovDefaultMessageSigning()
        _ = signer.setDefaultFactory(factory)
        
        // 2. Wrap it
        let myMutator = EnforceTokenMutator(signer: signer)
        
        // 3. Register it with the React Native Bridge BEFORE React Native init
        ApproovServiceMutatorBridge.shared.serviceMutator = myMutator
        
        return true
    }
}
```



## Real-world examples

### Policy-driven mutator (host scoping, offline fallback, message signing, pinning)

This example implementation demonstrates how to customize the native `ApproovServiceMutator` to apply different options to API requests based on the hostname. Note that because custom mutators run directly on the underlying native HTTP client hooks, you must implement them in Java/Kotlin (Android) and Swift/Objective-C (iOS).

**Android Implementation (Java)**
```java
package com.yourcompany.yourapp;

import io.approov.reactnative.ApproovServiceMutator;
import io.approov.reactnative.ApproovService;
import io.approov.reactnative.ApproovException;
import com.criticalblue.approovsdk.Approov;
import okhttp3.Request;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.net.URL;

public class PolicyDrivenMutator implements ApproovServiceMutator {
    private ApproovServiceMutator signer;
    private Set<String> protectedHosts;
    private Set<String> allowOfflineForHosts;
    private Set<String> skipPinningHosts;

    public PolicyDrivenMutator(ApproovServiceMutator signer) {
        this.signer = signer;
        this.protectedHosts = new HashSet<>(Arrays.asList("api.example.com"));
        this.allowOfflineForHosts = new HashSet<>(Arrays.asList("status.example.com"));
        this.skipPinningHosts = new HashSet<>(Arrays.asList("metrics.example.com"));
    }

    @Override
    public boolean handleInterceptorShouldProcessRequest(ApproovService service, Request request) throws ApproovException {
        String host = request.url().host();
        if (!protectedHosts.contains(host)) {
            return false;
        }
        return ApproovServiceMutator.DEFAULT.handleInterceptorShouldProcessRequest(service, request);
    }

    @Override
    public boolean handleInterceptorFetchTokenResult(ApproovService service, Approov.TokenFetchResult approovResults, String url) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        if (status == Approov.TokenFetchStatus.NO_NETWORK || status == Approov.TokenFetchStatus.POOR_NETWORK) {
            try {
                String host = new URL(url).getHost();
                if (allowOfflineForHosts.contains(host)) {
                    return false;
                }
            } catch (Exception e) {
                // Ignore URL parsing errors and fall through
            }
        }
        return ApproovServiceMutator.DEFAULT.handleInterceptorFetchTokenResult(service, approovResults, url);
    }

    @Override
    public Request handleInterceptorProcessedRequest(ApproovService service, Request request, ApproovServiceMutator.ApproovRequestMutations changes) throws ApproovException {
        Request req = request;
        if (signer != null) {
            req = signer.handleInterceptorProcessedRequest(service, req, changes);
        }
        return req.newBuilder().header("X-Client-Platform", "android").build();
    }
    
    @Override
    public boolean handlePinningShouldProcessRequest(Request request) {
        String host = request.url().host();
        return !skipPinningHosts.contains(host);
    }
}
```

**iOS Implementation (Swift)**
```swift
import Foundation
import approov_service_react_native

class PolicyDrivenMutator: ApproovServiceMutator {
    private let signer: ApproovServiceMutator?
    private let protectedHosts: Set<String>
    private let allowOfflineForHosts: Set<String>
    private let skipPinningHosts: Set<String>

    init(
        signer: ApproovServiceMutator? = nil,
        protectedHosts: Set<String> = ["api.example.com"],
        allowOfflineForHosts: Set<String> = ["status.example.com"],
        skipPinningHosts: Set<String> = ["metrics.example.com"]
    ) {
        self.signer = signer
        self.protectedHosts = protectedHosts
        self.allowOfflineForHosts = allowOfflineForHosts
        self.skipPinningHosts = skipPinningHosts
    }

    func handleInterceptorShouldProcessRequest(_ request: URLRequest) throws -> Bool {
        guard let host = request.url?.host, protectedHosts.contains(host) else { return false }
        return try ApproovServiceMutatorDefault.shared.handleInterceptorShouldProcessRequest(request)
    }

    func handleInterceptorFetchTokenResult(_ approovResults: ApproovTokenFetchResult,
                                           url: String) throws -> Bool {
        if approovResults.status == .noNetwork || approovResults.status == .poorNetwork,
           let host = URL(string: url)?.host, allowOfflineForHosts.contains(host) {
            return false
        }
        return try ApproovServiceMutatorDefault.shared
            .handleInterceptorFetchTokenResult(approovResults, url: url)
    }

    func handleInterceptorProcessedRequest(_ request: URLRequest,
                                           changes: ApproovRequestMutations) throws -> URLRequest {
        var req = request
        if let signer = signer {
            req = try signer.handleInterceptorProcessedRequest(req, changes: changes)
        }
        req.setValue("ios", forHTTPHeaderField: "X-Client-Platform")
        return req
    }

    func handlePinningShouldProcessRequest(_ request: URLRequest) -> Bool {
        guard let host = request.url?.host else { return true }
        return !skipPinningHosts.contains(host)
    }
    
    // Other required protocol methods with default behavior:
    func handlePrecheckResult(_ approovResults: ApproovTokenFetchResult) throws {}
    func handleFetchTokenResult(_ approovResults: ApproovTokenFetchResult) throws {}
    func handleFetchSecureStringResult(_ approovResults: ApproovTokenFetchResult, operation: String, key: String) throws {}
    func handleFetchCustomJWTResult(_ approovResults: ApproovTokenFetchResult) throws {}
    func handleInterceptorHeaderSubstitutionResult(_ approovResults: ApproovTokenFetchResult, header: String) throws -> Bool { return true }
    func handleInterceptorQueryParamSubstitutionResult(_ approovResults: ApproovTokenFetchResult, queryKey: String) throws -> Bool { return true }
}
```

### Log rejections with ARC + device ID to your telemetry

Monitoring and analyzing rejections is a key part of your security strategy. Ideally, your backend should be customized to include the **ARC (Approov Rejection Code)** and **Device ID** in its error responses (e.g., in a JSON body or a custom header) when it rejects a request due to a missing or invalid Approov token.

#### Why Server-Side Logging is Preferred
While you can obtain these values directly from the SDK using `ApproovService.getLastARC()`, it is generally safer and more reliable to log them from the server response for several reasons:

1.  **Avoid Misleading Network Events**: On poor network connections, a call to `getLastARC()` can inadvertently trigger a background network event that successfully completes a delayed attestation. This might provide an ARC associated with a *successful* attestation that occurred *after* your original request failed, creating confusing telemetry.
2.  **Corporate Firewall & MITM Bypass**: If your custom native mutator allows a request to proceed on `MITM_DETECTED` (a common result of corporate firewalls), the request is sent without a token. In this state, `getLastARC()` will not yet have a rejection code available for that specific attempt.
3.  **Accuracy and Correlation**: Logging the ARC that the server actually observed and used as the basis for rejection ensures perfect correlation in your monitoring dashboards.

If you must log from the client, ensure you have a fallback strategy for when the server doesn't provide the code.

> ```javascript
> import { ApproovService } from '@approov/approov-service-react-native';
> 
> async function makeProtectedRequest(url, options) {
>     try {
>         const response = await fetch(url, options);
>         if (response.ok) {
>             return response;
>         } else {
>             // Preferred: Extract ARC and Device ID from your own server's response
>             const serverArc = response.headers.get("X-Approov-Error-ARC");
>             
>             // ALTERNATIVE: (DISCOURAGED) Obtain from SDK ONLY if server-side retrieval is impossible.
>             // Note: This may trigger background network events and return misleading results.
>             const arc = serverArc || await ApproovService.getLastARC();
>             const deviceID = await ApproovService.getDeviceID();
>             
>             console.log(`Request rejected. ARC: ${arc}, Device ID: ${deviceID}`);
>             return response;
>         }
>     } catch (error) {
>         console.error("Network request failed", error);
>         throw error;
>     }
> }
> ```

## Tips

- Keep mutator logic fast and side-effect safe. These native hooks run on the request path and blocking them will hang the network traffic.
- Use `ApproovServiceMutator.DEFAULT` (Android) or `ApproovServiceMutatorDefault.shared` (iOS) to preserve the existing behavior and layer your changes on top.
- If you override multiple hooks, keep them focused (one concern per hook) for easier testing and maintenance.
