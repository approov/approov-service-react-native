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
