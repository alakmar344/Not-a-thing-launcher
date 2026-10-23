package com.nothinglauncher

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Turns any app icon into a consistent circular launcher icon in the Nothing OS design language.
 *
 * MONO (the Nothing look):
 *  1. Android 13+ themed icon (monochrome layer) → monochrome glyph on themed disc.
 *  2. Adaptive icon with a sparse foreground → its foreground as themed glyph.
 *  3. Anything else → the whole icon rendered in sleek monochrome with contrast boost.
 * COLOR: the original artwork, uniformly clipped to a circle.
 */
object IconFactory {

    private const val DISC_COLOR_DARK = 0xFF1C1C1F.toInt()
    private const val GLYPH_COLOR_DARK = 0xFFF2F2F0.toInt()
    private const val RIM_COLOR_DARK = 0xFF2C2C31.toInt()

    private const val DISC_COLOR_LIGHT = 0xFFECECE8.toInt()
    private const val GLYPH_COLOR_LIGHT = 0xFF0E0E10.toInt()
    private const val RIM_COLOR_LIGHT = 0xFFD8D8D4.toInt()

    /** The three layers of an adaptive icon (monochrome only exists on Android 13+). */
    class Layers(val background: Drawable?, val foreground: Drawable?, val monochrome: Drawable?)

