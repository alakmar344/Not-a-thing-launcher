package com.android.launcher3;

/** Build-time configuration of Launcher3, set for the Not a Thing launcher. */
public final class BuildConfig {
    private BuildConfig() {}

    /** Package of the shipped app (Launcher3 compares it against its own components). */
    public static final String APPLICATION_ID = "com.nothinglauncher";

    public static final boolean IS_STUDIO_BUILD = false;

    /**
     * No pinned search bar on the first page: like the v10 launcher, its top rows hold the
     * movable LED clock and battery tiles (placed on first run), and search lives in the pill
     * under the dock and at the top of the drawer.
     */
    public static final boolean QSB_ON_FIRST_SCREEN = false;

    public static final boolean WIDGET_ON_FIRST_SCREEN = false;

    public static final boolean IS_DEBUG_DEVICE = false;
}
