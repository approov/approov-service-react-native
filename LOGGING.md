# ApproovService Logging Guide

The `ApproovService` for React Native emits detailed security and operational logs to the native system log facilities on both iOS and Android.

- **iOS**: Logs are written to `NSLog` (visible in Console.app).
- **Android**: Logs are written to `Logcat` (visible via `adb logcat`).

By default, the log level is set to `INFO`. This provides visibility into key events such as initialization, token prefetching, and pinning enforcement.

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

- **iOS**: `NSLog` output is captured.
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

- **iOS**: `NSLog` calls are instrumented.
- **Android**: `Log` class usage is instrumented.

You can view these logs in the "Logs" section of your mobile application dashboard in New Relic.

### BugSnag

BugSnag captures breadcrumbs from system logs.

- **iOS**: Captures `NSLog` messages as breadcrumbs.
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

## High-Value iOS Log Lines

When debugging iOS networking conflicts, these log messages are especially important:

* `+load passive probe ...`: startup-only diagnostics about `NSURLSession` classes and selector implementations before the interceptor starts.
* `Registered session ...`: Approov saw session creation and stored session metadata.
* `task mutation [...]`: request interception and mutation executed for that task.
* `SKIPPING session creation ... (not in interception policy)`: the delegate class was not allowlisted.
* `skipping dataTaskWithRequest for unregistered session`: task creation was visible, but session registration was missed.
* `IMP CONFLICT` / `IMP RECOVERY`: another SDK overwrote an active Approov hook after interceptor startup and the optional runtime integrity checker detected or attempted recovery. These logs only appear when runtime recovery is enabled with `setMaxReswizzleAttempts(...) > 0`.
* `PINNING BLOCKED connection ...`: pinning actively rejected the server trust.
* `forwarding without pin verification`: the pinning delegate was reached, but a usable service was not available for verification.

## Troubleshooting

If you are not seeing Approov logs in your dashboard:

1.  **Check Log Level**:Ensure `ApproovService.setLogLevel` is not set to `NONE`.
2.  **Check Filter**: Verify your dashboard is not filtering out `INFO` level logs.
3.  **Check Native Integration**: Ensure your observability SDK is correctly linked and initialized in the native layer (iOS/Android) if required.
