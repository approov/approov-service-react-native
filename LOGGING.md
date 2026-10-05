# ApproovService Logging Guide

The `ApproovService` for React Native emits detailed security and operational logs to the native system log facilities on both iOS and Android.

- **iOS**: Logs are written to the unified logging system with `os_log`, as public messages prefixed `ApproovService <LEVEL>: `. INFO and WARN lines use the default log type, so they are kept and shown in Xcode and Console.app without extra settings; ERROR lines use the error type; DEBUG and EXTREME lines use the debug type, which Console.app shows once "Include Debug Messages" is enabled (Xcode shows them while debugging).
- **Android**: Logs are written to `Logcat` with the matching priority (visible via `adb logcat`).

By default, the log level is set to `INFO`. This provides visibility into key events such as initialization, token prefetching, and pinning enforcement.

### One level for every native log line

`setLogLevel` controls every log line the service layer writes on both platforms: all native code logs through one level-gated function per platform (`ApproovService.log` on Android, the `ApproovLog*` functions on iOS, which also pass the level to the Swift sources). `setLogLevel(ApproovService.Log.NONE)` silences the layer completely. The same event is logged at the same level on both platforms:

* **DEBUG**: per-request and per-connection detail, such as `task mutation` / `request mutation` summaries, `intercepting` and `observed dataTask` lines (iOS), a WebSocket upgrade forwarded without Approov processing (Android; WebSockets are not supported), `token for <url>` (the decoded token claims, which include the device ID and the client IP address), header and query parameter substitution, excluded URLs, pin checks per connection, dynamic configuration updates, pins applied per host, and `getDeviceID`.
* **INFO**: lifecycle events and results, such as initialization (with the device ID), `setDevKey`, prefetch and precheck outcomes, explicit calls such as `fetchToken`, and the first request forwarded in bypass mode (later ones are DEBUG).
* **WARN**: recoverable problems that change behaviour, such as initialization discarding runtime configuration, another SDK's OkHttp factory or custom client builder that could not be read, the first request sent before `initialize()` completed, the first request started on the main thread and forwarded without Approov protection (iOS), a session created with a nil delegate (iOS), and calls to deprecated no-op methods.
* **ERROR**: failures, such as initialization errors, message signing failures and rejected native calls.

Keep the level at `INFO` or above in production: `DEBUG` writes full request URLs, and the device ID and client IP address, for every protected request.

### Dynamic Log Levels

You can adjust the verbosity of Approov logs at runtime using the `setLogLevel` method:

```javascript
import { ApproovService } from '@approov/approov-service-react-native';

// Available levels: EXTREME (0), DEBUG (1), INFO (2), WARN (3), ERROR (4), NONE (5)
ApproovService.setLogLevel(ApproovService.Log.DEBUG);
```

## Capturing Logs with Observability SDKs

Most modern observability platforms (Sentry, Datadog, New Relic, BugSnag) automatically capture system logs and attach them to crash reports or error events as "breadcrumbs".

Below are configuration tips for popular SDKs to ensure `ApproovService` logs are captured.

### Sentry

Sentry's React Native SDK automatically captures native system logs as breadcrumbs.

**Configuration:**
Ensure `enableAutoBreadcrumbTracking` is enabled (it is `true` by default).

```javascript
Sentry.init({
  dsn: "YOUR_DSN",
  enableAutoBreadcrumbTracking: true, // Default
});
```

- **iOS**: the layer logs with `os_log`, not `NSLog`. Confirm that your SDK version captures unified logging (`os_log`) output; a hook that only captures `NSLog` will not see these lines.
- **Android**: `Logcat` output (Warning/Error levels by default, Info often included) is captured.

### Datadog

Datadog can forward native logs to their platform.

**Android Configuration:**
You may need to enable `logcat` forwarding in your `dd-sdk-android` configuration.

```kotlin
// Android Native Initialization
val config = Configuration.Builder(...)
    .setLogcatLogsEnabled(true) 
    .build()
```

**iOS Configuration:**
Datadog for iOS automatically captures logs sent to `os_log` or `NSLog` if configured.

### New Relic

New Relic's mobile agents automatically instrument system logging.

- **iOS**: the layer logs with `os_log`, not `NSLog`. Confirm that your SDK version captures unified logging (`os_log`) output; a hook that only captures `NSLog` will not see these lines.
- **Android**: `Log` class usage is instrumented.

You can view these logs in the "Logs" section of your mobile application dashboard in New Relic.

### BugSnag

BugSnag captures breadcrumbs from system logs.

- **iOS**: the layer logs with `os_log`, not `NSLog`. Confirm that your SDK version captures unified logging (`os_log`) output; a hook that only captures `NSLog` will not see these lines.
- **Android**: Captures `Log.*` calls as breadcrumbs.

No additional configuration is typically required beyond the standard initialization.

## Recommendation: Remote Logging

Because the Approov SDK operates at the native network interception layer (often using swizzling on iOS), standard JS-level error tracking may not capture the full context of connectivity issues.

We **strongly recommend** ensuring that your native observability SDK is configured to capture and forward these system logs remotely. This provides critical visibility into the initialization and pinning process, allowing for faster diagnosis of issues that may occur in different production environments.

We also recommend capturing the structured metadata returned by `ApproovService.getPinningDiagnostics()` during early rollout:
1. immediately after initialization and before the first protected request
2. immediately after the first protected request

This metadata complements the native logs:
* **Android:** confirms whether the active shared `OkHttpClient` still contains the Approov interceptor and certificate pinner.
* **iOS:** confirms whether registered sessions have verified pinning, and highlights `sessionsWithoutPinning` / `unpinnedSessions` when requests were observed without successful pinning verification.

On iOS, remember that a completely bypassed session may not appear in the metadata at all. In that case, the native logs remain the primary signal.

> [!WARNING]
> The session metadata ledger behind `getSessionDiagnostics()` is intended only for development and short-lived troubleshooting.
> In production, turn it off on iOS with `ApproovService.setSessionMetadataCollectionEnabled(false)`.
> The iOS implementation now enforces an internal safety cap of about 1 MB, but that cap is only a guardrail and should not be treated as a production setting.
> Android does not currently retain an equivalent session ledger.

## High-Value iOS Log Lines

When debugging iOS networking conflicts, these log messages are especially important:

* `+load passive probe ...`: startup-only diagnostics about `NSURLSession` classes and selector implementations before the interceptor starts.
* `Registered session ...`: Approov saw session creation and stored session metadata.
* `task mutation [...]` (DEBUG): request interception and mutation executed for that task.
* `SKIPPING session creation ... (not in interception policy)`: the delegate class was not allowlisted.
* `skipping dataTaskWithRequest for unregistered session`: task creation was visible, but session registration was missed.
* `IMP CONFLICT` / `IMP RECOVERY`: another SDK overwrote an active Approov hook after interceptor startup and the optional runtime integrity checker detected or attempted recovery. These logs only appear when runtime recovery is enabled with `setMaxReswizzleAttempts(...) > 0`.
* `PINNING BLOCKED connection ...`: pinning actively rejected the server trust.
* `forwarding without pin verification`: the pinning delegate was reached, but a usable service was not available for verification.

## Troubleshooting

If you are not seeing Approov logs in your dashboard:

1.  **Check Log Level**: Ensure `ApproovService.setLogLevel` is not set to `NONE`.
2.  **Check Filter**: Verify your dashboard is not filtering out `INFO` level logs.
3.  **Check Native Integration**: Ensure your observability SDK is correctly linked and initialized in the native layer (iOS/Android) if required.
