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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (size <= 0f) return
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val radius = size / 2f - 4f * density

        // 12-hour bezel markers
        for (i in 0 until 12) {
            val a = Math.toRadians(-90.0 + i * 30.0)
            val mx = cx + (radius * cos(a)).toFloat()
            val my = cy + (radius * sin(a)).toFloat()
            val major = i % 3 == 0
            canvas.drawCircle(mx, my, (if (major) 2.2f else 1.4f) * density, if (major) onPaint else offPaint)
        }

        val cal = Calendar.getInstance()
        val sec = cal.get(Calendar.SECOND)
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(Calendar.HOUR) % 12) + min / 60f

        fun drawDotHand(angleDeg: Double, lengthFraction: Float, dots: Int, dotRadiusDp: Float, paint: Paint) {
            val rad = Math.toRadians(angleDeg - 90.0)
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            for (s in 1..dots) {
                val dist = radius * lengthFraction * (s / dots.toFloat())
                canvas.drawCircle(cx + dist * cosA, cy + dist * sinA, dotRadiusDp * density, paint)
            }
        }

        drawDotHand(hr * 30.0, 0.54f, 4, 2.2f, onPaint)
        drawDotHand(min * 6.0, 0.78f, 6, 1.8f, onPaint)
        drawDotHand(sec * 6.0, 0.86f, 7, 1.25f, accentPaint)
        canvas.drawCircle(cx, cy, 3f * density, accentPaint)
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
