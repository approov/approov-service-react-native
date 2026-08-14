# Approov Service for React Native

![React Native](https://img.shields.io/badge/React%20Native-0.76%2B-61DAFB?logo=react&logoColor=white)
![Android](https://img.shields.io/badge/Android-minSdk%2025-3DDC84?logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-11.0%2B-000000?logo=apple&logoColor=white)
![npm](https://img.shields.io/npm/v/%40approov%2Fapproov-service-react-native?logo=npm&color=CB3837)
![Approov SDK](https://img.shields.io/badge/Approov%20SDK-3.5.3-0055CC)
![Message Signing](https://img.shields.io/badge/Message%20Signing-RFC%209421-6A1B9A)
![CI](https://github.com/approov/approov-service-react-native/actions/workflows/build_and_test.yml/badge.svg)

A wrapper for the [Approov SDK](https://github.com/approov/approov-ios-sdk) to enable easy integration when using [`React Native`](https://reactnative.dev/) for making the API calls that you wish to protect with Approov using `fetch()` or similar. In order to use this you will need a trial or paid [Approov](https://www.approov.io) account.

## Table of Contents

- [Adding the Approov Dependency](#adding-the-approov-dependency)
- [Manifest / Project Changes](#manifest--project-changes)
- [Initializing Approov](#initializing-approov)
- [Using Approov](#using-approov)
- [Checking It Works](#checking-it-works)
- [Next Steps](#next-steps)

## Adding the Approov Dependency

Add the Approov service layer to your existing app with:

```shell
npm install @approov/approov-service-react-native
```

If you experience an error related to peer dependencies, append `--force` to install with your particular React Native version. The plugin supports version 0.76 or above.

For Expo projects use:

```shell
expo install @approov/approov-service-react-native
```

## Manifest / Project Changes

For iOS you must install [pod](https://cocoapods.org/) dependencies. Change to the `ios` directory and run:

```shell
pod install
```

Do not worry if this generates warnings about duplicate UUIDs.

## Initializing Approov

Import the service layer:

```javascript
import { ApproovProvider, ApproovService } from '@approov/approov-service-react-native';
```

Initialize explicitly during startup and keep a correlation id in your app logs:

```javascript
const approovSessionId = global.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}`;

async function initializeApproov() {
  try {
    await ApproovService.initialize('<enter-your-config-string-here>');

    const enabled = await ApproovService.isApproovEnabled();
    if (enabled) {
      const deviceId = await ApproovService.getDeviceID();
      console.log('Approov initialized', { approovSessionId, deviceId });
    } else {
      console.warn('Approov initialized without active protection', { approovSessionId });
    }
  } catch (error) {
    console.warn('Approov initialization failed; continuing unprotected', {
      approovSessionId,
      error,
    });
    await ApproovService.initialize('');
  }
}
```

If you prefer component-wrapped startup, wrap your application with `ApproovProvider` after applying any setup calls:

```javascript
const approovSetup = () => {
};

return (
  <ApproovProvider config="<enter-your-config-string-here>" onInit={approovSetup}>
    <View>
      <Button onPress={callAPI} title="Press Me!" />
    </View>
  </ApproovProvider>
);
```

The config string is provided in your Approov onboarding email.

## Using Approov

Once initialization succeeds, network requests may have Approov tokens, message signatures, dynamic pinning, or secure substitutions applied. Initially you will not have set which API domains to protect, so requests are unchanged, but the service will contact the Approov cloud and log `UNKNOWN_URL` (Android) or `unknown URL` (iOS).

Support is provided for the [rn-fetch-blob](https://github.com/joltup/rn-fetch-blob) networking stack through the [@approov/rn-fetch-blob](https://www.npmjs.com/package/@approov/rn-fetch-blob) fork:

```shell
npm uninstall rn-fetch-blob
npm install @approov/rn-fetch-blob
```

## Checking It Works

You may use the `ApproovMonitor` component, also imported from `@approov/approov-service-react-native`, inside `ApproovProvider`. This outputs console logging on the state of Approov initialization.

During initial rollout and whenever you add observability SDKs, capture `ApproovService.getPinningDiagnostics()` metadata in your app logging:

- **Android:** fetch the metadata immediately before the first protected request and verify `isInterceptorPresent` and `isPinnerPresent`. If either is `false`, call `ApproovService.updateClientFactory(true)` before proceeding.
- **iOS:** fetch the metadata immediately after the first protected request and inspect `sessionsWithoutPinning` and `unpinnedSessions`. This helps detect delegate conflicts, skipped sessions, and missing pinning verification early in development and staging.

On Android, use [`logcat`](https://developer.android.com/studio/command-line/logcat) and filter with `adb logcat | grep ApproovService`. On iOS, use the [Console](https://support.apple.com/en-gb/guide/console/welcome/mac) app for a connected simulator or device and search for `ApproovService`.

Your Approov onboarding email should contain a link to [Live Metrics Graphs](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs). After you run your app with Approov integration you should see results in live metrics within a minute or so.

## Next Steps

- Read [ARCHITECTURE.md](ARCHITECTURE.md) for the service layer's network interception design on iOS and Android, race conditions, interference from third-party SDKs, and the `fetchWithApproov` alternative.
- Read [USAGE.md](USAGE.md) for detailed instructions on message signing, token binding, custom network mutators, API protection, and secrets protection.
- Read [REFERENCE.md](REFERENCE.md) for the complete React Native `ApproovService` interface.
- Read [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for platform-specific setup and runtime diagnostics.
- See the [Quickstart](https://github.com/approov/quickstart-react-native) sample application for a working integration.
