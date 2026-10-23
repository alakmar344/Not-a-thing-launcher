package com.nothinglauncher

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle

/**
 * A launchable activity as shown by the launcher.
 *
 * [icon] is already rendered in the user's chosen icon style (see [IconFactory]).
 * [user] is null only in previews/tests; launching falls back to the current user.
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    val user: UserHandle? = null,
    val isSystemApp: Boolean = false
) {
    val componentName: ComponentName get() = ComponentName(packageName, activityName)

    /** Stable identifier used for persistence ("package/activity"). */
    val key: String get() = "$packageName/$activityName"

    /** First letter used by the drawer's A–Z scroller ('#' for anything non-alphabetic). */
    val sectionLetter: Char
        get() {
            val c = label.trim().firstOrNull()?.uppercaseChar() ?: '#'
            return if (c in 'A'..'Z') c else '#'
        }
}
