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
