package com.nothinglauncher.drawer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.android.launcher3.model.data.AppInfo
import com.nothinglauncher.R

/**
 * Classifies drawer apps into clean dot-matrix sections using Android [ApplicationInfo.category]
 * with package fallbacks so apps group cleanly into smart sections without extra permissions.
 */
object AppCategorizer {

    enum class Bucket(val titleRes: Int) {
        COMMUNICATION(R.string.nt_cat_comm),
        MEDIA(R.string.nt_cat_media),
        PRODUCTIVITY(R.string.nt_cat_prod),
        WEB_NAV(R.string.nt_cat_web),
        GAMES(R.string.nt_cat_games),
        UTILITIES(R.string.nt_cat_utils)
    }

    fun bucketFor(context: Context, info: AppInfo): Bucket {
        val pkg = info.componentName?.packageName.orEmpty().lowercase()
        val pm = context.packageManager
        val appInfo = try {
            if (pkg.isNotEmpty()) pm.getApplicationInfo(pkg, 0) else null
        } catch (_: Throwable) {
            null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appInfo != null) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_GAME -> return Bucket.GAMES
                ApplicationInfo.CATEGORY_AUDIO,
                ApplicationInfo.CATEGORY_VIDEO,
                ApplicationInfo.CATEGORY_IMAGE -> return Bucket.MEDIA
                ApplicationInfo.CATEGORY_SOCIAL -> return Bucket.COMMUNICATION
                ApplicationInfo.CATEGORY_NEWS,
                ApplicationInfo.CATEGORY_MAPS -> return Bucket.WEB_NAV
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> return Bucket.PRODUCTIVITY
                ApplicationInfo.CATEGORY_ACCESSIBILITY -> return Bucket.UTILITIES
            }
        }
        @Suppress("DEPRECATION")
        if (appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0) {
            return Bucket.GAMES
        }
        return when {
            pkg == "com.google.android.gm" || pkg.endsWith(".gm") ||
                pkg.contains("dialer") || pkg.contains("mms") || pkg.contains("messaging") ||
                pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("signal") ||
                pkg.contains("discord") || pkg.contains("slack") || pkg.contains("mail") ||
                pkg.contains("gmail") || pkg.contains("contacts") || pkg.contains("chat") ->
                Bucket.COMMUNICATION
            pkg.contains("camera") || pkg.contains("gallery") || pkg.contains("photos") ||
                pkg.contains("music") || pkg.contains("spotify") || pkg.contains("youtube") ||
                pkg.contains("vlc") || pkg.contains("podcast") || pkg.contains("video") ||
                pkg.contains("recorder") ->
                Bucket.MEDIA
            pkg.contains("maps") || pkg.contains("browser") || pkg.contains("chrome") ||
                pkg.contains("firefox") || pkg.contains("webview") || pkg.contains("waze") ||
                pkg.contains("weather") ->
                Bucket.WEB_NAV
            pkg.contains("docs") || pkg.contains("sheets") || pkg.contains("notes") ||
                pkg.contains("keep") || pkg.contains("calendar") || pkg.contains("drive") ||
                pkg.contains("tasks") || pkg.contains("office") || pkg.contains("notion") ->
                Bucket.PRODUCTIVITY
            pkg.contains("game") -> Bucket.GAMES
            else -> Bucket.UTILITIES
        }
    }
}
