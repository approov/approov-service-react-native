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


## Troubleshooting

If you are not seeing Approov logs in your dashboard:

1.  **Check Log Level**:Ensure `ApproovService.setLogLevel` is not set to `NONE`.
2.  **Check Filter**: Verify your dashboard is not filtering out `INFO` level logs.
3.  **Check Native Integration**: Ensure your observability SDK is correctly linked and initialized in the native layer (iOS/Android) if required.
