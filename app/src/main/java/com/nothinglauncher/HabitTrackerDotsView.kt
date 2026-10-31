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
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
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
    /** Set once the finger moved past slop or a drag was handed off; the UP must not toggle. */
    private var gestureConsumed = false

    private val longPressRunnable = Runnable {
        if (!isAttachedToWindow) return@Runnable
        longPressed = true
        longPressRawX = lastRawX
        longPressRawY = lastRawY
        // Stock behaviour: the menu appears while the finger is still down, and the parents
        // (page scroller / pager) must not steal the gesture so a drag can follow.
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }
    private var longPressRawX = 0f
    private var longPressRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    /** Cached so onDraw does not format a string resource every frame. */
    private var progressLabel: String = ""

    init {
        progressLabel = progressText()
        contentDescription = progressLabel
        // Lets TalkBack / switch access "click" the tracker: each click fills the next dot.
        isClickable = true
    }

    var count: Int
        get() = completedCount
        set(value) {
            completedCount = value.coerceIn(0, totalHabits)
            progressLabel = progressText()
            contentDescription = progressLabel
            invalidate()
        }

    /** Accessibility click (touch taps go through [onTouchEvent]/[toggleAt] instead). */
    override fun performClick(): Boolean {
        super.performClick()
        setCountFromUser(if (completedCount >= totalHabits) 0 else completedCount + 1)
        return true
    }

    private fun setCountFromUser(newCount: Int) {
        completedCount = newCount.coerceIn(0, totalHabits)
        progressLabel = progressText()
        contentDescription = progressLabel
        onCountChanged?.invoke(completedCount)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                longPressed = false
                gestureConsumed = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                if (gestureConsumed) return true
                if (!longPressed) {
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                        removeCallbacks(longPressRunnable)
                        gestureConsumed = true
                    }
                } else if (abs(event.rawX - longPressRawX) > slop ||
                    abs(event.rawY - longPressRawY) > slop
                ) {
                    removeCallbacks(longPressRunnable)
                    longPressed = false
                    gestureConsumed = true
                    onDragStart?.invoke(event.rawX, event.rawY)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (longPressed) {
                    // The menu was already shown when the long-press fired.
                    longPressed = false
                    return true
                }
                if (!gestureConsumed) toggleAt(event.x)
                gestureConsumed = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                longPressed = false
                gestureConsumed = false
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        longPressed = false
        gestureConsumed = false
        super.onDetachedFromWindow()
    }

    private fun toggleAt(x: Float) {
        val w = width.toFloat()
        if (w <= 0f) return
        val slotWidth = w / totalHabits
        val tappedIdx = (x / slotWidth).toInt().coerceIn(0, totalHabits - 1)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        setCountFromUser(if (tappedIdx < completedCount) tappedIdx else tappedIdx + 1)
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

        canvas.drawText(progressLabel, 0f, h - 2f * density, labelPaint)
    }
}
