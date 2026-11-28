package com.nothinglauncher

import android.content.ComponentCallbacks2
import android.content.Context
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
import com.nothinglauncher.perf.VisualTier

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

    private const val DOT_GRID = 13
    private const val SPARSE_PROBE = 48
    private const val MAX_SCRATCH_BMP_PX = 384

    private fun defaultCacheMaxBytes(): Int =
        (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private val iconCache = object : LruCache<String, Bitmap>(defaultCacheMaxBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    // Immutable shared ColorFilters eliminate per-icon native filter allocations.
    private val MONO_TINT_DARK = PorterDuffColorFilter(GLYPH_COLOR_DARK, PorterDuff.Mode.SRC_IN)
    private val MONO_TINT_LIGHT = PorterDuffColorFilter(GLYPH_COLOR_LIGHT, PorterDuff.Mode.SRC_IN)
    private val GREY_ADAPTIVE_DARK = buildGreyFilter(1.05f, 12f)
    private val GREY_ADAPTIVE_LIGHT = buildGreyFilter(1.05f, -14f)
    private val GREY_LEGACY_DARK = buildGreyFilter(1.05f, 18f)
    private val GREY_LEGACY_LIGHT = buildGreyFilter(1.05f, -15f)

    // Precomputed 13x13 circular dot mask (skip diagonal corners without per-pixel sqrt/mul).
    private val DOT_MASK_13 = BooleanArray(DOT_GRID * DOT_GRID).apply {
        val center = (DOT_GRID - 1) / 2f
        val maxDistSq = (DOT_GRID * 0.50f) * (DOT_GRID * 0.50f)
        for (row in 0 until DOT_GRID) {
            for (col in 0 until DOT_GRID) {
                val dx = col - center
                val dy = row - center
                this[row * DOT_GRID + col] = dx * dx + dy * dy <= maxDistSq
            }
        }
    }

    /** Per-thread reusable scratch buffers so icon rendering creates zero temp Bitmaps/Paints/Arrays. */
    private class ThreadScratch {
        val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
        val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val paintBitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val paintLegacy = Paint(Paint.FILTER_BITMAP_FLAG)
        val paintDotOn = Paint(Paint.ANTI_ALIAS_FLAG)
        val paintDotOff = Paint(Paint.ANTI_ALIAS_FLAG)
        val canvas = Canvas()

        val dotProbe: Bitmap = Bitmap.createBitmap(DOT_GRID, DOT_GRID, Bitmap.Config.ARGB_8888)
        val dotPixels = IntArray(DOT_GRID * DOT_GRID)
        val sparseProbe: Bitmap = Bitmap.createBitmap(SPARSE_PROBE, SPARSE_PROBE, Bitmap.Config.ARGB_8888)
        val sparsePixels = IntArray(SPARSE_PROBE * SPARSE_PROBE)

        var scratchBitmap: Bitmap? = null
        var scratchSize: Int = 0
        var lastCircleSize: Int = -1
        val circlePath = Path()

        fun acquireBitmap(size: Int): Bitmap {
            val existing = scratchBitmap
            if (existing != null && !existing.isRecycled && scratchSize == size) {
                existing.eraseColor(Color.TRANSPARENT)
                return existing
            }
            val created = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            if (size <= MAX_SCRATCH_BMP_PX) {
                scratchBitmap?.recycle()
                scratchBitmap = created
                scratchSize = size
            }
            return created
        }

        fun releaseTemp(bmp: Bitmap) {
            if (bmp !== scratchBitmap) {
                bmp.recycle()
            }
        }

        fun circlePath(size: Int): Path {
            if (lastCircleSize != size) {
                circlePath.reset()
                circlePath.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
                lastCircleSize = size
            }
            return circlePath
        }
    }

    private val scratchTL = object : ThreadLocal<ThreadScratch>() {
        override fun initialValue(): ThreadScratch = ThreadScratch()
    }

    fun configureForTier(context: Context) {
        val maxMem = Runtime.getRuntime().maxMemory()
        val divisor = if (VisualTier.current(context) == VisualTier.Tier.LITE) 20 else 12
        val target = (maxMem / divisor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(2 * 1024 * 1024)
        iconCache.resize(target)
    }

    fun trimMemory(level: Int) {
        @Suppress("DEPRECATION")
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> iconCache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> iconCache.trimToSize((iconCache.maxSize() / 2).coerceAtLeast(1))
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            val s = scratchTL.get()
            if (s != null) {
                s.scratchBitmap?.recycle()
                s.scratchBitmap = null
                s.scratchSize = 0
            }
        }
    }

    fun clearCache() {
        iconCache.evictAll()
    }

    class Layers(val background: Drawable?, val foreground: Drawable?, val monochrome: Drawable?)

    fun getCached(
        resources: Resources,
        style: NothingPrefs.IconStyle,
        sizePx: Int,
        cacheKey: String
    ): BitmapDrawable? {
        val isLight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
        val fullKey = "${cacheKey}_${style.name}_${sizePx}_$isLight"
        val cached = iconCache.get(fullKey) ?: return null
        return if (!cached.isRecycled) BitmapDrawable(resources, cached) else null
    }

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

    private fun drawDisc(canvas: Canvas, size: Int, isLight: Boolean, s: ThreadScratch) {
        val discColor = if (isLight) DISC_COLOR_LIGHT else DISC_COLOR_DARK
        val rimColor = if (isLight) RIM_COLOR_LIGHT else RIM_COLOR_DARK
        val half = size / 2f
        val fill = s.paintFill
        fill.color = discColor
        canvas.drawCircle(half, half, half, fill)
        val strokeW = size / 56f
        val rim = s.paintStroke
        rim.color = rimColor
        rim.strokeWidth = strokeW
        canvas.drawCircle(half, half, half - strokeW / 2f, rim)
    }

    private fun drawLayer(canvas: Canvas, layer: Drawable, size: Int, filterPaint: Paint? = null, s: ThreadScratch? = null) {
        val extra = size / 4
        val d = layer.mutate()
        d.setBounds(-extra, -extra, size + extra, size + extra)
        if (filterPaint != null) {
            val scratch = s ?: scratchTL.get()!!
            val tmp = scratch.acquireBitmap(size)
            scratch.canvas.setBitmap(tmp)
            d.draw(scratch.canvas)
            canvas.drawBitmap(tmp, 0f, 0f, filterPaint)
            scratch.releaseTemp(tmp)
        } else {
            d.draw(canvas)
        }
    }

    private fun renderMono(source: Drawable, layers: Layers?, size: Int, isLight: Boolean): Bitmap {
        val s = scratchTL.get()!!
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawDisc(canvas, size, isLight, s)
        canvas.save()
        canvas.clipPath(s.circlePath(size))

        val monochrome = layers?.monochrome
        val foreground = layers?.foreground
        when {
            monochrome != null -> {
                s.paintBitmap.colorFilter = if (isLight) MONO_TINT_LIGHT else MONO_TINT_DARK
                drawLayer(canvas, monochrome, size, s.paintBitmap, s)
                s.paintBitmap.colorFilter = null
            }
            foreground != null && isSparse(foreground, size, s) -> {
                s.paintBitmap.colorFilter = if (isLight) MONO_TINT_LIGHT else MONO_TINT_DARK
                drawLayer(canvas, foreground, size, s.paintBitmap, s)
                s.paintBitmap.colorFilter = null
            }
            layers != null -> {
                val tmp = s.acquireBitmap(size)
                s.canvas.setBitmap(tmp)
                layers.background?.let { drawLayer(s.canvas, it, size, null, s) }
                layers.foreground?.let { drawLayer(s.canvas, it, size, null, s) }
                s.paintBitmap.colorFilter = if (isLight) GREY_ADAPTIVE_LIGHT else GREY_ADAPTIVE_DARK
                canvas.drawBitmap(tmp, 0f, 0f, s.paintBitmap)
                s.paintBitmap.colorFilter = null
                s.releaseTemp(tmp)
            }
            else -> {
                s.paintBitmap.colorFilter = if (isLight) GREY_LEGACY_LIGHT else GREY_LEGACY_DARK
                drawLegacy(canvas, source, size, s.paintBitmap, s)
                s.paintBitmap.colorFilter = null
            }
        }
        canvas.restore()
        return bmp
    }

    /**
     * Procedural LED dot-matrix icon renderer: samples the icon's glyph or high-contrast luminance
     * silhouette onto a crisp 13x13 circular dot matrix inside the disc.
     */
    private fun renderDotGlyph(source: Drawable, layers: Layers?, size: Int, isLight: Boolean): Bitmap {
        val s = scratchTL.get()!!
        val grid = DOT_GRID
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawDisc(canvas, size, isLight, s)

        val probe = s.dotProbe
        probe.eraseColor(Color.TRANSPARENT)
        val pc = s.canvas
        pc.setBitmap(probe)
        val monochrome = layers?.monochrome
        val foreground = layers?.foreground
        var maskMode = false
        when {
            monochrome != null -> {
                maskMode = true
                drawLayer(pc, monochrome, grid, null, s)
            }
            foreground != null && isSparse(foreground, size, s) -> {
                maskMode = true
                drawLayer(pc, foreground, grid, null, s)
            }
            layers != null -> {
                layers.background?.let { drawLayer(pc, it, grid, null, s) }
                layers.foreground?.let { drawLayer(pc, it, grid, null, s) }
            }
            else -> drawLegacy(pc, source, grid, null, s)
        }

        val pixels = s.dotPixels
        probe.getPixels(pixels, 0, grid, 0, 0, grid, grid)

        // Compute average luminance for non-mask adaptive silhouette extraction.
        var lumSum = 0f
        var lumCount = 0
        if (!maskMode) {
            for (i in 0 until pixels.size) {
                val p = pixels[i]
                if (Color.alpha(p) > 64) {
                    val l = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
                    lumSum += l
                    lumCount++
                }
            }
        }
        val avgLum = if (lumCount > 0) lumSum / lumCount else 128f

        val pad = size * 0.18f
        val pitch = (size - 2f * pad) / grid
        val dotR = pitch * 0.36f
        val offR = dotR * 0.68f
        val onPaint = s.paintDotOn
        onPaint.color = if (isLight) GLYPH_COLOR_LIGHT else GLYPH_COLOR_DARK
        val offPaint = s.paintDotOff
        offPaint.color = if (isLight) RIM_COLOR_LIGHT else RIM_COLOR_DARK
        offPaint.alpha = 110

        var cy = pad + 0.5f * pitch
        for (row in 0 until grid) {
            val rowOff = row * grid
            var cx = pad + 0.5f * pitch
            for (col in 0 until grid) {
                val idx = rowOff + col
                if (DOT_MASK_13[idx]) {
                    val p = pixels[idx]
                    val a = Color.alpha(p)
                    val lit = if (maskMode) {
                        a > 0x48
                    } else {
                        val l = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
                        a > 0x60 && kotlin.math.abs(l - avgLum) > 22f && (if (avgLum > 128f) l < avgLum + 10f else l > avgLum - 10f)
                    }
                    canvas.drawCircle(cx, cy, if (lit) dotR else offR, if (lit) onPaint else offPaint)
                }
                cx += pitch
            }
            cy += pitch
        }
        return bmp
    }

    private fun renderColor(source: Drawable, layers: Layers?, size: Int, isLight: Boolean): Bitmap {
        val s = scratchTL.get()!!
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (layers != null) {
            canvas.save()
            canvas.clipPath(s.circlePath(size))
            layers.background?.let { drawLayer(canvas, it, size, null, s) }
            layers.foreground?.let { drawLayer(canvas, it, size, null, s) }
            canvas.restore()
        } else {
            drawDisc(canvas, size, isLight, s)
            canvas.save()
            canvas.clipPath(s.circlePath(size))
            drawLegacy(canvas, source, size, null, s)
            canvas.restore()
        }
        return bmp
    }

    private fun renderFallback(source: Drawable, size: Int, isLight: Boolean): Bitmap {
        val s = scratchTL.get()!!
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawDisc(canvas, size, isLight, s)
        try {
            drawLegacy(canvas, source, size, null, s)
        } catch (_: Exception) {
        }
        return bmp
    }

    private fun drawLegacy(canvas: Canvas, source: Drawable, size: Int, paint: Paint?, s: ThreadScratch) {
        val inner = (size * 0.70f).toInt().coerceAtLeast(1)
        val offset = (size - inner) / 2
        val tmp = s.acquireBitmap(size)
        val tc = Canvas(tmp)
        val d = source.mutate()
        d.setBounds(offset, offset, offset + inner, offset + inner)
        d.draw(tc)
        canvas.drawBitmap(tmp, 0f, 0f, paint ?: s.paintLegacy)
        s.releaseTemp(tmp)
    }

    private fun isSparse(layer: Drawable, size: Int, s: ThreadScratch): Boolean {
        val probe = SPARSE_PROBE
        val tmp = s.sparseProbe
        tmp.eraseColor(Color.TRANSPARENT)
        val tc = Canvas(tmp)
        val extra = probe / 4
        val d = layer.mutate()
        d.setBounds(-extra, -extra, probe + extra, probe + extra)
        d.draw(tc)
        val pixels = s.sparsePixels
        tmp.getPixels(pixels, 0, probe, 0, 0, probe, probe)
        var inside = 0
        var opaque = 0
        val c = probe / 2f
        val cSq = c * c
        for (y in 0 until probe) {
            val dy = y + 0.5f - c
            val dySq = dy * dy
            val rowOff = y * probe
            for (x in 0 until probe) {
                val dx = x + 0.5f - c
                if (dx * dx + dySq > cSq) continue
                inside++
                if ((pixels[rowOff + x] ushr 24) > 0x80) opaque++
            }
        }
        d.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
        return inside > 0 && opaque.toFloat() / inside < 0.55f
    }

    private fun buildGreyFilter(contrast: Float, lift: Float): ColorMatrixColorFilter {
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
        return ColorMatrixColorFilter(m)
    }
}
