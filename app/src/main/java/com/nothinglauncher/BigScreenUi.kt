package com.nothinglauncher

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Responsive layout breakpoints for tablets, unfolded foldables, landscape docks,
 * and desktop windowing across every Not a Thing surface.
 */
object BigScreenUi {

    fun isLargeScreen(context: Context): Boolean {
        val cfg = context.resources.configuration
        return cfg.smallestScreenWidthDp >= 600 || cfg.screenWidthDp >= 680
    }

    fun isUltraWide(context: Context): Boolean =
        context.resources.configuration.screenWidthDp >= 900

    /** Columns for Minimal List mode inside the App Drawer (1 on phone, 2 on tablet/foldable, 3 on ultra-wide). */
    fun drawerListColumns(context: Context): Int {
        val w = context.resources.configuration.screenWidthDp
        return when {
            w >= 900 -> 3
            w >= 580 -> 2
            else -> 1
        }
    }

    /** Columns for Command Search cards on wide displays. */
    fun searchCommandColumns(context: Context): Int =
        if (context.resources.configuration.screenWidthDp >= 680) 2 else 1

    /** Maximum width for floating bottom sheets on tablets/foldables so they never stretch edge-to-edge. */
    fun sheetWindowWidthPx(context: Context): Int {
        val cfg = context.resources.configuration
        if (cfg.screenWidthDp < 600) return ViewGroup.LayoutParams.MATCH_PARENT
        val dm = context.resources.displayMetrics
        val maxPx = (540f * dm.density).toInt()
        return min(dm.widthPixels - (48f * dm.density).toInt(), maxPx).coerceAtLeast(1)
    }

    /** Centers and caps wide single-column containers on tablets/foldables. */
    fun applyMaxWidth(view: View, maxWidthDp: Int = 760) {
        val cfg = view.resources.configuration
        if (cfg.screenWidthDp < maxWidthDp + 48) return
        val dm = view.resources.displayMetrics
        val maxPx = (maxWidthDp * dm.density).toInt()
        val lp = view.layoutParams ?: return
        lp.width = min(dm.widthPixels, maxPx)
        if (lp is FrameLayout.LayoutParams) lp.gravity = Gravity.CENTER_HORIZONTAL
        view.layoutParams = lp
    }
}
