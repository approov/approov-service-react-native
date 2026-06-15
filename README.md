# Approov Service for React Native

A wrapper for the [Approov SDK](https://github.com/approov/approov-ios-sdk) to enable easy integration when using [`React Native`](https://reactnative.dev/) for making the API calls that you wish to protect with Approov using `fetch()` or similar. In order to use this you will need a trial or paid [Approov](https://www.approov.io) account.

For more detailed information, please refer to the following documentation:
* **[ARCHITECTURE.md](ARCHITECTURE.md)**: A deep dive into the service layer's network interception design on iOS and Android, race conditions, interference from 3rd party SDKs, and the `fetchWithApproov` alternative.
* **[USAGE.md](USAGE.md)**: Detailed instructions on using the various features of the Approov Service, including message signing, token binding, and custom networks mutators.
* **[REFERENCE.md (Interface)](REFERENCE.md)**: The complete API reference for the React Native `ApproovService` interface, describing all available methods and error types.
* **[TROUBLESHOOTING.md](TROUBLESHOOTING.md)**: A guide providing solutions to common errors and compilation issues you may encounter during setup and integration.

## ADDING THE APPROOV PACKAGE

Add the Approov service layer to your existing App with the following command:

```
npm install @approov/approov-service-react-native
```

Note if you experience an error related to peer dependencies, then you can append the `--force` to install with your particular React Native version. The plugin supports version 0.76 or above.

If you are installing into an Expo project then use:

```
expo install @approov/approov-service-react-native
```

For iOS you must also install [pod](https://cocoapods.org/) dependencies. Change the directory to `ios` and type:

```
pod install
```

Note: do not worry if this generates warnings about duplicate UUIDs.

## ACTIVATING APPROOV

Approov must be initialized before it can protect your API calls, and you **MUST wait for initialization to complete before issuing any protected `fetch()` request**. A request made before initialization finishes is forwarded *without* an Approov token (and without a reliable pinning guarantee); once it has left the device it cannot be recovered. See [USAGE.md](USAGE.md) ("Critical: Initialization Timing & Network Requests") for full details.

Import the service:

```Javascript
import { ApproovService } from '@approov/approov-service-react-native';
```

The `<enter-your-config-string-here>` referenced below is a custom string that configures your Approov account access; it will have been provided in your Approov onboarding email.

### Initialize and gate your requests

Call `initialize()` once at startup, and `await` it before making any protected request:

```Javascript
await ApproovService.initialize("<enter-your-config-string-here>");
// only now issue protected requests
const response = await fetch("https://your.api/endpoint");
```

Structure your app so that any screens or logic that make protected calls do not run until initialization has resolved (for example behind a splash/bootstrap step).

### Optional: the `ApproovProvider` convenience wrapper

If you prefer a React-context style, you can instead wrap your component tree in `ApproovProvider`. It initializes Approov for you when the app starts and exposes a `useApproov()` hook so components can wait for readiness. This is **purely a convenience** — it is not required, and it does not remove the need to gate your network calls behind initialization.

```Javascript
import { ApproovProvider, useApproov } from '@approov/approov-service-react-native';
```

You may define a function that is called just before Approov is initialized; you can include certain `ApproovService` configuration calls in it:

```Javascript
const approovSetup = () => {
};
```

Wrap your application with the `ApproovProvider` component. For instance, if your app's components (typically defined in `App.js`) are currently:

```Javascript
return (
  <View>
    <Button onPress={callAPI} title="Press Me!" />
  </View>
);
```

change them to:

```Javascript
return (
  <ApproovProvider config="<enter-your-config-string-here>" onInit={approovSetup}>
    <View>
      <Button onPress={callAPI} title="Press Me!" />
    </View>
  </ApproovProvider>
);
```

Components can then gate rendering and requests on readiness:

```Javascript
const { approovReady, approovError } = useApproov(); // wait for approovReady before fetching
```

See [USAGE.md](USAGE.md) for both approaches in full (the `useApproov()` hook vs. awaiting the `initialize()` promise).

## CHECKING IT WORKS
Once the initialization is called, it is possible for any network requests to have Approov tokens or secret substitutions made. Initially you won't have set which API domains to protect, so the requests will be unchanged. It will have called Approov though and made contact with the Approov cloud service. You will see `ApproovService` logging indicating `UNKNOWN_URL` (Android) or `unknown URL` (iOS).

If you use `ApproovProvider`, you can also place the `ApproovMonitor` component (also imported from `@approov/approov-service-react-native`) inside it. This will output console logging on the state of the Approov initialization.

During initial rollout and whenever you add observability SDKs, you should also capture `ApproovService.getPinningDiagnostics()` metadata in your app logging:
* **Android:** fetch the metadata immediately before the first protected request and verify `isInterceptorPresent` and `isPinnerPresent`. If either is `false`, call `ApproovService.updateClientFactory(true)` before proceeding.
* **iOS:** fetch the metadata immediately after the first protected request and inspect `sessionsWithoutPinning` and `unpinnedSessions`. This helps detect delegate conflicts, skipped sessions, and missing pinning verification early in development and staging.

See [USAGE.md](USAGE.md) for a recommended startup diagnostics workflow and [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for platform-specific interpretation.

On Android, you can see logging using [`logcat`](https://developer.android.com/studio/command-line/logcat) output from the device. You can see the specific Approov output using `adb logcat | grep ApproovService`. On iOS, look at the console output from the device using the [Console](https://support.apple.com/en-gb/guide/console/welcome/mac) app from MacOS. This provides console output for a connected simulator or physical device. Select the device and search for `ApproovService` to obtain specific logging related to Approov.

Your Approov onboarding email should contain a link allowing you to access [Live Metrics Graphs](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs). After you've run your app with Approov integration you should be able to see the results in the live metrics within a minute or so. At this stage you could even release your app to get details of your app population and the attributes of the devices they are running upon.


## RN-FETCH-BLOB
Support is provided for the [rn-fetch-blob](https://github.com/joltup/rn-fetch-blob) networking stack. However, to use this a special fork of the package must be used. This is available at [@approov/rn-fetch-blob](https://www.npmjs.com/package/@approov/rn-fetch-blob). You will need to uninstall the standard package and install the special one as follows:

```
npm uninstall rn-fetch-blob
npm install @approov/rn-fetch-blob
```


Please see the [Quickstart](https://github.com/approov/quickstart-react-native), which is a sample application you can check out to see how integration with Approov works.
