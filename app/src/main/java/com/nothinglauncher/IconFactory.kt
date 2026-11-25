package com.nothinglauncher

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.collection.LruCache

/**
 * Turns any app icon into a consistent circular launcher icon in the Nothing OS design language.
 *
 * Styles:
 * - **MONO:** Android 13+ monochrome layer or sparse adaptive foreground as crisp glyph in disc;
 *   colorful non-sparse icons extract high-contrast monochrome silhouettes rather than muddy grey.
 * - **DOTS:** Hardware LED dot-matrix glyph — samples the app's monochrome/silhouette mask onto
 *   a 13x13 circular dot grid inside the disc!
 * - **COLOR:** Original artwork uniformly clipped to a circle.
 */
object IconFactory {

    private const val DISC_COLOR_DARK = 0xFF1C1C1F.toInt()
    private const val GLYPH_COLOR_DARK = 0xFFF2F2F0.toInt()
    private const val RIM_COLOR_DARK = 0xFF2C2C31.toInt()

    private const val DISC_COLOR_LIGHT = 0xFFECECE8.toInt()
    private const val GLYPH_COLOR_LIGHT = 0xFF0E0E10.toInt()
    private const val RIM_COLOR_LIGHT = 0xFFD8D8D4.toInt()

    private val iconCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun clearCache() {
        iconCache.evictAll()
    }

    class Layers(val background: Drawable?, val foreground: Drawable?, val monochrome: Drawable?)

