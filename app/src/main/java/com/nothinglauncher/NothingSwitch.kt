package com.nothinglauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.Switch
import androidx.core.content.ContextCompat

/**
 * A minimal toggle: an outlined pill with a grey dot when off, a solid white pill with a
 * black dot when on. Animated and accessible (announced as a switch).
 */
class NothingSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val trackOff = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.hairline_strong)
    }
    private val trackOn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nothing_white)
    }
    private val thumbOff = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }
    private val thumbOn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nothing_black)
    }
    private val rect = RectF()

    private var progress = 0f
    private var animator: ValueAnimator? = null

    var onCheckedChange: ((Boolean) -> Unit)? = null

    var isChecked: Boolean = false
        private set

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { setChecked(!isChecked, animate = true, notify = true) }
    }

    fun setChecked(checked: Boolean, animate: Boolean = false, notify: Boolean = false) {
        if (checked == isChecked && animator == null) {
            progress = if (checked) 1f else 0f
            invalidate()
            return
        }
        isChecked = checked
        animator?.cancel()
        val target = if (checked) 1f else 0f
        if (animate && isAttachedToWindow) {
            animator = ValueAnimator.ofFloat(progress, target).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (animator === animation) animator = null
                    }
                })
                start()
            }
        } else {
            animator = null
            progress = target
            invalidate()
        }
        if (notify) onCheckedChange?.invoke(checked)
    }

    override fun onDetachedFromWindow() {
        // Finish instead of leaking a running animator that references this view.
        animator?.cancel()
        animator = null
        progress = if (isChecked) 1f else 0f
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((46 * density).toInt(), widthMeasureSpec),
            resolveSize((26 * density).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = trackOff.strokeWidth
        rect.set(inset, inset, width - inset, height - inset)
        val r = rect.height() / 2f
        if (progress < 1f) canvas.drawRoundRect(rect, r, r, trackOff)
        if (progress > 0f) {
            trackOn.alpha = (255 * progress).toInt()
            canvas.drawRoundRect(rect, r, r, trackOn)
        }
        val thumbR = r * 0.46f
        val startX = rect.left + r
        val endX = rect.right - r
        val cx = startX + (endX - startX) * progress
        val paint = if (progress > 0.5f) thumbOn else thumbOff
        canvas.drawCircle(cx, rect.centerY(), thumbR, paint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Switch::class.java.name
        info.isCheckable = true
        info.isChecked = isChecked
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = Switch::class.java.name
        event.isChecked = isChecked
    }
}
