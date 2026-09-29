# Approov SDK Consumer Rules

# Retain public interfaces for the app and service layer
-keep class com.criticalblue.approovsdk.Approov {
    public *;
}
-keep class com.criticalblue.approovsdk.Approov$* {
    public *;
}

# Retain all native methods to ensure JNI binds correctly
-keepclasseswithmembernames class com.criticalblue.approovsdk.** {
    native <methods>;
}

# Keep classes containing native methods from being renamed, as JNI often relies on class names
-keepnames class com.criticalblue.approovsdk.** {
    native <methods>;
}

# The Approov SDK AAR ships no R8 rules, and its native library constructs Java objects it
# looks up by name (for example AttestationServicesResponse(String, byte[], long, int, int)).
# R8 removes members only native code uses, and initialize() then fails. Keep the whole SDK
# until the SDK ships its own rules (approov/core-project-approov#807). The SDK references
# Play Integrity and Play services tasks classes that apps without those libraries omit.
-keep class com.criticalblue.approovsdk.** { *; }
-dontwarn com.google.android.gms.tasks.OnFailureListener
-dontwarn com.google.android.gms.tasks.OnSuccessListener
-dontwarn com.google.android.gms.tasks.Task
-dontwarn com.google.android.play.core.integrity.IntegrityManager
-dontwarn com.google.android.play.core.integrity.IntegrityManagerFactory
-dontwarn com.google.android.play.core.integrity.IntegrityServiceException
-dontwarn com.google.android.play.core.integrity.IntegrityTokenRequest$Builder
-dontwarn com.google.android.play.core.integrity.IntegrityTokenRequest
-dontwarn com.google.android.play.core.integrity.IntegrityTokenResponse

# ApproovService resolves these React Native members by name at runtime. Keep both
# Java and Kotlin layouts: R8 cannot infer the names through resolveField().
-keepclassmembers class com.facebook.react.modules.network.OkHttpClientProvider {
    *** sFactory;
    *** factory;
    *** INSTANCE;
}
-keepclassmembers class com.facebook.react.modules.network.NetworkingModule {
    *** mCustomClientBuilder;
    *** customClientBuilder;
    *** mClient;
    *** client;
    *** mCookieJarContainer;
    *** cookieJarContainer;
    *** mCookieHandler;
    *** cookieHandler;
    *** INSTANCE;
    public static void setCustomClientBuilder(...);
}

# getPinningDiagnostics reports interceptor class names; keep Approov's own readable in
# minified builds. Shrinking is still allowed.
-keepnames class io.approov.reactnative.ApproovInterceptor
-keepnames class io.approov.reactnative.ApproovPinningInterceptor