    fun render(resources: Resources, source: Drawable, style: LauncherPrefs.IconStyle, sizePx: Int): Drawable {
        val layers = if (source is AdaptiveIconDrawable) {
            Layers(
                source.background,
                source.foreground,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) source.monochrome else null
            )
        } else null
        val isLight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
        return renderInternal(resources, source, layers, style, sizePx, isLight)
    }

    /** Renders from explicit adaptive layers (used directly by previews/tests). */
    fun renderLayers(resources: Resources, layers: Layers, style: LauncherPrefs.IconStyle, sizePx: Int): Drawable {
        val isLight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
        return renderInternal(resources, layers.foreground ?: layers.background ?: ColorDrawable(0), layers, style, sizePx, isLight)
    }

    private fun renderInternal(
        resources: Resources,
        source: Drawable,
        layers: Layers?,
        style: LauncherPrefs.IconStyle,
        sizePx: Int,
        isLight: Boolean
    ): Drawable {
        val bitmap = try {
            when (style) {
                LauncherPrefs.IconStyle.MONO -> renderMono(source, layers, sizePx, isLight)
                LauncherPrefs.IconStyle.COLOR -> renderColor(source, layers, sizePx, isLight)
            }
        } catch (e: Exception) {
            // Some icons misbehave when drawn off-screen; never let that break the launcher.
            renderFallback(source, sizePx, isLight)
        }
        return BitmapDrawable(resources, bitmap)
    }

    private fun circlePath(size: Int) = Path().apply {
        addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
    }

    private fun drawDisc(canvas: Canvas, size: Int, isLight: Boolean) {
        val discColor = if (isLight) DISC_COLOR_LIGHT else DISC_COLOR_DARK
        val rimColor = if (isLight) RIM_COLOR_LIGHT else RIM_COLOR_DARK
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = discColor }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size / 56f
            color = rimColor
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - rim.strokeWidth / 2f, rim)
    }

    /** Draws an adaptive-icon layer so its 72/108 visible area fills [size]. */
    private fun drawLayer(canvas: Canvas, layer: Drawable, size: Int, filterPaint: Paint? = null) {
        val extra = size / 4 // 108 / 72 = 1.5 → a quarter of the size on each side
        val d = layer.mutate()
        d.setBounds(-extra, -extra, size + extra, size + extra)
        if (filterPaint != null) {
            val tmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.draw(Canvas(tmp))
            canvas.drawBitmap(tmp, 0f, 0f, filterPaint)
            tmp.recycle()
        } else {
            d.draw(canvas)
        }
    }

    private fun renderMono(source: Drawable, layers: Layers?, size: Int, isLight: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawDisc(canvas, size, isLight)
        canvas.save()
        canvas.clipPath(circlePath(size))

        val glyphColor = if (isLight) GLYPH_COLOR_LIGHT else GLYPH_COLOR_DARK
        val monochrome = layers?.monochrome
        val foreground = layers?.foreground
        when {
            monochrome != null -> {
                val tint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(glyphColor, PorterDuff.Mode.SRC_IN)
                }
                drawLayer(canvas, monochrome, size, tint)
            }
            foreground != null && isSparse(foreground, size) -> {
                drawLayer(canvas, foreground, size, monochromeGlyphPaint(isLight))
            }
            layers != null -> {
                val tmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val tc = Canvas(tmp)
                layers.background?.let { drawLayer(tc, it, size) }
                layers.foreground?.let { drawLayer(tc, it, size) }
                canvas.drawBitmap(tmp, 0f, 0f, greyPaint(contrast = 0.85f, lift = if (isLight) -10f else 10f))
                tmp.recycle()
            }
            else -> drawLegacy(canvas, source, size, greyPaint(contrast = 0.9f, lift = if (isLight) -15f else 20f))
        }
        canvas.restore()
        return bmp
    }

    private fun renderColor(source: Drawable, layers: Layers?, size: Int, isLight: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (layers != null) {
            canvas.save()
            canvas.clipPath(circlePath(size))
            layers.background?.let { drawLayer(canvas, it, size) }
            layers.foreground?.let { drawLayer(canvas, it, size) }
            canvas.restore()
        } else {
            drawDisc(canvas, size, isLight)
            canvas.save()
            canvas.clipPath(circlePath(size))
            drawLegacy(canvas, source, size, null)
            canvas.restore()
        }
        return bmp
    }

    private fun renderFallback(source: Drawable, size: Int, isLight: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawDisc(canvas, size, isLight)
        try {
            drawLegacy(canvas, source, size, null)
        } catch (_: Exception) {
        }
        return bmp
    }

    /** Legacy (non-adaptive) icons are drawn at ~70% on the disc. */
    private fun drawLegacy(canvas: Canvas, source: Drawable, size: Int, paint: Paint?) {
        val inner = (size * 0.70f).toInt()
        val offset = (size - inner) / 2
        val tmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val d = source.mutate()
        d.setBounds(offset, offset, offset + inner, offset + inner)
        d.draw(Canvas(tmp))
        canvas.drawBitmap(tmp, 0f, 0f, paint ?: Paint(Paint.FILTER_BITMAP_FLAG))
        tmp.recycle()
    }

    /** True if the layer covers less than ~55% of the visible circle (i.e. it's a glyph). */
    private fun isSparse(layer: Drawable, size: Int): Boolean {
        val probe = 48
        val tmp = Bitmap.createBitmap(probe, probe, Bitmap.Config.ARGB_8888)
        val extra = probe / 4
        val d = layer.mutate()
        d.setBounds(-extra, -extra, probe + extra, probe + extra)
        d.draw(Canvas(tmp))
        var inside = 0
        var opaque = 0
        val c = probe / 2f
        for (y in 0 until probe) for (x in 0 until probe) {
            val dx = x + 0.5f - c
            val dy = y + 0.5f - c
            if (dx * dx + dy * dy > c * c) continue
            inside++
            if ((tmp.getPixel(x, y) ushr 24) > 0x80) opaque++
        }
        tmp.recycle()
        // Restore bounds for the real render.
        d.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
        return inside > 0 && opaque.toFloat() / inside < 0.55f
    }

    private fun monochromeGlyphPaint(isLight: Boolean): Paint {
        val m = ColorMatrix().apply { setSaturation(0f) }
        val liftVal = if (isLight) -50f else 140f
        val factor = if (isLight) 0.85f else 0.45f
        val lift = ColorMatrix(
            floatArrayOf(
                factor, 0f, 0f, 0f, liftVal,
                0f, factor, 0f, 0f, liftVal,
                0f, 0f, factor, 0f, liftVal,
                0f, 0f, 0f, 1f, 0f
            )
        )
        m.postConcat(lift)
        return Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) }
    }

    private fun greyPaint(contrast: Float, lift: Float): Paint {
        val m = ColorMatrix().apply { setSaturation(0f) }
        val c = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, lift,
                0f, contrast, 0f, 0f, lift,
                0f, 0f, contrast, 0f, lift,
                0f, 0f, 0f, 1f, 0f
            )
        )
        m.postConcat(c)
        return Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) }
    }
}
