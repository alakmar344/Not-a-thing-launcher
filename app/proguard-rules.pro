# Launcher3's own keep rules (third_party/aosp/launcher3/proguard.flags) are applied through the
# :launcher3 module's consumerProguardFiles.

# Classes named in resources/XML (Launcher3 resolves overridable components by class name).
-keep class com.nothinglauncher.** { *; }
-keep class com.android.launcher3.** { *; }
-keep class com.android.systemui.plugins.** { *; }

# Generated protobuf-lite messages are accessed reflectively.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
