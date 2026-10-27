package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.min

/**
 * Vertical A–Z fast scroller for the drawer. Drag along it to jump between sections;
 * the active letter turns white with an accent dot beside it.
 */
class AlphabetScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var letters: List<Char> = emptyList()
        set(value) { field = value; activeIndex = -1; invalidate() }

    /** Called with the letter under the finger and its Y centre (in this view's coordinates). */
    var onLetterChanged: ((letter: Char, centerY: Float) -> Unit)? = null
    var onTouchingChanged: ((touching: Boolean) -> Unit)? = null

    var accentColor: Int
        get() = dotPaint.color
        set(value) { dotPaint.color = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_bold) } catch (e: Exception) { null }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_tertiary)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        typeface = this@AlphabetScrollerView.typeface
    }
    private val activePaint = Paint(idlePaint).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nothing_red)
    }

    private var activeIndex = -1
    private val exclusionRect = Rect()

    private fun slotHeight(): Float =
        if (letters.isEmpty()) 0f else min(height / letters.size.toFloat(), 18f * density)

    private fun topOffset(): Float = (height - slotHeight() * letters.size) / 2f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty()) return
        val slot = slotHeight()
        val top = topOffset()
        val cx = width / 2f
        letters.forEachIndexed { i, letter ->
            val cy = top + slot * (i + 0.5f)
            val active = i == activeIndex
            val paint = if (active) activePaint else idlePaint
            val baseline = cy - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter.toString(), cx, baseline, paint)
            if (active) canvas.drawCircle(cx - 11f * density, cy, 2.2f * density, dotPaint)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Keep the system back gesture from swallowing drags on the scroller.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exclusionRect.set(0, 0, width, height)
            systemGestureExclusionRects = listOf(exclusionRect)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onTouchingChanged?.invoke(true)
                }
                val slot = slotHeight()
                val idx = ((event.y - topOffset()) / slot).toInt().coerceIn(0, letters.size - 1)
                if (idx != activeIndex) {
                    activeIndex = idx
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onLetterChanged?.invoke(letters[idx], topOffset() + slot * (idx + 0.5f))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeIndex = -1
                onTouchingChanged?.invoke(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
