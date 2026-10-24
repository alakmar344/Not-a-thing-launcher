package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * An interactive Nothing OS Habit / Hydration Tracker.
 * Features 8 tap-to-toggle dot progress rings for daily goals.
 */
class HabitTrackerDotsView @JvmOverloads constructor(
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
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.dot_off)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nothing_red)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 10f * resources.displayMetrics.scaledDensity
        letterSpacing = 0.08f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (e: Exception) { null }
    }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    private val totalHabits = 8
    private var completedCount: Int = 4

    var count: Int
        get() = completedCount
        set(value) { completedCount = value.coerceIn(0, totalHabits); invalidate() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val w = width.toFloat()
            if (w <= 0f) return super.onTouchEvent(event)
            val slotWidth = w / totalHabits
            val tappedIdx = (event.x / slotWidth).toInt().coerceIn(0, totalHabits - 1)
            completedCount = if (tappedIdx < completedCount) tappedIdx else tappedIdx + 1
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            invalidate()
            return true
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val slotWidth = w / totalHabits
        val dotRadius = 4.5f * density
        val outerRadius = 8f * density
        val cy = 16f * density

        for (i in 0 until totalHabits) {
            val cx = slotWidth * i + slotWidth / 2f
            val isFilled = i < completedCount

            // Outer ring
            ringPaint.color = if (isFilled) onPaint.color else offPaint.color
            canvas.drawCircle(cx, cy, outerRadius, ringPaint)

            // Inner fill
            if (isFilled) {
                val paint = if (i == totalHabits - 1) accentPaint else onPaint
                canvas.drawCircle(cx, cy, dotRadius, paint)
            }
        }

        val progressText = String.format("%d OF %d COMPLETED · %d%%", completedCount, totalHabits, (completedCount * 100 / totalHabits))
        canvas.drawText(progressText, 0f, h - 2f * density, labelPaint)
    }
}
