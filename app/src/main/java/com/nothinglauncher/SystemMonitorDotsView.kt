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
        textSize = 10f * resources.displayMetrics.scaledDensity
        letterSpacing = 0.08f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (e: Exception) { null }
    }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    private var availRamGb: Float = 3.8f
    private var totalRamGb: Float = 8.0f
    private var usedPercent: Int = 52

    init {
        updateStats()
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
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Draw "X.X GB" in DotFont
        val ramText = String.format("%.1f GB", availRamGb)
        val dotPitch = 3.8f * density
        val dotRadius = 1.5f * density

        var startX = 0f
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
        val subText = String.format("FREE OF %.0f GB · %d%% USED", totalRamGb, usedPercent)
        canvas.drawText(subText, 0f, h - 2f * density, labelPaint)
    }
}
