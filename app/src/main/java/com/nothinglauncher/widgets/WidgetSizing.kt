package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import kotlin.math.ceil
import kotlin.math.max

/** Converts AppWidget size metadata to grid spans and reports real sizes back to providers. */
object WidgetSizing {

    /** Default span for a provider, honouring Android 12 target cells when present. */
    fun defaultSpan(context: Context, info: AppWidgetProviderInfo, columns: Int, rows: Int): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceIn(1, columns) to info.targetCellHeight.coerceIn(1, rows)
        }
        val d = context.resources.displayMetrics.density
        return spanFor(info.minWidth / d, columns) to spanFor(info.minHeight / d, rows)
    }

    /** Minimum span the widget can be resized down to. */
    fun minSpan(context: Context, info: AppWidgetProviderInfo, columns: Int, rows: Int): Pair<Int, Int> {
        val d = context.resources.displayMetrics.density
        val canX = (info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0
        val canY = (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL) != 0
        val def = defaultSpan(context, info, columns, rows)
        val minW = if (info.minResizeWidth in 1 until info.minWidth) info.minResizeWidth else info.minWidth
        val minH = if (info.minResizeHeight in 1 until info.minHeight) info.minResizeHeight else info.minHeight
        val x = if (canX) spanFor(minW / d, columns) else def.first
        val y = if (canY) spanFor(minH / d, rows) else def.second
        return x.coerceAtMost(def.first) to y.coerceAtMost(def.second)
    }

    /** Maximum span (Android 12 maxResizeWidth/Height), otherwise the whole grid. */
    fun maxSpan(context: Context, info: AppWidgetProviderInfo, columns: Int, rows: Int): Pair<Int, Int> {
        val canX = (info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0
        val canY = (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL) != 0
        val def = defaultSpan(context, info, columns, rows)
        var mx = if (canX) columns else def.first
        var my = if (canY) rows else def.second
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val d = context.resources.displayMetrics.density
            if (canX && info.maxResizeWidth > 0) mx = max(def.first, spanFor(info.maxResizeWidth / d, columns))
            if (canY && info.maxResizeHeight > 0) my = max(def.second, spanFor(info.maxResizeHeight / d, rows))
        }
        return mx.coerceAtMost(columns) to my.coerceAtMost(rows)
    }

    fun isResizable(info: AppWidgetProviderInfo): Boolean =
        (info.resizeMode and (AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL)) != 0

    /** Android's classic formula: n cells ≈ 70·n − 30 dp. */
    private fun spanFor(dp: Float, limit: Int): Int = ceil((dp + 30f) / 70f).toInt().coerceIn(1, limit)

    /** Tells the provider how big it really is so it can pick the right layout. */
    fun updateSize(view: AppWidgetHostView, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val d = view.resources.displayMetrics.density
        val wDp = (widthPx / d).toInt()
        val hDp = (heightPx / d).toInt()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(wDp.toFloat(), hDp.toFloat())))
            } else {
                @Suppress("DEPRECATION")
                view.updateAppWidgetSize(Bundle(), wDp, hDp, wDp, hDp)
            }
        } catch (e: Exception) {
            // Provider process may be gone; it will get the size on its next update.
        }
    }

    fun optionsFor(widthDp: Int, heightDp: Int): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
    }
}
