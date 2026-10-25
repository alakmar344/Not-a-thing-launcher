package com.nothinglauncher

import android.app.ActivityOptions
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.view.View
import android.widget.Toast

/** Everything that leaves the launcher: starting apps, shortcuts and system screens. */
object AppLauncher {

    private fun launcherApps(context: Context): LauncherApps? =
        context.getSystemService(LauncherApps::class.java)

    private fun revealOptions(view: View?): Bundle? = try {
        view?.let {
            ActivityOptions.makeClipRevealAnimation(it, 0, 0, it.width, it.height).toBundle()
        }
    } catch (e: Exception) {
        null
    }

    private fun boundsOf(view: View?): Rect? = view?.let {
        val loc = IntArray(2)
        it.getLocationOnScreen(loc)
        Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
    }

    /** Launches [app] with a reveal animation from [source]. Returns true on success. */
    fun launch(context: Context, app: AppInfo, source: View?): Boolean {
        val user = app.user ?: Process.myUserHandle()
        val opts = revealOptions(source)
        try {
            val la = launcherApps(context)
            if (la != null) {
                la.startMainActivity(app.componentName, user, boundsOf(source), opts)
                return true
            }
        } catch (e: Exception) {
            // fall through to the package manager route
        }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = app.componentName
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            context.startActivity(intent, opts)
            true
        } catch (e: Exception) {
            toast(context, R.string.cannot_open)
            false
        }
    }

    fun openAppInfo(context: Context, app: AppInfo, source: View? = null) {
        try {
            val la = launcherApps(context) ?: throw IllegalStateException()
            la.startAppDetailsActivity(app.componentName, app.user ?: Process.myUserHandle(), boundsOf(source), revealOptions(source))
        } catch (e: Exception) {
            start(
                context,
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
            )
        }
    }

    fun uninstall(context: Context, app: AppInfo) {
        @Suppress("DEPRECATION")
        start(context, Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", app.packageName, null)))
    }

    // ---- App shortcuts (only available while we are the default home app) --------------

    fun shortcuts(context: Context, app: AppInfo): List<ShortcutInfo> {
        val la = launcherApps(context) ?: return emptyList()
        return try {
            if (!la.hasShortcutHostPermission()) return emptyList()
            val query = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setActivity(app.componentName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                )
            (la.getShortcuts(query, app.user ?: Process.myUserHandle()) ?: emptyList())
                .filter { it.isEnabled }
                .sortedWith(compareBy<ShortcutInfo> { if (it.isDynamic) 1 else 0 }.thenBy { it.rank })
                .take(4)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun shortcutIcon(context: Context, shortcut: ShortcutInfo): Drawable? = try {
        launcherApps(context)?.getShortcutIconDrawable(shortcut, context.resources.displayMetrics.densityDpi)
    } catch (e: Exception) {
        null
    }

    fun startShortcut(context: Context, shortcut: ShortcutInfo, source: View?) {
        try {
            launcherApps(context)?.startShortcut(shortcut, boundsOf(source), revealOptions(source))
        } catch (e: Exception) {
            toast(context, R.string.cannot_open)
        }
    }

    // ---- System screens ---------------------------------------------------------------

    fun webSearch(context: Context, query: String) {
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (!start(context, search, showError = false)) {
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        }
    }

    fun openAlarms(context: Context) {
        if (!start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS), showError = false)) {
            start(context, Intent(android.provider.Settings.ACTION_DATE_SETTINGS))
        }
    }

    fun openCalendar(context: Context) {
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(System.currentTimeMillis().toString())
            .build()
        if (!start(context, Intent(Intent.ACTION_VIEW, uri), showError = false)) {
            start(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))
        }
    }

    fun openBatterySettings(context: Context) {
        if (!start(context, Intent(Intent.ACTION_POWER_USAGE_SUMMARY), showError = false)) {
            start(context, Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    fun openWallpaperPicker(context: Context) {
        val chooser = Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), context.getString(R.string.wallpaper))
        start(context, chooser)
    }

    /** Opens the app currently playing audio; falls back to its details page. */
    fun openMediaApp(context: Context, packageName: String) {
        val pkg = packageName.substringBefore('/')
        if (pkg.isBlank()) return
        val launch = try { context.packageManager.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null }
        if (launch != null && start(context, launch, showError = false)) return
        start(
            context,
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)),
            showError = false
        )
    }

    /**
     * Pulls down the notification shade, preferring the optional accessibility service.
     * Returns false when the platform gives a normal app no way to do it, so the caller can
     * explain how to enable the gesture instead of failing silently.
     */
    @Suppress("WrongConstant")
    fun expandNotifications(context: Context): Boolean {
        if (LauncherSystem.expandNotifications()) return true
        return try {
            val service = context.getSystemService("statusbar") ?: return false
            val clazz = Class.forName("android.app.StatusBarManager")
            clazz.getMethod("expandNotificationsPanel").invoke(service)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun start(context: Context, intent: Intent, showError: Boolean = true): Boolean {
        return try {
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            if (showError) toast(context, R.string.cannot_open)
            false
        } catch (e: SecurityException) {
            if (showError) toast(context, R.string.cannot_open)
            false
        }
    }

    fun toast(context: Context, res: Int) {
        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
    }
}
