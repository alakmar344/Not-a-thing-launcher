package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs

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

    /** Called after a tap with the new number of completed dots. */
    var onCountChanged: ((Int) -> Unit)? = null

    /** Long-press without moving: the host shows the element menu. */
    var onLongPress: (() -> Unit)? = null

    /** Long-press followed by a drag: the host starts a launcher drag. */
    var onDragStart: ((Float, Float) -> Unit)? = null

    private val totalHabits = 8
    private var completedCount: Int = 0
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var longPressed = false

    private val longPressRunnable = Runnable {
        if (!isAttachedToWindow) return@Runnable
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    var count: Int
        get() = completedCount
        set(value) {
            completedCount = value.coerceIn(0, totalHabits)
            contentDescription = progressText()
            invalidate()
        }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downRawX = event.rawX
                downRawY = event.rawY
                longPressed = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!longPressed) {
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                        removeCallbacks(longPressRunnable)
                    }
                } else if (abs(event.rawX - downRawX) > slop * 1.5f ||
                    abs(event.rawY - downRawY) > slop * 1.5f
                ) {
                    removeCallbacks(longPressRunnable)
                    longPressed = false
                    onDragStart?.invoke(event.rawX, event.rawY)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (longPressed) {
                    longPressed = false
                    onLongPress?.invoke()
                    return true
                }
                toggleAt(event.x)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                longPressed = false
            }
        }
        return true
    }

    private fun toggleAt(x: Float) {
        val w = width.toFloat()
        if (w <= 0f) return
        val slotWidth = w / totalHabits
        val tappedIdx = (x / slotWidth).toInt().coerceIn(0, totalHabits - 1)
        completedCount = if (tappedIdx < completedCount) tappedIdx else tappedIdx + 1
        contentDescription = progressText()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onCountChanged?.invoke(completedCount)
        invalidate()
        super.performClick()
    }

    private fun progressText(): String {
        val pct = completedCount * 100 / totalHabits
        return context.getString(R.string.habit_progress, completedCount, totalHabits, pct)
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

            ringPaint.color = if (isFilled) onPaint.color else offPaint.color
            canvas.drawCircle(cx, cy, outerRadius, ringPaint)

            if (isFilled) {
                val paint = if (i == totalHabits - 1) accentPaint else onPaint
                canvas.drawCircle(cx, cy, dotRadius, paint)
            }
        }

        canvas.drawText(progressText(), 0f, h - 2f * density, labelPaint)
    }
}
