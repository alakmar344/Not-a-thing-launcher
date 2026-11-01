package com.android.wm.shell;

/** Stand-in for the WM Shell aconfig flags (platform-only); all features off for a third-party home app. */
public final class Flags {
    private Flags() {}

    public static boolean enableTaskbarNavbarUnification() { return false; }
    public static boolean enableAppPairs() { return false; }
    public static boolean enableLeftRightSplitInPortrait() { return false; }
    public static boolean enableSplitContextual() { return false; }
}
