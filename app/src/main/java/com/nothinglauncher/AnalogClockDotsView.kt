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
 * A Nothing OS-inspired Analog Dot Clock.
 * Features a 12-dot dial with red accent 12 o'clock, procedural dot hands, and center pivot.
 */
class AnalogClockDotsView @JvmOverloads constructor(
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

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE && isShown
        if (shouldRun == running) return
        running = shouldRun
        removeCallbacks(ticker)
        if (running) post(ticker)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateRunning()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Honour the layout padding (the tile's label sits in the bottom padding).
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = paddingLeft + w / 2f
        val cy = paddingTop + h / 2f
        val dotRadius = 3.5f * density
        val radius = min(w, h) / 2f - dotRadius * 1.25f
        if (radius <= 0f) return

        // 1. Draw 12 dial dots
        for (i in 0 until 12) {
            val angleDeg = i * 30.0 - 90.0
            val rad = Math.toRadians(angleDeg)
            val dx = cx + (radius * cos(rad)).toFloat()
            val dy = cy + (radius * sin(rad)).toFloat()

            val paint = if (i == 0) accentPaint else onPaint
            val r = if (i % 3 == 0) dotRadius * 1.25f else dotRadius
            canvas.drawCircle(dx, dy, r, paint)
        }

        // Current time
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR)
        val minute = now.get(Calendar.MINUTE)
        val second = now.get(Calendar.SECOND)

        // Hour hand (shorter, thicker)
        val hourAngle = Math.toRadians(((hour + minute / 60.0) * 30.0) - 90.0)
        val hourLength = radius * 0.52f
        val hourSteps = 4
        for (s in 1..hourSteps) {
            val dist = hourLength * (s.toFloat() / hourSteps)
            val hx = cx + (dist * cos(hourAngle)).toFloat()
            val hy = cy + (dist * sin(hourAngle)).toFloat()
            canvas.drawCircle(hx, hy, dotRadius * 1.1f, onPaint)
        }

        // Minute hand (longer)
        val minAngle = Math.toRadians(((minute + second / 60.0) * 6.0) - 90.0)
        val minLength = radius * 0.78f
        val minSteps = 6
        for (s in 1..minSteps) {
            val dist = minLength * (s.toFloat() / minSteps)
            val mx = cx + (dist * cos(minAngle)).toFloat()
            val my = cy + (dist * sin(minAngle)).toFloat()
            canvas.drawCircle(mx, my, dotRadius, onPaint)
        }

        // Center pivot
        canvas.drawCircle(cx, cy, dotRadius * 1.2f, accentPaint)
    }
}
