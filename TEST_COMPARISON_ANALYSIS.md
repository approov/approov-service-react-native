# Test Comparison Analysis

This document compares the tests in `approov-service-react-native` against:

- `../approov-service-urlsession`
- `../approov-service-okhttp`
- `../core-service-layers-testing/TESTING_REQUIREMENTS.md`

The goal is not to force identical test suites. The React Native package has extra bridge, JS, and platform-integration concerns that the pure service-layer repos do not have. The useful question is whether the React Native repo exercises the same core behaviors and outcomes, and whether it does so clearly enough.

## Executive Summary

`approov-service-react-native` is not simply behind the native service-layer repos. In several areas it is stronger because it tests:

- the JS wrapper surface
- React provider lifecycle behavior
- React Native bridge input validation
- Android interceptor internals and client-builder behavior
- iOS synthetic retry and `NSURLSession` integration regressions

However, compared with `approov-service-urlsession` and `approov-service-okhttp`, the React Native tests have three weaknesses:

1. The coverage is fragmented across JS, Android JVM, iOS Objective-C, and Swift helper tests, so requirement coverage is harder to see.
2. Some requirement areas covered directly in the dedicated service-layer repos are only partially covered, or not covered at all, in React Native.
3. Android and iOS in React Native do not always exercise the same scenarios, so cross-platform behavior is harder to trust.

## How The Interface Is Exercised

### URLSession and OkHttp

The dedicated service-layer repos mostly test the public service-layer API end to end:

- initialize the service
- configure protected domains and pinning
- perform real request mutation
- inspect backend replayed headers and URLs
- fetch secure strings and custom JWTs directly
- test message signing in the same service-layer test suite

This makes those suites easy to compare directly against `TESTING_REQUIREMENTS.md`.

### React Native

The React Native repo exercises behavior through multiple layers:

- Jest tests for the JS wrapper and provider
- Android JVM tests for interceptor logic, service public API, client builder, and mini-SDK integration
- iOS native tests for interceptor behavior and synthetic `NSURLSession` behavior
- iOS mini-SDK native tests for bridge-facing API behavior
- Swift tests for message-signing and mutator-bridge mechanics

This is valuable, but it means the core service-layer requirements are not visible in one place. In practice, React Native has both more breadth and less clarity.

## Where React Native Is Stronger

Compared with the dedicated URLSession and OkHttp repos, `approov-service-react-native` adds meaningful test coverage in areas they do not need or do not emphasize:

- JS request normalization and response mapping in `__tests__/index.test.js`
- React provider readiness and error propagation in `__tests__/approov-provider.test.js`
- monitor logging behavior in `__tests__/approov-monitor.test.js`
- Android builder/listener behavior in `android/src/test/java/io/approov/reactnative/ApproovClientBuilderTest.java`
- Android duplicate-interceptor recovery and local POST body preservation in `android/src/test/java/io/approov/reactnative/ApproovServiceRegressionTest.java`
- iOS synthetic retry-response behavior and completion-handler preservation in `tests/ios/native/ApproovNativeTests.m`
- Swift message-signing and mutator-bridge correctness in `tests/ios/swift/ApproovMessageSigningTests.swift`

Those are real strengths. They should be treated as React Native-specific coverage, not noise.

## What Is Wrong Today

### 1. Requirement coverage is hard to prove

The URLSession and OkHttp repos each have a single mini-SDK suite intentionally organized by requirement section. React Native spreads equivalent coverage across several files and styles, so a reviewer cannot quickly answer "do we satisfy section 4?" without manual investigation.

This is the biggest structural problem.

### 2. React Native does not yet match the native repos on all core requirement scenarios

The dedicated repos cover some requirement cases explicitly that React Native does not cover, or only covers on one platform.

### 3. Android and iOS are not fully symmetric

Some cases exist only on Android or only on iOS. That creates risk that the shared JS API behaves differently per platform without an obvious signal in CI.

### 4. Message signing is tested mostly as helper behavior, not as full RN service-layer behavior

