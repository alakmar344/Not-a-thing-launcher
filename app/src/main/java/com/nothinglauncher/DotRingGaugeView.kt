package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
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
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.10f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (_: Exception) { null }
    }

    var progress01: Float = 0f
        set(value) { val v = value.coerceIn(0f, 1f); if (v != field) { field = v; invalidate() } }

    var centerText: String = "00"
        set(value) { if (value != field) { field = value; invalidate() } }

    var subtitle: String = ""
        set(value) { if (value != field) { field = value; drawnSubSource = null; invalidate() } }

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

    private val ringX = FloatArray(DOT_COUNT)
    private val ringY = FloatArray(DOT_COUNT)
    private var geomW = -1
    private var geomH = -1
    private var geomCx = 0f
    private var geomCy = 0f
    private var geomDotR = 0f
    private var geomRingR = 0f
    private var drawnSubSource: String? = null
    private var drawnSubMaxW = -1f
    private var drawnSubtitle = ""

    private fun ensureGeometry() {
        if (geomW == width && geomH == height) return
        geomW = width
        geomH = height
        val availW = width - paddingLeft - paddingRight
        val availH = height - paddingTop - paddingBottom
        val size = min(availW, availH).toFloat()
        if (size <= 0f) {
            geomRingR = 0f
            return
        }
        val cx = paddingLeft + availW / 2f
        val cy = paddingTop + availH / 2f
        val dotR = size * 0.025f
        val ringR = size / 2f - dotR * 1.8f
        geomCx = cx
        geomCy = cy
        geomDotR = dotR
        geomRingR = ringR
        for (i in 0 until DOT_COUNT) {
            ringX[i] = cx + ringR * COS_40[i]
            ringY[i] = cy + ringR * SIN_40[i]
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureGeometry()
        val ringR = geomRingR
        if (ringR <= 0f) return
        val cx = geomCx
        val cy = geomCy
        val dotR = geomDotR
        val heading = compassDegrees

        if (heading != null) {
            val northIdx = (((-heading % 360f + 360f) % 360f) / 360f * dotCount).roundToInt() % dotCount
            val northR = dotR * 1.5f
            val otherR = dotR * 0.72f
            for (i in 0 until dotCount) {
                val isNorth = i == northIdx
                val isCardinal = i % 10 == 0
                val p = when {
                    isNorth -> accentPaint
                    isCardinal -> onPaint
                    else -> offPaint
                }
                canvas.drawCircle(ringX[i], ringY[i], if (isNorth) northR else if (isCardinal) dotR else otherR, p)
            }
        } else {
            val litCount = (progress01 * dotCount).roundToInt()
            val activePaint = if (highlightActive) accentPaint else onPaint
            val leadR = dotR * 1.3f
            val offR = dotR * 0.78f
            for (i in 0 until dotCount) {
                val lit = i < litCount
                val isLead = highlightActive && litCount > 0 && i == litCount - 1
                canvas.drawCircle(
                    ringX[i], ringY[i],
                    if (isLead) leadR else if (lit) dotR else offR,
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
        var ry = y0 + 0.5f * pitch
        for (row in 0 until DotFont.ROWS) {
            val rowLit = raster.lit[row]
            var rx = x0 + 0.5f * pitch
            for (col in 0 until raster.columns) {
                if (rowLit[col]) {
                    canvas.drawCircle(rx, ry, r, onPaint)
                }
                rx += pitch
            }
            ry += pitch
        }
        if (subtitle.isNotBlank()) {
            val maxSubW = (ringR * 1.48f).coerceAtLeast(1f)
            if (drawnSubSource != subtitle || drawnSubMaxW != maxSubW) {
                val fit = labelPaint.breakText(subtitle, true, maxSubW, null)
                drawnSubtitle = if (fit < subtitle.length && fit > 1) subtitle.substring(0, fit - 1) + "\u2026" else subtitle
                drawnSubSource = subtitle
                drawnSubMaxW = maxSubW
            }
            canvas.drawText(drawnSubtitle, cx, y0 + textH + 14f * density, labelPaint)
        }
    }

    private companion object {
        const val DOT_COUNT = 40
        val COS_40 = FloatArray(DOT_COUNT) { i -> cos(Math.toRadians(-90.0 + i * 360.0 / DOT_COUNT)).toFloat() }
        val SIN_40 = FloatArray(DOT_COUNT) { i -> sin(Math.toRadians(-90.0 + i * 360.0 / DOT_COUNT)).toFloat() }
    }
}
