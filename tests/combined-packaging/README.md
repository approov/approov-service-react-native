# Combined Android service packaging check

This small Android app includes the React Native service project and the
published `io.approov:service.okhttp:3.5.8` artifact. The release build runs
R8 and deliberately keeps both sets of `sfv` and `sig` helper classes. It
fails if their names collide; it applies no removal patch or duplicate-class
suppression.

With Android SDK and Gradle 9 available, run:

```sh
gradle -p tests/combined-packaging :app:assembleRelease -Pintegration=both
```

The `integration` property also accepts `react-native` and `okhttp` for
standalone release builds. This check covers packaging only; it does not
exercise live token fetching, TLS pinning, or message signing.
