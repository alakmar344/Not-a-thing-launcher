package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Environment
import android.os.StatFs
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.cos
import kotlin.math.sin

/**
 * A Nothing-style Internal Storage & RAM Tile.
 * Shows free storage capacity in DotFont with a circular dot capacity gauge.
 */
class StorageDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_on)
    }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_off)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nothing_red)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 10f * resources.displayMetrics.scaledDensity
        letterSpacing = 0.08f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (e: Exception) { null }
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 11f * resources.displayMetrics.scaledDensity
        letterSpacing = 0.10f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_bold) } catch (e: Exception) { null }
    }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    var storageText: String = "128 GB"
        set(value) { field = value; invalidate() }

    var storageSub: String = "FREE OF 256 GB · 50%"
        set(value) { field = value; invalidate() }

    private var usedPercent: Float = 0.50f

    init {
        refreshStorageInfo()
    }

    private fun refreshStorageInfo() {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val totalBytes = totalBlocks * blockSize
            val freeBytes = availableBlocks * blockSize
            val usedBytes = totalBytes - freeBytes

            val freeGb = (freeBytes / (1024L * 1024L * 1024L)).toInt()
            val totalGb = (totalBytes / (1024L * 1024L * 1024L)).toInt()
            if (totalGb > 0) {
                usedPercent = (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                val pct = (usedPercent * 100).toInt()
                storageText = "$freeGb GB"
                storageSub = "FREE OF $totalGb GB · $pct%"
            }
        } catch (_: Exception) {
            // Keep default
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Draw Title
        val title = context.getString(R.string.storage_label)
        canvas.drawText(title, 0f, 12f * density, titlePaint)

        // 2. Draw DotFont storage text
        val raster = DotFont.rasterize(storageText, 1)
        val dotPitch = 2.8f * density
        val dotRadius = dotPitch * 0.38f
        val startY = 22f * density
        val startX = 0f

        for (r in 0 until DotFont.ROWS) {
            for (c in 0 until raster.columns) {
                if (raster.lit[r][c]) {
                    val cx = startX + c * dotPitch + dotRadius
                    val cy = startY + r * dotPitch + dotRadius
                    canvas.drawCircle(cx, cy, dotRadius, onPaint)
                }
            }
        }

        // 3. Draw Subtitle
        canvas.drawText(storageSub, 0f, startY + DotFont.ROWS * dotPitch + 15f * density, textPaint)

        // 4. Circular dot capacity ring on the right
        val ringCenterX = w - 36f * density
        val ringCenterY = h / 2f + 4f * density
        val ringRadius = 24f * density
        val totalDots = 32
        val activeDots = (usedPercent * totalDots).toInt().coerceIn(1, totalDots)

        for (i in 0 until totalDots) {
            val angle = (i.toFloat() / totalDots) * (2 * Math.PI) - (Math.PI / 2)
            val cx = ringCenterX + ringRadius * cos(angle).toFloat()
            val cy = ringCenterY + ringRadius * sin(angle).toFloat()

            val paint = if (i < activeDots) {
                if (usedPercent > 0.85f) accentPaint else onPaint
            } else offPaint

            val r = if (i == 0 || i == totalDots / 2) 2.2f * density else 1.5f * density
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}
