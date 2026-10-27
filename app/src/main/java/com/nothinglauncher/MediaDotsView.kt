package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.random.Random

/**
 * A Nothing OS-inspired Dot-Matrix Media Waveform Visualizer.
 * Renders dynamic dot-matrix equalizer bars that pulse smoothly.
 */
class MediaDotsView @JvmOverloads constructor(
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

    var isPlaying: Boolean = true
        set(value) { field = value; updateRunning(); invalidate() }

    private val barCounts = IntArray(16) { Random.nextInt(1, 6) }
    private var running = false

    private val animator = object : Runnable {
        override fun run() {
            if (!running) return
            for (i in barCounts.indices) {
                val change = Random.nextInt(-1, 2)
                barCounts[i] = (barCounts[i] + change).coerceIn(1, 5)
            }
            invalidate()
            postDelayed(this, 140)
        }
    }

    /** Only animate while playing and actually on screen (it used to spin at 7 Hz forever). */
    private fun updateRunning() {
        val shouldRun = isPlaying && isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE && isShown
        if (shouldRun == running) return
        running = shouldRun
        removeCallbacks(animator)
        if (running) post(animator)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(animator)
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
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cols = barCounts.size
        val rows = 5
        val dotRadius = 2.2f * density
        val colSpacing = w / cols
        val rowSpacing = h / (rows + 1)

        for (c in 0 until cols) {
            val cx = colSpacing * c + colSpacing / 2f
            val litBars = if (isPlaying) barCounts[c] else 1
            for (r in 0 until rows) {
                val cy = h - (rowSpacing * (r + 1))
                val isLit = r < litBars
                val paint = when {
                    isLit && c % 4 == 0 -> accentPaint
                    isLit -> onPaint
                    else -> offPaint
                }
                canvas.drawCircle(cx, cy, dotRadius, paint)
            }
        }
    }
}
