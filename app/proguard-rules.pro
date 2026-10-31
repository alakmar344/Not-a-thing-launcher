# Add project specific ProGuard rules here.
# The launcher's own classes are kept whole: services, activities and custom views are
# instantiated by the framework / layout inflater by name.
-keep class com.nothinglauncher.** { *; }
-keepclassmembers class com.nothinglauncher.** { *; }

# Bound by the system by class name (notification access / accessibility settings).
-keep class com.nothinglauncher.MediaNotificationListenerService { *; }
-keep class com.nothinglauncher.LauncherAccessibilityService { *; }

# AndroidX
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep R class members
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Material Components
-keep class com.google.android.material.** { *; }

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
