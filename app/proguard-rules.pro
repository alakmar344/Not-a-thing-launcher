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
# R8. Scoped like AOSP's own rule, R8 does real work again (measured: classes.dex 4.48 MB ->
# 3.16 MB, APK 5.21 MB -> 4.79 MB).
#
# Caveat: `allowshrinking` means R8 *deletes* classes and constructors it can't see used, and it
# can't see uses that only exist as strings in resources. Anything Launcher3 instantiates by name
# needs its own precise rule — see launcher3/consumer-rules.pro (custom widgets) and AOSP's
# ResourceBasedOverride rule — and tools/verify_release_reflection.py checks the real release dex
# for this in CI. Without the widget rule, all four Not a Thing widgets vanished from release
# builds while every (unminified) debug test still passed.
-keep,allowshrinking,allowoptimization class com.nothinglauncher.** { *; }
-keep,allowshrinking,allowoptimization class com.android.systemui.plugins.** { *; }

# Generated protobuf-lite messages are accessed reflectively.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
