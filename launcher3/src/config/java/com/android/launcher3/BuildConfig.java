package com.android.launcher3;

/** Build-time configuration of Launcher3, set for the Not a Thing launcher. */
public final class BuildConfig {
    private BuildConfig() {}

    /** Package of the shipped app (Launcher3 compares it against its own components). */
    public static final String APPLICATION_ID = "com.nothinglauncher";

    public static final boolean IS_STUDIO_BUILD = false;

    /** The first home page reserves its top row for the Not a Thing clock/search block. */
    public static final boolean QSB_ON_FIRST_SCREEN = true;

    public static final boolean WIDGET_ON_FIRST_SCREEN = false;

    public static final boolean IS_DEBUG_DEVICE = false;
}
