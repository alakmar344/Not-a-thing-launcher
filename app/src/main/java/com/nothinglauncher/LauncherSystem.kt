package com.nothinglauncher

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.view.accessibility.AccessibilityEvent

/**
 * Small, dependency-free bridge to the system surfaces a home app needs:
 * the HOME role, usage access, notification access and the optional
 * accessibility service used by the double-tap-to-sleep / pull-down gestures.
 */
object LauncherSystem {

    fun isDefaultHome(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return rm.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName == context.packageName
        } catch (e: Exception) {
            false
        }
    }

    /** Starts the native "choose your home app" request. Returns false if it cannot be shown. */
    fun requestDefaultHome(activity: Activity, requestCode: Int = REQUEST_HOME_ROLE): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = activity.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                return try {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
        return try {
            activity.startActivityForResult(Intent(Settings.ACTION_HOME_SETTINGS), requestCode)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    fun openUsageAccessSettings(context: Context) {
        startSafely(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    fun hasNotificationAccess(context: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (e: Exception) {
        false
    }

    fun openNotificationAccessSettings(context: Context) {
        // No resolveActivity() pre-check: with Android 11 package visibility it can report
        // null for the Settings app; startSafely already falls back to the main Settings page.
        startSafely(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun hasAccessibility(context: Context): Boolean {
        if (LauncherAccessibilityService.instance != null) return true
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            enabled.split(':').any { it.equals(accessibilityComponent(context).flattenToString(), true) }
        } catch (e: Exception) {
            false
        }
    }

    fun openAccessibilitySettings(context: Context) {
        startSafely(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun accessibilityComponent(context: Context) =
        ComponentName(context, LauncherAccessibilityService::class.java)

    fun lockScreen(): Boolean = LauncherAccessibilityService.instance?.lockScreen() == true

    fun expandNotifications(): Boolean = LauncherAccessibilityService.instance?.expandNotifications() == true

    fun hasActivityRecognition(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasRecordAudio(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startSafely(context: Context, intent: Intent) {
        try {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS)
                if (context !is Activity) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
            } catch (_: Exception) {
                AppLauncher.toast(context, R.string.cannot_open)
            }
        }
    }

    const val REQUEST_HOME_ROLE = 4001
}

/**
 * Optional accessibility service. It is only used for two explicit home gestures
 * (sleep / notification shade); the launcher is fully usable without it.
 */
class LauncherAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // The service is unbound (user turned it off) before onDestroy; drop the stale handle.
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun lockScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    // GLOBAL_ACTION_NOTIFICATIONS exists since API 16, so it works on every supported release.
    fun expandNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    companion object {
        @Volatile
        var instance: LauncherAccessibilityService? = null
            private set
    }
}
