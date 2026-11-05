# Launcher3's own keep rules (third_party/aosp/launcher3/proguard.flags) already keep
# com.android.launcher3.** with `allowshrinking,allowoptimization` — the class *names* survive
# (needed because ResourceBasedOverride/CustomWidgetPlugin resolve implementations by name from
# resources/XML) but R8 can still strip unused members and inline/optimize everything else. Those
# rules come in automatically through the :launcher3 module's consumerProguardFiles.
#
# This file used to add a second, plain `-keep class com.android.launcher3.** { *; }` /
# `-keep class com.nothinglauncher.** { *; }` on top. A plain -keep (no allowshrinking/
# allowoptimization) is strictly stronger and wins over the narrower rule above, so it was
# silently cancelling out Launcher3's own optimization-friendly rule for virtually the entire
# app — nothing in either package was actually being shrunk, inlined, or dead-code-eliminated by
# R8. Scoping these the same way AOSP scopes its own rule keeps every class name resolvable by
# reflection while letting R8 do real work on method bodies/unused code again.
-keep,allowshrinking,allowoptimization class com.nothinglauncher.** { *; }
-keep,allowshrinking,allowoptimization class com.android.systemui.plugins.** { *; }

# Generated protobuf-lite messages are accessed reflectively.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