    fun render(
        resources: Resources,
        source: Drawable,
        style: NothingPrefs.IconStyle,
        sizePx: Int,
        cacheKey: String? = null
    ): Drawable {
        val isLight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
        val fullKey = if (cacheKey != null) "${cacheKey}_${style.name}_${sizePx}_$isLight" else null
        if (fullKey != null) {
            val cached = iconCache.get(fullKey)
            if (cached != null && !cached.isRecycled) {
                return BitmapDrawable(resources, cached)
            }
        }
        val layers = if (source is AdaptiveIconDrawable) {
            Layers(
                source.background,
                source.foreground,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) source.monochrome else null
            )
        } else null
        val drawable = renderInternal(resources, source, layers, style, sizePx, isLight)
        if (fullKey != null && drawable is BitmapDrawable && drawable.bitmap != null) {
            iconCache.put(fullKey, drawable.bitmap)
        }
        return drawable
    }

    fun renderLayers(resources: Resources, layers: Layers, style: NothingPrefs.IconStyle, sizePx: Int): Drawable {
        val isLight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
        return renderInternal(resources, layers.foreground ?: layers.background ?: ColorDrawable(0), layers, style, sizePx, isLight)
    }

    private fun renderInternal(
        resources: Resources,
        source: Drawable,
        layers: Layers?,
        style: NothingPrefs.IconStyle,
        sizePx: Int,
        isLight: Boolean
    ): Drawable {
        @Suppress("NAME_SHADOWING")
        val sizePx = sizePx.coerceAtLeast(1)
        val bitmap = try {
            when (style) {
                NothingPrefs.IconStyle.MONO -> renderMono(source, layers, sizePx, isLight)
                NothingPrefs.IconStyle.DOTS -> renderDotGlyph(source, layers, sizePx, isLight)
                NothingPrefs.IconStyle.COLOR -> renderColor(source, layers, sizePx, isLight)
            }
        } catch (_: Exception) {
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

    private fun drawLayer(canvas: Canvas, layer: Drawable, size: Int, filterPaint: Paint? = null) {
        val extra = size / 4
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
                val tint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(glyphColor, PorterDuff.Mode.SRC_IN)
                }
                drawLayer(canvas, foreground, size, tint)
            }
            layers != null -> {
                val tmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val tc = Canvas(tmp)
                layers.background?.let { drawLayer(tc, it, size) }
                layers.foreground?.let { drawLayer(tc, it, size) }
                canvas.drawBitmap(tmp, 0f, 0f, greyPaint(contrast = 1.05f, lift = if (isLight) -14f else 12f))
                tmp.recycle()
            }
            else -> drawLegacy(canvas, source, size, greyPaint(contrast = 1.05f, lift = if (isLight) -15f else 18f))
        }
        canvas.restore()
        return bmp
    }

    /**
     * Procedural LED dot-matrix icon renderer: samples the icon's glyph or high-contrast luminance
     * silhouette onto a crisp 13x13 circular dot matrix inside the disc.
     */
    private fun renderDotGlyph(source: Drawable, layers: Layers?, size: Int, isLight: Boolean): Bitmap {
        val grid = 13
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawDisc(canvas, size, isLight)

        val probe = Bitmap.createBitmap(grid, grid, Bitmap.Config.ARGB_8888)
        val pc = Canvas(probe)
        val monochrome = layers?.monochrome
        val foreground = layers?.foreground
        var maskMode = false
        when {
            monochrome != null -> {
                maskMode = true
                drawLayer(pc, monochrome, grid)
            }
            foreground != null && isSparse(foreground, size) -> {
                maskMode = true
                drawLayer(pc, foreground, grid)
            }
            layers != null -> {
                layers.background?.let { drawLayer(pc, it, grid) }
                layers.foreground?.let { drawLayer(pc, it, grid) }
            }
            else -> drawLegacy(pc, source, grid, null)
        }

        val pixels = IntArray(grid * grid)
        probe.getPixels(pixels, 0, grid, 0, 0, grid, grid)
        probe.recycle()

        // Compute average luminance for non-mask adaptive silhouette extraction.
        var lumSum = 0f
        var lumCount = 0
        if (!maskMode) {
            for (p in pixels) {
                if (Color.alpha(p) > 64) {
                    val l = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p))
                    lumSum += l
                    lumCount++
                }
            }
        }
        val avgLum = if (lumCount > 0) lumSum / lumCount else 128f

        val pad = size * 0.18f
        val pitch = (size - 2f * pad) / grid
        val dotR = pitch * 0.36f
        val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLight) GLYPH_COLOR_LIGHT else GLYPH_COLOR_DARK
        }
        val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLight) RIM_COLOR_LIGHT else RIM_COLOR_DARK
            alpha = 110
        }
        val center = (grid - 1) / 2f
        for (row in 0 until grid) {
            for (col in 0 until grid) {
                val dx = col - center
                val dy = row - center
                if (dx * dx + dy * dy > (grid * 0.50f) * (grid * 0.50f)) continue
                val p = pixels[row * grid + col]
                val a = Color.alpha(p)
                val lit = if (maskMode) {
                    a > 0x48
                } else {
                    val l = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
                    a > 0x60 && kotlin.math.abs(l - avgLum) > 22f && (if (avgLum > 128f) l < avgLum + 10f else l > avgLum - 10f)
                }
                val cx = pad + (col + 0.5f) * pitch
                val cy = pad + (row + 0.5f) * pitch
                canvas.drawCircle(cx, cy, if (lit) dotR else dotR * 0.68f, if (lit) onPaint else offPaint)
            }
        }
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

    private fun drawLegacy(canvas: Canvas, source: Drawable, size: Int, paint: Paint?) {
        val inner = (size * 0.70f).toInt().coerceAtLeast(1)
        val offset = (size - inner) / 2
        val tmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val d = source.mutate()
        d.setBounds(offset, offset, offset + inner, offset + inner)
        d.draw(Canvas(tmp))
        canvas.drawBitmap(tmp, 0f, 0f, paint ?: Paint(Paint.FILTER_BITMAP_FLAG))
        tmp.recycle()
    }

    private fun isSparse(layer: Drawable, size: Int): Boolean {
        val probe = 48
        val tmp = Bitmap.createBitmap(probe, probe, Bitmap.Config.ARGB_8888)
        val extra = probe / 4
        val d = layer.mutate()
        d.setBounds(-extra, -extra, probe + extra, probe + extra)
        d.draw(Canvas(tmp))
        val pixels = IntArray(probe * probe)
        tmp.getPixels(pixels, 0, probe, 0, 0, probe, probe)
        var inside = 0
        var opaque = 0
        val c = probe / 2f
        for (y in 0 until probe) for (x in 0 until probe) {
            val dx = x + 0.5f - c
            val dy = y + 0.5f - c
            if (dx * dx + dy * dy > c * c) continue
            inside++
            if ((pixels[y * probe + x] ushr 24) > 0x80) opaque++
        }
        tmp.recycle()
        d.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
        return inside > 0 && opaque.toFloat() / inside < 0.55f
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
