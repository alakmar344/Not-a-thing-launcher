package com.android.systemui.shared.system;

/**
 * Constants from the generated SysUI statsd atoms. Statsd logging is a platform privilege, so the
 * standalone launcher only needs the enum values Launcher3 maps user types to.
 */
public final class SysUiStatsLog {
    private SysUiStatsLog() {}

    public static final int LAUNCHER_UICHANGED__USER_TYPE__TYPE_UNKNOWN = 0;
    public static final int LAUNCHER_UICHANGED__USER_TYPE__TYPE_MAIN = 1;
    public static final int LAUNCHER_UICHANGED__USER_TYPE__TYPE_WORK = 2;
    public static final int LAUNCHER_UICHANGED__USER_TYPE__TYPE_CLONED = 3;
    public static final int LAUNCHER_UICHANGED__USER_TYPE__TYPE_PRIVATE = 4;
}
