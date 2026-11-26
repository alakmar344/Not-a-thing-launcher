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
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Reusable 40-dot circular gauge used by Timer, Vitals, and Compass/Steps widgets.
 * Supports progress arcs, dot-matrix center readout, subtitle label, and optional azimuth heading dot.
 */
class DotRingGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val dotCount = 40

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dot_on) }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dot_off) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.nothing_red) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 9f * density * resources.configuration.fontScale
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.10f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (_: Exception) { null }
    }

    var progress01: Float = 0f
        set(value) { val v = value.coerceIn(0f, 1f); if (v != field) { field = v; invalidate() } }

    var centerText: String = "00"
        set(value) { if (value != field) { field = value; invalidate() } }

    var subtitle: String = ""
        set(value) { if (value != field) { field = value; invalidate() } }

    /** Non-null azimuth angle (0..360 deg, 0=North) switches the ring into a compass dial. */
    var compassDegrees: Float? = null
        set(value) { if (value != field) { field = value; invalidate() } }

    var highlightActive: Boolean = false
        set(value) { if (value != field) { field = value; invalidate() } }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    fun applySurfaceColors(sc: AccentTheme.SurfaceColors) {
        onPaint.color = sc.onDot
        offPaint.color = sc.offDot
        labelPaint.color = sc.textSecondary
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (size <= 0f) return
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val dotR = size * 0.025f
        val ringR = size / 2f - dotR * 1.8f
        val heading = compassDegrees

        if (heading != null) {
            val northIdx = (((-heading % 360f + 360f) % 360f) / 360f * dotCount).roundToInt() % dotCount
            for (i in 0 until dotCount) {
                val angle = Math.toRadians(-90.0 + i * 360.0 / dotCount)
                val x = cx + (ringR * cos(angle)).toFloat()
                val y = cy + (ringR * sin(angle)).toFloat()
                val isNorth = i == northIdx
                val isCardinal = i % 10 == 0
                val p = when {
                    isNorth -> accentPaint
                    isCardinal -> onPaint
                    else -> offPaint
                }
                canvas.drawCircle(x, y, if (isNorth) dotR * 1.5f else if (isCardinal) dotR else dotR * 0.72f, p)
            }
        } else {
            val litCount = (progress01 * dotCount).roundToInt()
            val activePaint = if (highlightActive) accentPaint else onPaint
            for (i in 0 until dotCount) {
                val angle = Math.toRadians(-90.0 + i * 360.0 / dotCount)
                val x = cx + (ringR * cos(angle)).toFloat()
                val y = cy + (ringR * sin(angle)).toFloat()
                val lit = i < litCount
                val isLead = highlightActive && i == (litCount - 1).coerceAtLeast(0)
                canvas.drawCircle(
                    x, y,
                    if (isLead) dotR * 1.3f else if (lit) dotR else dotR * 0.78f,
                    if (isLead) accentPaint else if (lit) activePaint else offPaint
                )
            }
        }

        val raster = DotFont.rasterizeCached(centerText)
        val pitch = min(ringR * 0.14f, (ringR * 1.25f) / raster.columns.coerceAtLeast(1))
        val r = pitch * 0.37f
        val textW = raster.columns * pitch
        val textH = DotFont.ROWS * pitch
        val x0 = cx - textW / 2f
        val y0 = cy - textH / 2f - (if (subtitle.isNotBlank()) 6f * density else 0f)
        for (row in 0 until DotFont.ROWS) {
            for (col in 0 until raster.columns) {
                if (!raster.lit[row][col]) continue
                canvas.drawCircle(x0 + (col + 0.5f) * pitch, y0 + (row + 0.5f) * pitch, r, onPaint)
            }
        }
        if (subtitle.isNotBlank()) {
            val maxSubW = (ringR * 1.48f).coerceAtLeast(1f)
            val fit = labelPaint.breakText(subtitle, true, maxSubW, null)
            val drawn = if (fit < subtitle.length && fit > 1) subtitle.substring(0, fit - 1) + "\u2026" else subtitle
            canvas.drawText(drawn, cx, y0 + textH + 14f * density, labelPaint)
        }
    }
}
