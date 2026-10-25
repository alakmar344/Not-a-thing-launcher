package com.nothinglauncher.ui

import android.content.Context
import com.nothinglauncher.LauncherPrefs

/** Sizes derived from settings and screen density, shared by every view on the home screen. */
class DeviceProfile(context: Context, prefs: LauncherPrefs) {
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = density * context.resources.configuration.fontScale.coerceIn(0.85f, 1.3f)

    val columns = prefs.gridColumns
    val rows = prefs.gridRows

    private val sizeScale = when (prefs.iconSize) {
        LauncherPrefs.IconSize.SMALL -> 0.84f
        LauncherPrefs.IconSize.MEDIUM -> 1.0f
        LauncherPrefs.IconSize.LARGE -> 1.14f
    }

    private val baseIconDp = when {
        columns <= 4 -> 54f
        columns == 5 -> 48f
        else -> 42f
    }

    val iconSizePx = (baseIconDp * sizeScale * density).toInt()
    val hotseatIconSizePx = (minOf(56f, baseIconDp + 2f) * sizeScale * density).toInt()
    val drawerIconSizePx = iconSizePx
    val folderIconSizePx = (minOf(54f, baseIconDp) * density).toInt()
    val labelTextSizePx = (if (columns >= 6) 11f else 12f) * scaledDensity
    val iconLabelGapPx = (6 * density).toInt()
    val pagePaddingPx = (12 * density).toInt()
    val hotseatPaddingPx = (16 * density).toInt()

    val showLabels = prefs.showHomeLabels
    val showDockLabels = prefs.showDockLabels
    val labelShadow = prefs.background == LauncherPrefs.Background.WALLPAPER
    val accent = prefs.accentColor()
    val dotsEnabled = prefs.notificationDots
}
