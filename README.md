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

In order to use Approov you must include it as a component that wraps your application components. This automatically deals with initializing Approov when the app is started. Import using the following:

```Javascript
import { ApproovProvider, ApproovService } from '@approov/approov-service-react-native';
```

This defines an `ApproovProvider` component and the `ApproovService` which allows you to make certain calls to Approov from your application.

You should define an initially empty function that is called just before Approov is initialized. You may wish to include certain `ApproovService` calls in this in the future:

```Javascript
const approovSetup = () => {
};
```

You must now wrap your application with the `ApproovProvider` component. For instance, if your app's components (typically defined in `App.js`) are currently:

```Javascript
return (
  <View>
    <Button onPress={callAPI} title="Press Me!" />
  </View>
);
```

This should be changed to the following:

```Javascript
return (
  <ApproovProvider config="<enter-your-config-string-here>" onInit={approovSetup}>
    <View>
      <Button onPress={callAPI} title="Press Me!" />
    </View>
  </ApproovProvider>
);
```

The `<enter-your-config-string-here>` is a custom string that configures your Approov account access. This will have been provided in your Approov onboarding email.

## CHECKING IT WORKS
Once the initialization is called, it is possible for any network requests to have Approov tokens or secret substitutions made. Initially you won't have set which API domains to protect, so the requests will be unchanged. It will have called Approov though and made contact with the Approov cloud service. You will see `ApproovService` logging indicating `UNKNOWN_URL` (Android) or `unknown URL` (iOS).

You may use the `ApproovMonitor` component (also imported from `@approov/approov-service-react-native`) inside the `ApproovProvider`. This will output console logging on the state of the Approov initialization.

On Android, you can see logging using [`logcat`](https://developer.android.com/studio/command-line/logcat) output from the device. You can see the specific Approov output using `adb logcat | grep ApproovService`. On iOS, look at the console output from the device using the [Console](https://support.apple.com/en-gb/guide/console/welcome/mac) app from MacOS. This provides console output for a connected simulator or physical device. Select the device and search for `ApproovService` to obtain specific logging related to Approov.

Your Approov onboarding email should contain a link allowing you to access [Live Metrics Graphs](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs). After you've run your app with Approov integration you should be able to see the results in the live metrics within a minute or so. At this stage you could even release your app to get details of your app population and the attributes of the devices they are running upon.


## RN-FETCH-BLOB
Support is provided for the [rn-fetch-blob](https://github.com/joltup/rn-fetch-blob) networking stack. However, to use this a special fork of the package must be used. This is available at [@approov/rn-fetch-blob](https://www.npmjs.com/package/@approov/rn-fetch-blob). You will need to uninstall the standard package and install the special one as follows:

```
npm uninstall rn-fetch-blob
npm install @approov/rn-fetch-blob
```


Please see the [Quickstart](https://github.com/approov/quickstart-react-native), which is a sample application you can check out to see how integration with Approov works.