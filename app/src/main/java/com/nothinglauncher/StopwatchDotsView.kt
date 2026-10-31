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
            contentDescription = context.getString(R.string.stopwatch_label)
            removeCallbacks(ticker)
            post(ticker)
        }
    }

    fun pause() {
        if (isRunning) {
            elapsedWhenPaused = SystemClock.elapsedRealtime() - baseTime
            isRunning = false
            removeCallbacks(ticker)
            updateDescription()
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
        updateDescription()
        invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            removeCallbacks(ticker)
        } else if (isRunning && isShown) {
            removeCallbacks(ticker)
            post(ticker)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            removeCallbacks(ticker)
        } else if (isRunning && isShown) {
            removeCallbacks(ticker)
            post(ticker)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) {
            removeCallbacks(ticker)
        } else if (isRunning && isShown) {
            removeCallbacks(ticker)
            post(ticker)
        }
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

    private fun currentElapsed(): Long =
        (if (isRunning) SystemClock.elapsedRealtime() - baseTime else elapsedWhenPaused).coerceAtLeast(0L)

    private var cachedTenths = -1L
    private var cachedText = ""

    /**
     * MM:SS.d, or H:MM:SS once past an hour (minutes used to wrap back to 00 silently).
     * Re-formatted only when the shown value changes, not on every frame.
     */
    private fun formatElapsed(totalMs: Long): String {
        val tenthsTotal = totalMs / 100
        if (tenthsTotal == cachedTenths) return cachedText
        cachedTenths = tenthsTotal
        val hours = totalMs / 3_600_000
        val minutes = (totalMs / 60_000) % 60
        val seconds = (totalMs / 1000) % 60
        val tenths = tenthsTotal % 10
        // Locale.US: DotFont only has ASCII digits (Arabic/Persian locales would render blanks).
        cachedText = if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
        }
        return cachedText
    }

    private fun updateDescription() {
        contentDescription = "${context.getString(R.string.stopwatch_label)} ${formatElapsed(currentElapsed())}"
    }

    init {
        // Must run after cachedTenths/cachedText above are initialised.
        updateDescription()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val totalMs = currentElapsed()
        val timeStr = formatElapsed(totalMs)

        val dotPitch = 4.2f * density
        val dotRadius = 1.6f * density

        // Measure text width in DotFont
        var totalDotsWidth = 0f
        for (i in timeStr.indices) {
            val glyph = DotFont.glyphFor(timeStr[i])
            val cols = glyph.firstOrNull()?.length ?: 0
            totalDotsWidth += (cols * dotPitch) + (if (i < timeStr.length - 1) dotPitch * 0.8f else 0f)
        }

        // Dots are centred on c * pitch, so the last column ends one pitch short of
        // totalDotsWidth: shift by half a pitch to centre the visible digits.
        var startX = (w - totalDotsWidth + dotPitch) / 2f
        val startY = (h - ((DotFont.ROWS - 1) * dotPitch)) / 2f

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
