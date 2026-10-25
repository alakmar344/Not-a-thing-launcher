package com.nothinglauncher

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle

/**
 * A launchable activity as shown by the launcher.
 *
 * [icon] is already rendered in the user's chosen icon style (see [IconFactory]) and carries
 * the work badge for managed-profile apps.
 * [user] is null only in previews/tests; launching falls back to the current user.
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    val user: UserHandle? = null,
    val isSystemApp: Boolean = false,
    val userSerial: Long = mainUserSerial,
    val firstInstallTime: Long = 0L
) {
    val componentName: ComponentName get() = ComponentName(packageName, activityName)

    val isWorkProfile: Boolean get() = userSerial != mainUserSerial

    /**
     * Stable identifier used for persistence. Apps of the main profile keep the v1–v8 format
     * ("package/activity") so hidden-app and recents lists survive upgrades untouched.
     */
    val key: String get() = makeKey(packageName, activityName, userSerial)

    /** First letter used by the drawer's A–Z scroller ('#' for anything non-alphabetic). */
    val sectionLetter: Char
        get() {
            val c = label.trim().firstOrNull()?.uppercaseChar() ?: '#'
            return if (c in 'A'..'Z') c else '#'
        }

    companion object {
        /** Serial number of the profile the launcher runs in; set once by [LauncherModel]. */
        @Volatile
        var mainUserSerial: Long = 0L

        fun makeKey(pkg: String, cls: String, serial: Long): String =
            if (serial == mainUserSerial) "$pkg/$cls" else "$pkg/$cls@$serial"
    }
}
