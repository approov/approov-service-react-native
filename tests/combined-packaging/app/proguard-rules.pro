# Keep this app free of duplicate-class suppression or helper removal rules.
-keep class io.approov.internal.reactnative.util.http.sfv.** { *; }
-keep class io.approov.internal.reactnative.util.sig.** { *; }
-keep class io.approov.util.http.sfv.** { *; }
-keep class io.approov.util.sig.** { *; }

# A React Native app references ApproovPackage from its application class. Keep it as that entry
# point, so R8 processes the service layer and its consumer rules as it would in a real app.
-keep class io.approov.reactnative.ApproovPackage { public <init>(); }

# Written for the checkKeptMembers task.
-printseeds build/outputs/r8-seeds.txt
