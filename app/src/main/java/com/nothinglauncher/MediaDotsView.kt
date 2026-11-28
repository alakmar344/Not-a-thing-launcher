package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Minimalist Dot-Matrix Media Waveform Visualizer.
 * Renders dynamic dot-matrix equalizer bars that pulse smoothly while playback is active.
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

    fun applySurfaceColors(sc: AccentTheme.SurfaceColors) {
        onPaint.color = sc.onDot
        offPaint.color = sc.offDot
        invalidate()
    }

    var isPlaying: Boolean = true
        set(value) { field = value; updateRunning(); invalidate() }

    init {
        // Decorative visualiser; the media tile's title/artist views carry the information.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val frameMs = com.nothinglauncher.perf.VisualTier.effects(context).visualizerFrameMs

    private var rng = 0x1A2B3C4D
    private val barCounts = IntArray(16) { ((it * 7 + 3) % 5) + 1 }
    private var running = false
    private val colCx = FloatArray(16)
    private val rowCy = FloatArray(5)
    private var geomW = -1
    private var geomH = -1

    private fun nextRandStep(): Int {
        rng = rng * 1664525 + 1013904223
        return ((rng ushr 16) % 3) - 1
    }

    private val animator = object : Runnable {
        override fun run() {
            if (!running) return
            for (i in barCounts.indices) {
                barCounts[i] = (barCounts[i] + nextRandStep()).coerceIn(1, 5)
            }
            invalidate()
            // Faster and smoother on capable phones, calmer (fewer redraws) on Lite ones.
            postDelayed(this, frameMs)
        }
    }

    /** Only animate while playing, motion is permitted, and view is actually on screen. */
    private fun updateRunning() {
        val shouldRun = isPlaying &&
            isAttachedToWindow &&
            windowVisibility == VISIBLE &&
            visibility == VISIBLE &&
            isShown &&
            com.nothinglauncher.perf.VisualTier.motionAllowed(context)
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
        if (geomW != width || geomH != height) {
            geomW = width
            geomH = height
            val colSpacing = w / cols
            val rowSpacing = h / (rows + 1)
            for (c in 0 until cols) colCx[c] = colSpacing * c + colSpacing * 0.5f
            for (r in 0 until rows) rowCy[r] = h - rowSpacing * (r + 1)
        }

        val dotRadius = 2.2f * density
        val peakR = dotRadius * 1.15f
        val dimR = dotRadius * 0.85f
        val playing = isPlaying

        for (c in 0 until cols) {
            val cx = colCx[c]
            val litBars = if (playing) barCounts[c] else 1
            for (r in 0 until rows) {
                val isLit = r < litBars
                val isPeak = playing && r == litBars - 1 && (c % 4 == 0 || litBars == rows)
                val paint = when {
                    isPeak -> accentPaint
                    isLit -> onPaint
                    else -> offPaint
                }
                val rDot = if (isPeak) peakR else if (isLit) dotRadius else dimR
                canvas.drawCircle(cx, rowCy[r], rDot, paint)
            }
        }
    }
}