React Native has good low-level signing tests, especially on iOS and Android, but it does not yet mirror the URLSession/OkHttp pattern of testing more of the signing requirements through the same bridge-facing mini-SDK flows.

## Coverage Comparison Against TESTING_REQUIREMENTS

### 1. Initialization

Well covered in URLSession and OkHttp:

- same-config re-init
- different-config rejection
- empty-config behavior

React Native status:

- covered: empty-config behavior on Android and iOS
- covered: same-config re-init on Android
- missing or not explicit: different-config re-init failure
- missing or not explicit: valid config with empty comment
- missing or not explicit: valid config with `reinit:` comment as a named requirement
- missing or not explicit: general initialization failure fallback
- missing or not explicit: SDK initialization failure fallback

Assessment:

React Native covers the happy path and empty-config fallback, but it is weaker than both dedicated repos on initialization failure-mode documentation and explicit re-init coverage.

### 2. Request Processing and Token Behaviors

URLSession and OkHttp both cover:

- precheck
- protected request mutation
- binding-header hashing
- exclusion
- selected fallback status behavior
- unprotected request behavior
- custom JWT and secure string basics

React Native covers many of these too, spread across Android and iOS:

- protected mutation with token, trace, substitutions, and binding hash
- manual data hash in token
- missing and empty binding-header value behavior
- custom header names and prefixes
- trace-ID disabling
- removal of substitution and exclusion settings
- token status injection
- unprotected request forwarding
- excluded URL mutation bypass

What is missing or weaker in React Native:

- missing or not explicit: fallback when token is empty but request still proceeds with empty token header and empty trace header
- missing or not explicit: fallback when secure-string substitution returns an empty value
- missing or not explicit: explicit distinction between "invalid secure string key" and "non-existent key"
- incomplete parity: detailed fetch-token status mapping is stronger in OkHttp than in React Native

Assessment:

React Native is fairly strong here, but the dedicated repos are clearer and more exhaustive about fetch-status outcomes.

### 3. Service Mutators and Decision Overrides

URLSession and OkHttp explicitly cover custom mutator override behavior.

React Native also covers:

- custom mutator allowing status-header injection
- custom mutator blocking `NO_APPROOV_SERVICE`
- Android interceptor mutator behavior
- Swift mutator-bridge propagation and request copy-back

What is missing or weaker in React Native:

- missing or not explicit: default mutator fail-closed behavior as a named contract
- missing or not explicit: mutator API plus message-signing integration in one end-to-end scenario

Assessment:

React Native has good mutator regression coverage, but less explicit requirement-level coverage than it should.

### 4. Pinning

URLSession and OkHttp both cover:

- valid/invalid pins
- dynamic pin updates
- accept-any behavior

React Native covers:

- valid pins and invalid pins on iOS mini-SDK native tests
- diagnostics on both platforms
- Android builder behavior for pin-change listeners

What is missing or weaker in React Native:

- missing on Android: mini-SDK or bridge-facing valid/invalid pinning scenarios comparable to the dedicated repos
- missing or not explicit: dynamic pinning updates end to end
- missing or not explicit: unprotected domains are unaffected by pinning
- missing or not explicit: pinning-only protection without token, trace, or signing
- missing or not explicit: excluded protected URLs still exercise pinning

Assessment:

Pinning is the clearest area where React Native trails the dedicated service-layer repos in requirement-aligned end-to-end coverage.

### 5. Message Signing

URLSession and OkHttp both cover:

- install signing success
- account signing success
- single-signature behavior
- install key generation failure
- digest generation for POST/PUT/PATCH
- signing failure fallback

React Native covers:

- Android signer unit behavior in `ApproovDefaultMessageSigningTest`
- iOS signer and mutator-bridge behavior in `ApproovMessageSigningTests.swift`

What is missing or weaker in React Native:

- missing or not explicit: account and install signing through RN mini-SDK request flows
- missing or not explicit: signing failure fallback in RN bridge-facing tests
- missing or not explicit: digest-body behavior through actual React Native-facing request paths
- missing or not explicit: "single signature application" as a service-layer end-to-end behavior, not just helper logic

Assessment:

