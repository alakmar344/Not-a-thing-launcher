package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * A Nothing-style Scratchpad / Daily Quote Element (v7.6).
 * Renders user custom text in DotFont with a live accent dot.
 */
class QuoteDotsView @JvmOverloads constructor(
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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        letterSpacing = 0.08f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (e: Exception) { null }
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        letterSpacing = 0.10f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_bold) } catch (e: Exception) { null }
    }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    private val defaultQuote = context.getString(R.string.quote_default)
    private val titleText = context.getString(R.string.element_quick_note).uppercase()
    private val subText = context.getString(R.string.quote_tap_to_edit)

    /** Upper-cased, whitespace-collapsed and truncated once per change, not every frame. */
    private var displayText = defaultQuote.uppercase().take(MAX_CHARS)

    var quoteText: String = defaultQuote
        set(value) {
            // Newlines/tabs have no glyph and would leave odd gaps: collapse all whitespace.
            field = value.trim().replace(WHITESPACE, " ").ifEmpty { defaultQuote }
            displayText = field.uppercase().let {
                if (it.length > MAX_CHARS) it.take(MAX_CHARS - 1).trimEnd() + "\u2026" else it
            }
            contentDescription = field
            invalidate()
        }

    init {
        contentDescription = quoteText
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        if (w <= 0f || height <= 0) return

        // 1. Draw Title and live dot
        canvas.drawText(titleText, 0f, 12f * density, titlePaint)
        canvas.drawCircle(w - 10f * density, 8f * density, 3f * density, accentPaint)

        // 2. Draw DotFont quote text
        val raster = DotFont.rasterizeCached(displayText, 1)
        val maxAvailableW = w - 8f * density
        val computedPitch = (maxAvailableW / raster.columns.coerceAtLeast(1)).coerceAtMost(2.6f * density)
        val dotPitch = computedPitch.coerceAtLeast(1.2f * density)
        val dotRadius = dotPitch * 0.38f
        val startY = 22f * density
        val startX = 0f

        for (r in 0 until DotFont.ROWS) {
            for (c in 0 until raster.columns) {
                if (raster.lit[r][c]) {
                    val cx = startX + c * dotPitch + dotRadius
                    val cy = startY + r * dotPitch + dotRadius
                    canvas.drawCircle(cx, cy, dotRadius, onPaint)
                }
            }
        }

        // 3. Draw Subtitle
        canvas.drawText(subText, 0f, startY + DotFont.ROWS * dotPitch + 15f * density, textPaint)
    }

    private companion object {
        const val MAX_CHARS = 24
        val WHITESPACE = Regex("\\s+")
    }
}
