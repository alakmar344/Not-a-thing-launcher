package com.nothinglauncher

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * A Nothing OS-inspired System RAM & CPU Resource Monitor.
 * Displays available RAM in DotFont with an active 16-dot resource bar.
 */
class SystemMonitorDotsView @JvmOverloads constructor(
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
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        letterSpacing = 0.08f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (e: Exception) { null }
    }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    private var availRamGb: Float = 3.8f
    private var totalRamGb: Float = 8.0f
    private var usedPercent: Int = 52

    /** Formatted in [updateStats] (every 5 s) instead of on every draw. */
    private var ramText = ""
    private var subText = ""

    private var running = false

    /** RAM changes constantly; refresh while the tile is on screen instead of only on bind. */
    private val refresher = object : Runnable {
        override fun run() {
            if (!running) return
            updateStats()
            postDelayed(this, 5_000L)
        }
    }

    init {
        updateStats()
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE && isShown
        if (shouldRun == running) return
        running = shouldRun
        removeCallbacks(refresher)
        if (running) post(refresher)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(refresher)
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

    fun updateStats() {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            if (memInfo.totalMem > 0) {
                totalRamGb = memInfo.totalMem / (1024f * 1024f * 1024f)
                availRamGb = memInfo.availMem / (1024f * 1024f * 1024f)
                val used = memInfo.totalMem - memInfo.availMem
                usedPercent = ((used.toFloat() / memInfo.totalMem) * 100).toInt().coerceIn(0, 100)
            }
        } catch (_: Exception) {
            availRamGb = 3.8f
            totalRamGb = 8.0f
            usedPercent = 52
        }
        // Locale.US: a "3,8" decimal comma or non-ASCII digits have no DotFont glyph.
        ramText = String.format(java.util.Locale.US, "%.1f GB", availRamGb)
        subText = context.getString(R.string.system_monitor_sub, totalRamGb, usedPercent)
        contentDescription = "$ramText, $subText"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Draw "X.X GB" in DotFont
        val dotPitch = 3.8f * density
        val dotRadius = 1.5f * density

        // Dots are centred on their grid point: inset by one radius so column 0 isn't clipped.
        var startX = dotRadius
        val startY = 4f * density

        for (ch in ramText) {
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

        // 2. Draw 16-segment horizontal usage dot bar
        val barY = h - 18f * density
        val totalDots = 16
        val barDotRadius = 2.4f * density
        val barSpacing = w / totalDots
        val litDots = (usedPercent * totalDots / 100).coerceIn(1, totalDots)

        for (i in 0 until totalDots) {
            val bx = barSpacing * i + barSpacing / 2f
            val paint = when {
                i < litDots && i >= 13 -> accentPaint
                i < litDots -> onPaint
                else -> offPaint
            }
            canvas.drawCircle(bx, barY, barDotRadius, paint)
        }

        // 3. Subtitle text
        canvas.drawText(subText, 0f, h - 2f * density, labelPaint)
    }
}
