package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * A Nothing OS-inspired Event Countdown Tile.
 * Displays remaining days in bold [DotFont] with target event description.
 */
class CountdownDotsView @JvmOverloads constructor(
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

    var daysRemaining: Int = 0
        set(value) { field = value.coerceAtLeast(0); invalidate() }

    var eventTitle: String = context.getString(R.string.countdown_no_target)
        set(value) { field = value.uppercase(); contentDescription = "$field, $daysRemaining"; invalidate() }

    /** False when no date is configured or the date has already passed. */
    var targetActive: Boolean = false
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val daysStr = daysRemaining.toString()
        val dotPitch = 4.2f * density
        val dotRadius = 1.6f * density

        var startX = 0f
        val startY = 4f * density

        for (ch in daysStr) {
            val glyph = DotFont.glyphFor(ch)
            val cols = glyph.firstOrNull()?.length ?: 0
            for (r in 0 until DotFont.ROWS) {
                val rowStr = glyph.getOrNull(r) ?: ""
                for (c in 0 until cols) {
                    val isLit = rowStr.getOrNull(c) == '#'
                    val cx = startX + (c * dotPitch)
                    val cy = startY + (r * dotPitch)
                    canvas.drawCircle(cx, cy, dotRadius, if (isLit) onPaint else offPaint)
                }
            }
            startX += (cols * dotPitch) + (dotPitch * 0.8f)
        }

        // Draw "DAYS REMAINING" suffix text next to numbers
        val suffixX = startX + 6f * density
        val suffixY = startY + (DotFont.ROWS * dotPitch / 2f) + 4f * density
        canvas.drawText("DAYS", suffixX, suffixY, labelPaint)

        // Subtitle line at bottom
        val subText = if (targetActive) {
            "$eventTitle · ${context.getString(R.string.countdown_target)}"
        } else {
            eventTitle
        }
        canvas.drawText(subText, 0f, h - 2f * density, labelPaint)
    }
}
