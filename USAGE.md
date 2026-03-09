# Usage

This document describes the features and functionality of the Approov Service for React Native. It provides details on how to interact with the service layer and customize its behavior to suit your application's needs.

## ⚠️ Critical: Initialization Timing & Network Requests

The Approov SDK must fully complete its native initialization sequence and receive your configuration string before it can protect your network traffic. 

If your application executes a `fetch()` or `axios` request *before* `ApproovService.initialize()` has successfully completed, the native interceptor will bypass that specific request without adding an Approov token or enforcing TLS pinning.

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
import { ApproovService } from '@approov/approov-service-react-native';

async function bootstrapAppAndFetch() {
  try {
    // 1. Wait for Approov to finish initializing its Native modules
    await ApproovService.initialize("<your-config-string>");
    
    // 2. CRITICAL ON ANDROID: Verify the OkHttpClient hasn't been overwritten 
    // by another third-party SDK before making your very first fetch request.
    const status = await ApproovService.getPinningDiagnostics();
    if (status && status.isInterceptorPresent === false) {
      console.warn("Approov Interceptor is missing! Healing Android OkHttpClient...");
      // Re-inject the protection onto the tampered client
      await ApproovService.updateClientFactory(true);
    }
    
    // 3. Now it is 100% safe to fetch; the session is securely protected
    const response = await fetch("https://api.example.com/secure-data");
    // ...
  } catch (error) {
    console.error("Failed to initialize Approov", error);
  }
}
```

### Pre-Flight Native Network Health Check (Android)
React Native heavily optimizes Android networking by using a single, globally shared `OkHttpClient`. If another observability or analytics SDK (e.g., Datadog, New Relic) initializes *after* Approov does, that SDK might programmatically overwrite the networking factory, completely stripping away the Approov protection without throwing an error.

For robust Android deployments, it is **highly recommended** to perform the `ApproovService.getPinningDiagnostics()` health-check exactly once, just before you execute the very first API request of the application session, as demonstrated in Option 2 above. If the interceptor is missing, calling `ApproovService.updateClientFactory(true)` will instantly heal the active client, preserving the foreign SDK's hooks while adding the Approov protection back on top.

## Message Signing

It is possible to sign HTTP requests using Approov to ensure message integrity and authenticity. There are two types of message signing available:

1.  [Installation Message Signing](https://ext.approov.io/docs/latest/approov-usage-documentation/#installation-message-signing): Uses an installation-specific key (held in the device's Secure Enclave/TEE) to sign requests. This provides strong non-repudiation as the signing key never leaves the device and is unique to that specific installation.
2.  [Account Message Signing](https://ext.approov.io/docs/latest/approov-usage-documentation/#account-message-signing): Uses a shared account-specific secret key (HMAC-SHA256) to sign requests. This key is delivered to the SDK only upon successful attestation.

**Advantages of Message Signing:**
*   **Integrity:** Ensures that the request parameters (headers, body, URL) have not been tampered with during transit.
*   **Authenticity:** Proves that the request originated from a genuine, attested application instance.

Message signing is opt-in. It must be configured natively on both iOS and Android before initialization if you wish to use it.

For more details on how to enable or configure message signing, see the [Approov Service Mutator](#approov-service-mutator) section below.

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

In some cases, you might want to send the Approov fetch status (e.g., `NO_NETWORK`, `MITM_DETECTED`) to your backend when an actual token cannot be obtained. This allows your backend to distinguish between different failure reasons even when the `Approov-Token` would otherwise be empty or missing.

To enable this feature:

```javascript
ApproovService.setUseApproovStatusIfNoToken(true);
```

When enabled, the `Approov-Token` header is populated with the status string (with the configured prefix) only when the mutator allows the request to proceed without a token (for example, default `NO_APPROOV_SERVICE` handling, or custom mutator overrides). If the mutator blocks the request, no outbound request is made.

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

---

# Approov Service Mutator

The `ApproovServiceMutator` allows you to customize the behavior of the Approov network layer at key points in the request lifecycle. 

**Important Architecture Note:** 
Because the React Native bridge does not support passing complex executable code or classes, custom mutators must be implemented and registered directly in your platform-native code (Java/Kotlin for Android, Swift/Objective-C for iOS).

## Default Behavior: HTTP Message Signing

By default, the Approov Service is configured with a default mutator (`ApproovServiceMutator.DEFAULT` / `ApproovServiceMutatorDefault`) that **does not perform HTTP Message Signing**. It simply fetches the Approov token and adds the `Approov-Token` header to requests.

If you need HTTP Message Signing, or you need to change other networking behaviors, you must do so natively by setting a custom `ApproovServiceMutator`.

## Customizing Request Handling with Native Mutators

You may want to modify the network behavior to suit specific app requirements. A common use case is handling `NO_APPROOV_SERVICE` statuses to enforce that an Approov Token must always be present, or enabling HTTP Message Signing.

### Enabling Message Signing

To enable HTTP Message Signing, you must register the `ApproovDefaultMessageSigning` mutator. If you also want custom logic (like enforcing tokens), you pass the message signer into your custom mutator so they compose together.

Below are examples of how to implement and register a custom mutator that enforces tokens and simultaneously enables message signing.

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

An important part of your security strategy is to monitor and analyze rejections. Ideally, the server response would be customized to include the ARC and device ID in the response body or headers. However, if this is not possible, you can obtain these values from the `ApproovService` and log them to your telemetry directly from your application code.

This example shows how to log rejections with the ARC and device ID using the global JS `fetch` API. It assumes you are using a custom native `ApproovServiceMutator` that prevents requests from proceeding without an Approov token. If this is not the case, and a request is made in poor network conditions, there is a small chance that `getLastARC()` will be executed just as the network interface becomes available. This would provide an ARC even though the original request timed out without one. The following code is a simple example of how to implement this logging:

```javascript
import { ApproovService } from '@approov/approov-service-react-native';

async function makeProtectedRequest(url, options) {
    try {
        const response = await fetch(url, options);
        if (response.ok) {
            // Process request
            return response;
        } else {
            // Log rejection: ARC + device ID can be added for correlating a particular request to the failure reason
            
            // We are certain we have an ARC code because our custom native ApproovServiceMutator
            // prevents requests without an Approov token to proceed. If this is not the case,
            // we will not have an ARC code and should SKIP obtaining the ARC.
            const arc = await ApproovService.getLastARC();
            const deviceID = await ApproovService.getDeviceID();
            
            console.log(`Request rejected with ARC: ${arc} and device ID: ${deviceID}; response code: ${response.status}`);
            return response;
        }
    } catch (error) {
        console.error("Network request failed", error);
        throw error;
    }
}
```

## Tips

- Keep mutator logic fast and side-effect safe. These native hooks run on the request path and blocking them will hang the network traffic.
- Use `ApproovServiceMutator.DEFAULT` (Android) or `ApproovServiceMutatorDefault.shared` (iOS) to preserve the existing behavior and layer your changes on top.
- If you override multiple hooks, keep them focused (one concern per hook) for easier testing and maintenance.
