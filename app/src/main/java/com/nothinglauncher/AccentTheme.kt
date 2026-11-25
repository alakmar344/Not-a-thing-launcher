package com.nothinglauncher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Hardware-inspired accent palette and widget surface resolver.
 * Default remains signature Nothing Red (#D71921); users can switch hardware phosphor accents
 * in Settings -> Look, and every dot-matrix widget/surface resolves dynamically from here.
 */
object AccentTheme {

    enum class Palette(val colorHex: Int, val labelRes: Int) {
        RED(0xFFD71921.toInt(), R.string.accent_red),
        AMBER(0xFFFF8C1A.toInt(), R.string.accent_amber),
        PHOSPHOR(0xFF00D964.toInt(), R.string.accent_phosphor),
        COBALT(0xFF3B82F6.toInt(), R.string.accent_cobalt),
        MONO(0xFF9C9CA1.toInt(), R.string.accent_mono)
    }

    enum class WidgetSurface { DEFAULT, DARK, PAPER, GLASS }

    fun accentColor(context: Context): Int {
        val prefs = NothingPrefs.get(context)
        return when {
            prefs.focusMode -> ContextCompat.getColor(context, R.color.text_secondary)
            else -> prefs.accentPalette.colorHex
        }
    }

    fun rawAccentColor(context: Context): Int = NothingPrefs.get(context).accentPalette.colorHex

    fun softAccentColor(context: Context, alpha: Int = 0x38): Int {
        val base = accentColor(context)
        return Color.argb(alpha.coerceIn(0, 255), Color.red(base), Color.green(base), Color.blue(base))
    }

    fun tintDot(dotView: View, context: Context) {
        val c = accentColor(context)
        dotView.background?.mutate()?.setTint(c)
            ?: run { dotView.backgroundTintList = ColorStateList.valueOf(c) }
    }

    fun applyWidgetSurface(view: View, context: Context, widgetKey: String, defaultLight: Boolean = false) {
        val surface = NothingPrefs.get(context).widgetSurface(widgetKey)
        val density = context.resources.displayMetrics.density
        val radius = context.resources.getDimension(R.dimen.tile_radius)
        when (surface) {
            WidgetSurface.DEFAULT -> view.setBackgroundResource(
                if (defaultLight) R.drawable.bg_tile_light else R.drawable.bg_tile_dark
            )
            WidgetSurface.DARK -> view.setBackgroundResource(R.drawable.bg_tile_dark)
            WidgetSurface.PAPER -> view.setBackgroundResource(R.drawable.bg_tile_light)
            WidgetSurface.GLASS -> {
                val isDark = (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                view.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        if (isDark) 0xCC1B1B1F.toInt() else 0xCCFDFDFB.toInt(),
                        if (isDark) 0xAA0C0C0E.toInt() else 0xAAF0F0EC.toInt()
                    )
                ).apply {
                    cornerRadius = radius
                    setStroke((1 * density).toInt().coerceAtLeast(1), ContextCompat.getColor(context, R.color.hairline_strong))
                }
            }
        }
    }
}
