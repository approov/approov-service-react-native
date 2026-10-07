# Approov Expo Config Plugin

Add the plugin to your Expo app config, run `expo prebuild`, and the generated Android and iOS projects carry the universal Approov packages, initialized natively at app start with your Approov account ID before React Native starts. Every request your app makes to a domain you added to Approov then carries an Approov token that your backend can verify, over a TLS connection validated against the Managed Trust Roots Approov maintains for your account, or the certificate public keys configured for the domain. On Android this covers OkHttp, `HttpsURLConnection`, Volley and Cronet (react-native-nitro-fetch included) through the `io.approov.gradle` build plugin; on iOS it covers every `URLSession` created after initialization.

The plugin targets the 3.8.0 universal packages: [approov-service-android](https://github.com/approov/approov-service-android) (`io.approov:service.android` with its Gradle plugin) and [approov-service-ios](https://github.com/approov/approov-service-ios). It ships with the release of this package whose native module is built on those packages. What those packages do once installed is described in their READMEs; this page covers only what the plugin adds and how to set the plugin up. The plugin initializes Approov and configures nothing: the headers, secure strings, exclusions, service mutator, message signing and every other setting are made by your app afterwards (see [configuration](#configuration)).

## REQUIREMENTS

- Expo SDK 52 or later with Continuous Native Generation (`expo prebuild`). Native iOS initialization needs the Swift `AppDelegate` of Expo SDK 53 and later. The plugin is tested against the bare templates of Expo SDK 52 to 58.
- Android: Groovy Gradle files (`build.gradle`, as Expo generates them), Android Gradle Plugin 8.6 or later and JDK 17 or later for the build, Android 6.0 (API 23) or later at run time.
- iOS: iOS 15 or later, Xcode 16 or later (the iOS package builds with Swift 6), CocoaPods.
- **Published artifacts.** The defaults fetch `io.approov:service.android` and `io.approov:service.android-gradle-plugin` from Maven Central, and the `approov-ios-sdk` and `approov-service-ios` pods from GitHub at a tag (neither is published to CocoaPods trunk). A build, EAS Build included, works only once the versions it names are published. Until then use the local development options below on your own machine.
- **One Approov layer per platform.** The package's own 3.5.x native module and the universal packages the plugin adds must not both be linked into one app: two Approov layers in one process conflict. See [the 3.5.x native module](#the-35x-native-module) below.

## INSTALLING

```sh
npx expo install @approov/approov-service-react-native
```

The plugin loads `expo/config-plugins`, which every Expo app has through its `expo` dependency, and it does not depend on `@expo/config-plugins` being hoisted. It therefore works with npm, Yarn classic, Yarn berry (including Plug'n'Play) and pnpm (isolated `node_modules`). `expo` is declared as an optional peer dependency (`>=52.0.0`), so a bare React Native app that does not use Expo is not made to install it.

Add the plugin with your Approov account ID to `app.json`:

```json
{
  "expo": {
    "plugins": [
      ["@approov/approov-service-react-native", { "accountId": "<your-approov-account-id>" }]
    ]
  }
}
```

or to `app.config.js`:

```js
export default ({ config }) => ({
  ...config,
  plugins: [
    [
      '@approov/approov-service-react-native',
      {
        // your Approov account ID, from your onboarding email or "approov sdk -getConfigString"
        accountId: '<your-approov-account-id>',
      },
    ],
  ],
});
```

Then generate the native projects:

```sh
npx expo prebuild
```

Your account ID is in your onboarding email, or from the Approov CLI at any time (the CLI calls it the SDK config string):

```sh
approov sdk -getConfigString
```

It looks like `#your-account#p6nZ...=`. It is the same for every app in your account and is not a secret, so it can live in your app config and in source control. If you prefer to keep it out of the config, leave `accountId` out and set the `APPROOV_ACCOUNT_ID` environment variable when you run `expo prebuild` instead. Either way the whole value must arrive intact, punctuation included.

## OPTIONS

| Option | Default | What it does |
| :--- | :--- | :--- |
| `accountId` | the `APPROOV_ACCOUNT_ID` environment variable at prebuild | Your Approov account ID, written to the Android manifest and `Info.plist` for the native initialization. Surrounding whitespace is trimmed. Required while `nativeInitialize` is true: prebuild fails without it. |
| `comment` | none | The initialization comment, passed to the Approov SDK unchanged (for example `options:...` startup flags; see [SDK initialization options](https://docs.approov.io/direct-sdk-integration/initialization-options/#sdk-initialization-options)). An empty string is a comment, different from none. Control characters are rejected. |
| `nativeInitialize` | `true` | Inserts the guarded native initialization in `MainApplication` and the Swift `AppDelegate`. With `false` the plugin still adds the packages, the permissions and, when given, the account ID; your app then initializes from JavaScript. |
| `android.version` | `3.8.0` | The version of both `io.approov:service.android` and the Gradle plugin `io.approov:service.android-gradle-plugin`. They are released together and must match, so there is one option for both. An exact version only: `+` and `latest.` are rejected. |
| `android.repositories` | `["mavenCentral"]` | Where both artifacts come from: `mavenCentral`, `mavenLocal`, `google`, `gradlePluginPortal`, an `https://` URL, or a local Maven repository directory (relative to the project root). `mavenLocal`, URL and directory repositories are limited to the group `io.approov`. A `mavenCentral`, `google` or `gradlePluginPortal` the template already has is not added again. |
| `android.gradlePluginPath` | none | Local development only: an `approov-gradle-plugin` source directory, included in the build with `includeBuild` in place of the published Gradle plugin. |
| `android.cronetDependencyPackages` | the Gradle plugin's default, `["com.margelo.nitro.nitrofetch"]` | Dependency packages whose Cronet engine creation the Gradle plugin protects; `[]` limits it to your app's own classes. |
| `ios.version` | `3.8.0` | The git tag of `approov-service-ios`. Not together with `ios.podPath`. |
| `ios.sdkVersion` | `3.5.3` | The git tag of `approov-ios-sdk`, the Approov SDK pod the iOS package depends on. It must satisfy the iOS package's podspec, which for `approov-service-ios` 3.8.0 requires exactly `3.5.3`; `pod install` fails otherwise. |
| `ios.podPath` | none | Local development only: an `approov-service-ios` checkout (with its podspec), used as a `:path` pod in place of the GitHub tag. |

Unknown options and malformed values fail the prebuild, and `expo config`, with a message naming the option. The account ID is checked only when native files are generated, so `expo config`, `expo start`, EAS Update and the `expo-constants` build step work without it.

The local development options are never applied unless you set them, and a path that does not exist fails the config. Remove them before a release or an EAS build: the paths exist only on your machine.

## WHAT THE PLUGIN CHANGES

Every insertion sits between `@generated begin approov-...` and `@generated end approov-...` markers. A later prebuild replaces it, an option you remove removes what it added, and running prebuild twice gives identical files.

| File | Change |
| :--- | :--- |
| `android/settings.gradle` | `includeBuild(...)` of the local Gradle plugin, only with `android.gradlePluginPath` |
| `android/build.gradle` | `classpath("io.approov:service.android-gradle-plugin:<android.version>")` in `buildscript`; the configured repositories in `buildscript` and `allprojects` |
| `android/app/build.gradle` | `apply plugin: "io.approov.gradle"` on the line after `com.android.application`; `implementation("io.approov:service.android:<android.version>")`; an `approov { cronetDependencyPackages = [...] }` block when that option is set |
| `AndroidManifest.xml` | the `INTERNET` and `ACCESS_NETWORK_STATE` permissions; application meta-data `io.approov.ACCOUNT_ID` and, with a comment, `io.approov.INIT_COMMENT` |
| `MainApplication.kt` or `.java` | the guarded initialization right after `super.onCreate()` |
| `ios/Podfile` | `pod 'approov-ios-sdk', :git => ..., :tag => '<ios.sdkVersion>'` and `pod 'approov-service-ios', :git => ..., :tag => '<ios.version>'` (or `:path`) in the app target |
| `Info.plist` | `ApproovAccountID` and, with a comment, `ApproovInitComment` |
| `AppDelegate.swift` | `import ApproovService` and the guarded initialization as the first statements of `application(_:didFinishLaunchingWithOptions:)` |

The values are written so that the app reads back exactly what you configured. In the manifest the first character is a `\uXXXX` escape and every backslash is doubled, because the Android build would otherwise turn a value that looks like a number, color, boolean or resource reference into that type (`007` into `7`). In `Info.plist` every `$` is written as `$$`, because Xcode expands `$(NAME)`, `${NAME}` and `$NAME` there. So the manifest shows `#your-account#...` and the app reads `#your-account#...`.

The account ID is read from the manifest and `Info.plist` at run time and never written into source code. The plugin has no option for a development key; register development devices or use a development key the way the Approov documentation describes, never through the app config.

## INITIALIZATION

**Native.** By default the plugin initializes Approov in `Application.onCreate` and `application(_:didFinishLaunchingWithOptions:)`, before React Native starts, so no request from JavaScript or a native library goes out before initialization. The inserted code reads the account ID and comment back and calls `ApproovService.initialize` inside a guard. Initialization fails only if the value it receives is not a complete, unaltered account ID, which the Approov SDK then rejects. In that case, or when the value is missing from the manifest or `Info.plist`, the code logs the problem (the exception's class only, never the value) and initializes with an empty account ID: the app starts in **bypass mode**, without Approov protection, and your backend stays the enforcement point. It then logs both state flags, which is the line to look for:

```
ApproovInit: Approov service enabled=true protection enabled=true
```

`protection enabled=false` means bypass mode. The native initialization passes only the account ID and comment; it configures nothing.

**JavaScript as well.** This applies once the package's native module is built on the universal packages; while the 3.5.x module is excluded ([below](#the-35x-native-module)) there is no JavaScript `initialize` to call. One app process has one Approov service state, whichever language calls `initialize`. A JavaScript `ApproovService.initialize` (or `ApproovProvider`) after the native one, with the same account ID **and the same comment**, returns at once and changes nothing: both flags are already true and the configuration is kept. A different account ID or comment, including no comment against an empty one, is rejected by the Approov SDK and the error reaches the second caller as a rejected promise, while the native initialization stays in effect. So either leave initialization to the plugin, or pass JavaScript exactly the account ID and the `comment` you gave the plugin; `await ApproovService.initialize(...)` then resolves at once, and is the point after which the app configures Approov from JavaScript. A rejection here does not mean Approov is off: the native initialization is still in effect, so check the protection state before blocking requests on it.

**JavaScript only.** With `nativeInitialize: false` nothing is initialized natively. Call `initialize` from JavaScript before the first request, await it and then configure; a request made before it goes out without Approov protection. This needs a native module built on the universal packages, like the case above.

## CONFIGURATION

The plugin performs no configuration at all. Its native initialization calls `initialize` with the account ID and comment, and that is all; every setting (the token, trace, status and binding headers, substitution headers and query parameters, exclusions, the service mutator, message signing, the logging level, the stale protection refresh period on Android, and so on) is made by your app after initialization, from JavaScript or natively. The order is always initialize, then configure. From JavaScript, await `initialize` with the account ID and comment you gave the plugin, which resolves at once when the native initialization already ran with them, and configure straight after it:

```js
import { ApproovService } from '@approov/approov-service-react-native';

// the account ID and comment you gave the plugin: resolves at once after its native initialization
await ApproovService.initialize('<your-approov-account-id>');
// then configure, before the app issues protected requests; only the settings your app uses, for example:
ApproovService.setTokenHeader('Authorization', 'Bearer ');
```

`initialize` never resets configuration, and configuration made before it is kept, but initialize, then configure, is the documented pattern.

**Requests before your configuration.** Native initialization runs before React Native starts, so no request goes out before initialization, but a request can be processed before your configuration call runs. Such a request uses the defaults in force at that moment: the `DEFAULT` service mutator (`CLOSE_FAILURE` in 3.8.0), the `Approov-Token` header with no prefix, no binding header, no exclusions, no substitutions (so a secure string placeholder goes out unchanged), message signing off and logging at `INFO`. A configuration change applies only to requests processed after the call; a request already in flight is not processed again. Connection validation does not depend on this order: the trust roots and keys come from the Approov SDK's configuration and apply from initialization. Requests that can run before your configuration call include:

- native code at startup, for example expo-updates checking for an update, react-native-nitro-fetch `prefetchOnAppStart` (not supported with protected endpoints on Android; see the Android package's ADVANCED.md), native SDKs, and Android headless JS tasks;
- JavaScript modules that fetch when they are imported.

Configure immediately after `initialize`, before the app issues protected requests, and keep startup requests to protected hosts after that point.

## THE 3.5.x NATIVE MODULE

This package also contains the 3.5.x React Native native module, which brings its own Approov layer for Android and iOS. Linking it next to the universal packages the plugin adds puts two Approov layers in one app, which conflict, so until the package's native module is built on the universal packages exclude it from autolinking in `react-native.config.js`:

```js
module.exports = {
  dependencies: {
    '@approov/approov-service-react-native': {
      platforms: { android: null, ios: null },
    },
  },
};
```

Without the native module the JavaScript `ApproovService` API is not available, so keep `nativeInitialize: true` and configure Approov natively, after the plugin's initialization.

## EAS BUILD

- EAS Build runs `expo prebuild` on its servers, so the account ID must be there: keep `accountId` in the app config, or define `APPROOV_ACCOUNT_ID` as an EAS environment variable for the build profile.
- EAS fetches the published artifacts: `android.version` must be on Maven Central and the `ios.version` and `ios.sdkVersion` tags on GitHub. The local development options (`mavenLocal`, directory repositories, `-local` versions, `android.gradlePluginPath`, `ios.podPath`) do not work there.
- EAS Update needs no account ID; native initialization ships with the binary.

## NOT SUPPORTED

- **Objective-C AppDelegate** (Expo SDK 52 and earlier): the universal iOS package has a Swift-only API, so prebuild fails with a message unless `nativeInitialize` is `false`. Upgrade to Expo SDK 53 or later, or call `ApproovService.initialize` from Swift yourself.
- **Kotlin DSL Gradle files** (`build.gradle.kts`): prebuild fails naming the file.
- A `MainApplication` without `super.onCreate()` in `onCreate()`, or an `AppDelegate` without `application(_:didFinishLaunchingWithOptions:)`: prebuild fails rather than leave the app uninitialized.

## TROUBLESHOOTING

| Symptom | Cause and fix |
| :--- | :--- |
| prebuild: `the Approov account ID is missing` | Set `accountId`, or `APPROOV_ACCOUNT_ID` for the prebuild (on EAS, as an EAS environment variable), or set `nativeInitialize: false`. |
| prebuild: `"accountId" is still the placeholder` | Replace `<your-approov-account-id>` with your account ID. |
| prebuild: `unterminated generated block` | A generated block was edited by hand. Run `npx expo prebuild --clean`. |
| Gradle: `Approov version mismatch` | Something in the app resolves a different `io.approov:service.android` from `android.version`; align the versions. |
| `pod install` cannot find a tag | The `ios.version` or `ios.sdkVersion` tag is not published yet; use `ios.podPath` for local development. |
| Duplicate classes, or two Approov layers logging | The 3.5.x native module is still linked; see [the 3.5.x native module](#the-35x-native-module). |
| Log: `No Approov account ID in the manifest meta-data` or `in Info.plist` | The value is missing from the generated files; run prebuild again with the account ID set. The app is in bypass mode. |
| Log: `Approov initialization failed (<exception class>)` | The value did not reach the SDK intact. Check it against `approov sdk -getConfigString` and run prebuild again. The app is in bypass mode. |
| JavaScript `initialize` rejected after a native initialization | Different account ID or comment from the plugin's; see [initialization](#initialization). |
| The manifest shows `#...` | Expected: see [what the plugin changes](#what-the-plugin-changes). |
| A startup request went out with a placeholder, the default token header or no signature | It was processed before your configuration call ran, so it used the defaults; configure straight after `initialize` (see [configuration](#configuration)). |
