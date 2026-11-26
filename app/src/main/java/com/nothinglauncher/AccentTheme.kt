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

    enum class WidgetSurface(val labelRes: Int) {
        DEFAULT(R.string.nt_surface_default),
        DARK(R.string.nt_surface_dark),
        PAPER(R.string.nt_surface_paper),
        GLASS(R.string.nt_surface_glass)
    }

    data class SurfaceColors(
        val onDot: Int,
        val offDot: Int,
        val textPrimary: Int,
        val textSecondary: Int
    )

    fun surfaceColors(context: Context, widgetKey: String, defaultLight: Boolean = false): SurfaceColors {
        val surface = NothingPrefs.get(context).widgetSurface(widgetKey)
        val usePaper = surface == WidgetSurface.PAPER || (surface == WidgetSurface.DEFAULT && defaultLight)
        val useDark = surface == WidgetSurface.DARK
        return when {
            usePaper -> SurfaceColors(
                onDot = ContextCompat.getColor(context, R.color.ink),
                offDot = ContextCompat.getColor(context, R.color.paper_dot_off),
                textPrimary = ContextCompat.getColor(context, R.color.ink),
                textSecondary = ContextCompat.getColor(context, R.color.ink_secondary)
            )
            useDark -> SurfaceColors(
                onDot = 0xFFF4F4F2.toInt(),
                offDot = 0xFF1D1D21.toInt(),
                textPrimary = 0xFFF4F4F2.toInt(),
                textSecondary = 0xFF9C9CA1.toInt()
            )
            else -> SurfaceColors(
                onDot = ContextCompat.getColor(context, R.color.dot_on),
                offDot = ContextCompat.getColor(context, R.color.dot_off),
                textPrimary = ContextCompat.getColor(context, R.color.text_primary),
                textSecondary = ContextCompat.getColor(context, R.color.text_secondary)
            )
        }
    }

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
            WidgetSurface.DARK -> view.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF18181B.toInt(), 0xFF0C0C0E.toInt())
            ).apply {
                cornerRadius = radius
                setStroke((1 * density).toInt().coerceAtLeast(1), 0xFF232327.toInt())
            }
            WidgetSurface.PAPER -> view.setBackgroundResource(R.drawable.bg_tile_light)
            WidgetSurface.GLASS -> {
                val isDark = (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                view.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        if (isDark) 0xB8202026.toInt() else 0xCCFDFDFB.toInt(),
                        if (isDark) 0x98101014.toInt() else 0xAAEBEBE6.toInt()
                    )
                ).apply {
                    cornerRadius = radius
                    setStroke((1 * density).toInt().coerceAtLeast(1), ContextCompat.getColor(context, R.color.hairline_strong))
                }
            }
        }
    }
}
