package com.nothinglauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * A Nothing-style minimalist dot page indicator for the multi-page workspace.
 * Active page is an elongated pill in the accent/white color; inactive pages are quiet dots.
 */
class PageIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
    }
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_off)
    }

    var accentColor: Int = ContextCompat.getColor(context, R.color.nothing_red)
        set(value) { field = value; invalidate() }

    var pageCount: Int = 1
        set(value) {
            val v = value.coerceAtLeast(1)
            if (field != v) {
                field = v
                currentPage = currentPage.coerceIn(0, field - 1)
                requestLayout()
                invalidate()
            }
        }

    var currentPage: Int = 0
        set(value) {
            val v = value.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            if (field != v) {
                animateToPage(v)
                field = v
            }
        }

    var onPageSelected: ((Int) -> Unit)? = null

    private var animatedPage = 0f
    private var animator: ValueAnimator? = null
    private val rect = RectF()

    private fun animateToPage(target: Int) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animatedPage, target.toFloat()).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animatedPage = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setPageImmediately(page: Int) {
        animator?.cancel()
        currentPage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        animatedPage = currentPage.toFloat()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dotDiameter = 6f * density
        val spacing = 8f * density
        val activeWidth = 16f * density
        val totalWidth = if (pageCount <= 1) 0 else ((pageCount - 1) * (dotDiameter + spacing) + activeWidth).toInt()
        val totalHeight = (18 * density).toInt()
        setMeasuredDimension(
            resolveSize(totalWidth + (16 * density).toInt(), widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageCount <= 1) return

        val dotR = 3f * density
        val spacing = 8f * density
        val activeW = 16f * density
        val totalW = (pageCount - 1) * (dotR * 2 + spacing) + activeW
        var startX = (width - totalW) / 2f
        val cy = height / 2f

        for (i in 0 until pageCount) {
            val dist = abs(i - animatedPage).coerceIn(0f, 1f)
            val w = activeW * (1f - dist) + (dotR * 2) * dist
            val h = dotR * 2
            val x = startX
            rect.set(x, cy - dotR, x + w, cy + dotR)
            if (dist < 0.5f) {
                activePaint.color = ContextCompat.getColor(context, R.color.text_primary)
                canvas.drawRoundRect(rect, dotR, dotR, activePaint)
            } else {
                canvas.drawRoundRect(rect, dotR, dotR, inactivePaint)
            }
            startX += w + spacing
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageCount <= 1) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val segmentW = width / pageCount.toFloat()
            val clicked = (event.x / segmentW).toInt().coerceIn(0, pageCount - 1)
            if (clicked != currentPage) {
                currentPage = clicked
                onPageSelected?.invoke(clicked)
                return true
            }
        }
        return true
    }
}
