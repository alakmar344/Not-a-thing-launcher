package com.nothinglauncher

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
 * Turns any app icon into a consistent circular launcher icon.
 *
 * MONO (the Nothing look):
 *  1. Android 13+ themed icon (monochrome layer) → light glyph on a dark disc.
 *  2. Adaptive icon with a sparse foreground → its foreground as a light grey glyph.
 *  3. Anything else → the whole icon in high-key greyscale.
 * COLOR: the original artwork, uniformly clipped to a circle.
 */
object IconFactory {

    private const val DISC_COLOR = 0xFF1C1C1F.toInt()
    private const val GLYPH_COLOR = 0xFFF2F2F0.toInt()
    private const val RIM_COLOR = 0xFF2C2C31.toInt()

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
        return renderInternal(resources, source, layers, style, sizePx)
    }

    /** Renders from explicit adaptive layers (used directly by previews/tests). */
    fun renderLayers(resources: Resources, layers: Layers, style: LauncherPrefs.IconStyle, sizePx: Int): Drawable =
        renderInternal(resources, layers.foreground ?: layers.background ?: ColorDrawable(0), layers, style, sizePx)

    private fun renderInternal(
        resources: Resources,
        source: Drawable,
        layers: Layers?,
        style: LauncherPrefs.IconStyle,
        sizePx: Int
    ): Drawable {
        val bitmap = try {
            when (style) {
                LauncherPrefs.IconStyle.MONO -> renderMono(source, layers, sizePx)
                LauncherPrefs.IconStyle.COLOR -> renderColor(source, layers, sizePx)
            }
        } catch (e: Exception) {
            // Some icons misbehave when drawn off-screen; never let that break the launcher.
            renderFallback(source, sizePx)
        }
        return BitmapDrawable(resources, bitmap)
    }

    private fun circlePath(size: Int) = Path().apply {
        addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
    }

    private fun drawDisc(canvas: Canvas, size: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DISC_COLOR }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size / 56f
            color = RIM_COLOR
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

    private fun renderMono(source: Drawable, layers: Layers?, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawDisc(canvas, size)
        canvas.save()
        canvas.clipPath(circlePath(size))

        val monochrome = layers?.monochrome
        val foreground = layers?.foreground
        when {
            monochrome != null -> {
                val tint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(GLYPH_COLOR, PorterDuff.Mode.SRC_IN)
                }
                drawLayer(canvas, monochrome, size, tint)
            }
            foreground != null && isSparse(foreground, size) -> {
                drawLayer(canvas, foreground, size, highKeyGreyPaint())
            }
            layers != null -> {
                val tmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val tc = Canvas(tmp)
                layers.background?.let { drawLayer(tc, it, size) }
                layers.foreground?.let { drawLayer(tc, it, size) }
                canvas.drawBitmap(tmp, 0f, 0f, greyPaint(contrast = 0.85f, lift = 10f))
                tmp.recycle()
            }
            else -> drawLegacy(canvas, source, size, greyPaint(contrast = 0.9f, lift = 20f))
        }
        canvas.restore()
        return bmp
    }

    private fun renderColor(source: Drawable, layers: Layers?, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (layers != null) {
            canvas.save()
            canvas.clipPath(circlePath(size))
            layers.background?.let { drawLayer(canvas, it, size) }
            layers.foreground?.let { drawLayer(canvas, it, size) }
            canvas.restore()
        } else {
            drawDisc(canvas, size)
            canvas.save()
            canvas.clipPath(circlePath(size))
            drawLegacy(canvas, source, size, null)
            canvas.restore()
        }
        return bmp
    }

    private fun renderFallback(source: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawDisc(canvas, size)
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

    /** Greyscale lifted towards white so glyph details stay visible on the dark disc. */
    private fun highKeyGreyPaint(): Paint {
        val m = ColorMatrix().apply { setSaturation(0f) }
        val lift = ColorMatrix(
            floatArrayOf(
                0.45f, 0f, 0f, 0f, 140f,
                0f, 0.45f, 0f, 0f, 140f,
                0f, 0f, 0.45f, 0f, 140f,
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