The low-level signing logic is tested well, but the requirement-level service behavior is weaker than in URLSession and OkHttp.

### 6. Secure Strings and Custom JWT

URLSession and OkHttp are stronger here. They explicitly test:

- valid secure string
- non-existent key
- empty key
- large custom JWT payload
- malformed custom JWT payload
- disabled custom JWT

OkHttp additionally tests:

- nil secure string key

React Native currently covers:

- valid secure string
- unknown/non-existent key
- simple custom JWT success

What is missing in React Native:

- invalid secure string key
- empty secure string key
- nil secure string key as a secure-string case
- substitution value range coverage
- custom JWT with 18KB payload
- malformed custom JWT payload
- disabled/rejected/network custom JWT failure cases

Assessment:

This is another clear gap area for React Native versus both dedicated repos.

## Cross-Repo Comparison Summary

### Areas where React Native is behind both URLSession and OkHttp

- requirement-to-test traceability
- initialization failure-path coverage
- end-to-end pinning scenarios
- requirement-style message-signing coverage
- secure-string edge cases
- large and malformed custom JWT coverage

### Areas where React Native is ahead

- JS API behavior
- React provider lifecycle
- bridge input validation
- regression tests for synthetic retry responses
- client-builder and duplicate-interceptor behavior
- mutator-bridge copy-back and stream preservation

### Areas where the dedicated repos are also still incomplete

Based strictly on `TESTING_REQUIREMENTS.md`, even URLSession and OkHttp still appear light or silent on some items:

- general initialization failure fallback
- SDK initialization failure fallback
- explicit default mutator fail-closed contract
- mutator API plus message-signing integration in one scenario
- pinning-only protection
- excluded protected URLs still pin
- secure-string substitution value-range coverage

So the React Native repo is not uniquely incomplete. The difference is that the dedicated repos present their coverage more clearly.

## Recommended Additions For React Native

Highest priority:

- Add a single `RN_REQUIREMENTS_COVERAGE.md` or similar table mapping each requirement to the existing Android/iOS/JS test file and test name.
- Add Android and iOS tests for different-config re-initialization failure.
- Add Android and iOS tests for initialization failure fallback and SDK-init failure fallback.
- Add bridge-facing pinning tests on Android comparable to the dedicated OkHttp suite.
- Add dynamic pinning update tests for React Native-facing clients/sessions.
- Add secure-string tests for empty key, nil key, and invalid key on both platforms.
- Add custom JWT tests for 18KB payload, malformed payload, and disabled/rejected cases on both platforms.

Medium priority:

- Add end-to-end RN tests for install signing and account signing through actual protected requests.
- Add end-to-end RN tests for signing fallback when signing fails.
- Add a test that proves excluded protected URLs still pin, if that is the intended behavior.
- Add tests for empty token, empty trace ID, and empty secure-string substitution fallback behavior.
- Add explicit tests for default mutator fail-closed behavior.

Lower priority but useful:

- Add substitution value-range tests for empty strings, one-character values, and special characters.
- Normalize naming across Android and iOS so matching scenarios are easier to diff.
- Mirror more of the URLSession/OkHttp section comments in the React Native native suites.

## Recommended Structural Changes

The fastest improvement is not more tests first. It is better visibility.

Recommended structure:

1. Keep the JS, Android, iOS, and Swift tests where they are.
2. Add one requirement coverage matrix document.
3. Mark each requirement as:
   - covered on Android and iOS
   - covered on one platform only
   - covered indirectly
   - missing
4. For missing items, prefer bridge-facing tests first, then helper-level tests only if the bridge path is impractical.

That would make the React Native suite much easier to review and maintain.

## Bottom Line

`approov-service-react-native` does not have a weak test suite overall. It has a broad suite with several React Native-specific strengths that the dedicated service-layer repos do not need.

What is wrong is mostly:

- core requirement coverage is hard to see
- some requirement-level gaps remain, especially pinning, signing, and secure string/custom JWT edge cases
- Android and iOS do not yet mirror each other closely enough

If we add a coverage matrix and then fill the missing end-to-end cases, the React Native repo can become the strongest and clearest test suite of the three.
