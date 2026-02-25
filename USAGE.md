# Usage

This document describes the features and functionality of the Approov Service for React Native. It provides details on how to interact with the service layer and customize its behavior to suit your application's needs.

## Message Signing

It is possible to sign HTTP requests using Approov to ensure message integrity and authenticity. There are two types of message signing available:

1.  [Installation Message Signing](https://ext.approov.io/docs/latest/approov-usage-documentation/#installation-message-signing): Uses an installation-specific key (held in the device's Secure Enclave/TEE) to sign requests. This provides strong non-repudiation as the signing key never leaves the device and is unique to that specific installation.
2.  [Account Message Signing](https://ext.approov.io/docs/latest/approov-usage-documentation/#account-message-signing): Uses a shared account-specific secret key (HMAC-SHA256) to sign requests. This key is delivered to the SDK only upon successful attestation.

**Advantages of Message Signing:**
*   **Integrity:** Ensures that the request parameters (headers, body, URL) have not been tampered with during transit.
*   **Authenticity:** Proves that the request originated from a genuine, attested application instance.

Message signing is enabled by default. It is configured natively on both iOS and Android during initialization.

For more details on how to configure or disable message signing, see the [Approov Service Mutator](#approov-service-mutator) section below.

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

When enabled, if the Approov token fetch fails or returns an empty token, the `Approov-Token` header will be populated with the status string (with the configured prefix) instead of being left empty.

---

# Approov Service Mutator

The `ApproovServiceMutator` allows you to customize the behavior of the Approov network layer at key points in the request lifecycle. 

**Important Architecture Note:** 
Because the React Native bridge does not support passing complex executable code or classes, custom mutators must be implemented and registered directly in your platform-native code (Java/Kotlin for Android, Swift/Objective-C for iOS).

## Default Behavior: HTTP Message Signing

By default, the Approov Service is configured with an `ApproovDefaultMessageSigning` mutator on both platforms. This means **HTTP Message Signing is ON by default** and will automatically sign requests and add the `Approov-Token` header.

The default message signing configuration ensures that standard API requests are protected. If you need to change the signing algorithms, specify different headers to sign, or **disable message signing entirely**, you must do so natively by setting a new `ApproovServiceMutator`.

## Customizing Request Handling with Native Mutators

You may want to modify the network behavior to suit specific app requirements. A common use case is handling `NO_APPROOV_SERVICE` statuses to enforce that an Approov Token must always be present, or skipping Approov processing for certain health check endpoints.

### Composing with Message Signing

If you register a custom native mutator, you must decide whether you still want HTTP Message Signing. If you do, you must pass an `ApproovDefaultMessageSigning` instance into your custom mutator, and have your mutator call it during the `handleInterceptorProcessedRequest` step.

Below are examples of how to implement and register a custom mutator that enforces tokens while maintaining message signing.

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

## Disabling Message Signing (Opt-Out)

If you strictly do not want to use HTTP Message Signing, you can opt-out by registering an empty or default mutator during native app initialization.

**Android (`MainApplication.java`):**
```java
ApproovService.setServiceMutator(io.approov.reactnative.ApproovServiceMutator.DEFAULT);
```

**iOS (`AppDelegate.swift`):**
```swift
ApproovServiceMutatorBridge.shared.serviceMutator = ApproovServiceMutatorDefault()
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
