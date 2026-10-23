package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlin.math.min

/**
 * A row of 60 dots: elapsed seconds glow softly, the current second is the accent colour,
 * and every fifth dot is a little brighter so the row reads like a ruler.
 */
class SecondsTickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val pastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }
    private val futurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_off)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.hairline_strong)
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nothing_red)
    }

    var accentColor: Int
        get() = nowPaint.color
        set(value) { nowPaint.color = value; invalidate() }

    var previewSecond: Int? = null
        set(value) { field = value; invalidate() }

    private var second = Calendar.getInstance().get(Calendar.SECOND)
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            val s = Calendar.getInstance().get(Calendar.SECOND)
            if (s != second) { second = s; invalidate() }
            if (running) postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (8 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = 60
        val pitch = width / count.toFloat()
        val r = min(pitch * 0.3f, height / 2f)
        val cy = height / 2f
        val current = previewSecond ?: second
        for (i in 0 until count) {
            val cx = (i + 0.5f) * pitch
            val paint = when {
                i == current -> nowPaint
                i < current -> pastPaint
                i % 5 == 0 -> markPaint
                else -> futurePaint
            }
            canvas.drawCircle(cx, cy, if (i == current) r * 1.25f else r, paint)
        }
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE && previewSecond == null
        if (shouldRun == running) return
        running = shouldRun
        removeCallbacks(ticker)
        if (running) post(ticker)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); running = false; removeCallbacks(ticker) }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateRunning() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); updateRunning() }
}
