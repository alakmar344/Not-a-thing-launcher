package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * A Nothing OS-inspired Chrono / Stopwatch Display.
 * Renders high-precision elapsed time (MM:SS.d) using [DotFont].
 */
class StopwatchDotsView @JvmOverloads constructor(
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

    var isRunning: Boolean = false
        private set

    private var baseTime: Long = 0L
    private var elapsedWhenPaused: Long = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (isRunning) {
                invalidate()
                postDelayed(this, 100)
            }
        }
    }

    fun start() {
        if (!isRunning) {
            baseTime = SystemClock.elapsedRealtime() - elapsedWhenPaused
            isRunning = true
            post(ticker)
        }
    }

    fun pause() {
        if (isRunning) {
            elapsedWhenPaused = SystemClock.elapsedRealtime() - baseTime
            isRunning = false
            removeCallbacks(ticker)
            invalidate()
        }
    }

    fun toggle(): Boolean {
        if (isRunning) pause() else start()
        return isRunning
    }

    fun reset() {
        isRunning = false
        removeCallbacks(ticker)
        baseTime = 0L
        elapsedWhenPaused = 0L
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Resume ticking after a detach/re-attach (page rebinds, drag & drop) while running.
        if (isRunning) {
            removeCallbacks(ticker)
            post(ticker)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val totalMs = if (isRunning) SystemClock.elapsedRealtime() - baseTime else elapsedWhenPaused
        val minutes = (totalMs / 60000) % 60
        val seconds = (totalMs / 1000) % 60
        val tenths = (totalMs / 100) % 10

        // Locale.US: DotFont only has ASCII digits (Arabic/Persian locales would render blanks).
        val timeStr = String.format(java.util.Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)

        val dotPitch = 4.2f * density
        val dotRadius = 1.6f * density

        // Measure text width in DotFont
        var totalDotsWidth = 0f
        for (i in timeStr.indices) {
            val glyph = DotFont.glyphFor(timeStr[i])
            val cols = glyph.firstOrNull()?.length ?: 0
            totalDotsWidth += (cols * dotPitch) + (if (i < timeStr.length - 1) dotPitch * 0.8f else 0f)
        }

        var startX = (w - totalDotsWidth) / 2f
        val startY = (h - (DotFont.ROWS * dotPitch)) / 2f

        for (ch in timeStr) {
            val glyph = DotFont.glyphFor(ch)
            val cols = glyph.firstOrNull()?.length ?: 0
            for (r in 0 until DotFont.ROWS) {
                val rowStr = glyph.getOrNull(r) ?: ""
                for (c in 0 until cols) {
                    val isLit = rowStr.getOrNull(c) == '#'
                    val cx = startX + (c * dotPitch)
                    val cy = startY + (r * dotPitch)
                    val paint = when {
                        isLit && ch == '.' -> accentPaint
                        isLit -> onPaint
                        else -> offPaint
                    }
                    canvas.drawCircle(cx, cy, dotRadius, paint)
                }
            }
            startX += (cols * dotPitch) + (dotPitch * 0.8f)
        }
    }
}
