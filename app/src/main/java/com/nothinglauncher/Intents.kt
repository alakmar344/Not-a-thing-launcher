package com.nothinglauncher

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.view.View
import android.widget.Toast

/** Intents opened from the Not a Thing elements, with graceful fallbacks. */
object Intents {

    fun start(context: Context, intent: Intent, source: View? = null): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = source?.let {
            android.app.ActivityOptions.makeClipRevealAnimation(it, 0, 0, it.width, it.height).toBundle()
        }
        context.startActivity(intent, options)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun startOrToast(context: Context, source: View?, vararg intents: Intent) {
        if (intents.none { start(context, it, source) }) {
            Toast.makeText(context, R.string.nt_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    fun openAlarms(context: Context, source: View? = null) =
        startOrToast(context, source, Intent(AlarmClock.ACTION_SHOW_ALARMS))

    fun openCalendar(context: Context, source: View? = null) {
        val today = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").let {
            ContentUris.appendId(it, System.currentTimeMillis()); it.build()
        }
        startOrToast(
            context, source,
            Intent(Intent.ACTION_VIEW, today),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
        )
    }

    fun openBatterySettings(context: Context, source: View? = null) = startOrToast(
        context, source,
        Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
    )

    fun openNotificationAccess(context: Context, source: View? = null) = startOrToast(
        context, source,
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )

    fun openWallpaperPicker(context: Context) = startOrToast(
        context, null,
        Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), null)
    )

    fun openApp(context: Context, packageName: String, source: View? = null) {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        startOrToast(context, source, launch)
    }
}
