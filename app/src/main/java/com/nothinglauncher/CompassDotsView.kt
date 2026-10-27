package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Compass Tile: Dot-matrix compass rose with an accent North marker,
 * center heading in DotFont, and cardinal direction label.
 */
class CompassDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val dotCount = 32

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dot_on) }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dot_off) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.nothing_red) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 9.5f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (e: Exception) { null }
    }

    var headingDegrees: Int = 0
        set(value) { field = value.mod(360); invalidate() } // mod: never negative

    var directionText: String = "N"
        set(value) { field = value; invalidate() }

    var statusText: String? = null
        set(value) { field = value; contentDescription = value; invalidate() }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (size <= 0f) return

        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val dotR = size * 0.026f
        val ringR = size / 2f - dotR * 2.2f

        // 1. Compass dial ring
        for (i in 0 until dotCount) {
            val angle = Math.toRadians(-90.0 + i * 360.0 / dotCount)
            val x = cx + (ringR * cos(angle)).toFloat()
            val y = cy + (ringR * sin(angle)).toFloat()
            val isCardinal = i % (dotCount / 4) == 0
            val isNorth = i == 0
            val paint = when {
                isNorth -> accentPaint
                isCardinal -> onPaint
                else -> offPaint
            }
            canvas.drawCircle(x, y, if (isNorth || isCardinal) dotR * 1.25f else dotR * 0.8f, paint)
        }

        // 2. Heading degrees in DotFont
        val text = String.format(java.util.Locale.US, "%03d°", headingDegrees)
        val raster = DotFont.rasterize(text)
        val pitch = min(ringR * 0.22f, (ringR * 1.2f) / raster.columns.coerceAtLeast(1))
        val r = pitch * 0.38f
        val textW = raster.columns * pitch
        val textH = DotFont.ROWS * pitch
        val x0 = cx - textW / 2f
        val y0 = cy - textH / 2f - 4f * density

        for (row in 0 until DotFont.ROWS) {
            for (col in 0 until raster.columns) {
                if (raster.lit[row][col]) {
                    canvas.drawCircle(x0 + (col + 0.5f) * pitch, y0 + (row + 0.5f) * pitch, r, onPaint)
                }
            }
        }

        // 3. Direction label below
        canvas.drawText(directionText, cx, cy + textH / 2f + 14f * density, labelPaint)
        statusText?.takeIf { it.isNotBlank() }?.let { text ->
            canvas.drawText(text, cx, height - 6f * density, labelPaint)
        }
    }
}
