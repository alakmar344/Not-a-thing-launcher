package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 60-dot bezel analog clock with dot-interpolated hour, minute, and accent second hands.
 * Lifecycle-gated via [onVisibilityAggregated] so it never ticks off-screen.
 */
class AnalogDotClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dot_on) }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dot_off) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.nothing_red) }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    fun applySurfaceColors(sc: AccentTheme.SurfaceColors) {
        onPaint.color = sc.onDot
        offPaint.color = sc.offDot
        invalidate()
    }

    private var running = false
    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            if (running) postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    private val cal = Calendar.getInstance()
    private val bezelX = FloatArray(12)
    private val bezelY = FloatArray(12)
    private var geomW = -1
    private var geomH = -1
    private var geomCx = 0f
    private var geomCy = 0f
    private var geomRadius = 0f

    private fun ensureGeometry() {
        if (geomW == width && geomH == height) return
        geomW = width
        geomH = height
        val availW = width - paddingLeft - paddingRight
        val availH = height - paddingTop - paddingBottom
        val size = min(availW, availH).toFloat()
        if (size <= 0f) {
            geomRadius = 0f
            return
        }
        val cx = paddingLeft + availW / 2f
        val cy = paddingTop + availH / 2f
        val radius = size / 2f - 4f * density
        geomCx = cx
        geomCy = cy
        geomRadius = radius
        for (i in 0 until 12) {
            bezelX[i] = cx + radius * COS_12[i]
            bezelY[i] = cy + radius * SIN_12[i]
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureGeometry()
        val radius = geomRadius
        if (radius <= 0f) return
        val cx = geomCx
        val cy = geomCy
        val majorR = 2.2f * density
        val minorR = 1.4f * density

        // 12-hour bezel markers
        for (i in 0 until 12) {
            val major = i % 3 == 0
            canvas.drawCircle(bezelX[i], bezelY[i], if (major) majorR else minorR, if (major) onPaint else offPaint)
        }

        cal.timeInMillis = System.currentTimeMillis()
        val sec = cal.get(Calendar.SECOND)
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(Calendar.HOUR) % 12) + min / 60f

        drawDotHand(canvas, cx, cy, radius, cos(hr * 30.0 * DEG_TO_RAD - HALF_PI).toFloat(), sin(hr * 30.0 * DEG_TO_RAD - HALF_PI).toFloat(), 0.54f, 4, 2.2f * density, onPaint)
        drawDotHand(canvas, cx, cy, radius, cos(min * 6.0 * DEG_TO_RAD - HALF_PI).toFloat(), sin(min * 6.0 * DEG_TO_RAD - HALF_PI).toFloat(), 0.78f, 6, 1.8f * density, onPaint)
        drawDotHand(canvas, cx, cy, radius, COS_60[sec], SIN_60[sec], 0.86f, 7, 1.25f * density, accentPaint)
        canvas.drawCircle(cx, cy, 3f * density, accentPaint)
    }

    private fun drawDotHand(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        cosA: Float, sinA: Float, lengthFraction: Float, dots: Int, dotRadiusPx: Float, paint: Paint
    ) {
        val step = radius * lengthFraction / dots
        var dist = step
        for (s in 1..dots) {
            canvas.drawCircle(cx + dist * cosA, cy + dist * sinA, dotRadiusPx, paint)
            dist += step
        }
    }

    private companion object {
        const val DEG_TO_RAD = Math.PI / 180.0
        const val HALF_PI = Math.PI / 2.0
        val COS_12 = FloatArray(12) { i -> cos(Math.toRadians(-90.0 + i * 30.0)).toFloat() }
        val SIN_12 = FloatArray(12) { i -> sin(Math.toRadians(-90.0 + i * 30.0)).toFloat() }
        val COS_60 = FloatArray(60) { i -> cos(Math.toRadians(-90.0 + i * 6.0)).toFloat() }
        val SIN_60 = FloatArray(60) { i -> sin(Math.toRadians(-90.0 + i * 6.0)).toFloat() }
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE && isShown
        if (shouldRun == running) return
        running = shouldRun
        removeCallbacks(ticker)
        if (running) post(ticker)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onDetachedFromWindow() { running = false; removeCallbacks(ticker); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateRunning() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); updateRunning() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateRunning() }
}
