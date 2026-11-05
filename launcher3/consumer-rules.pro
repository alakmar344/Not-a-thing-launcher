# Launcher3 keeps classes referenced by name from XML/resources (see proguard.flags).

# CustomWidgetManager (AOSP) and PluginManagerWrapper (src/overrides) both instantiate every class
# listed in R.array.custom_widget_providers with
#     Class.forName(name).getDeclaredConstructor(Context.class).newInstance(context)
# R8 can't see string-array references, so without this rule a minified release build deletes the
# widget classes that nothing else references (and the (Context) constructor of the ones that are
# referenced), and every in-process widget silently fails to load. Keep the class, its name, and
# exactly that constructor; everything else in it can still be shrunk/optimized.
-keep class * implements com.android.systemui.plugins.CustomWidgetPlugin {
    <init>(android.content.Context);
}
