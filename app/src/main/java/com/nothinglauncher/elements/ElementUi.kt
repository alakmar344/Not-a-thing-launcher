package com.nothinglauncher.elements

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.nothinglauncher.DotFont
import com.nothinglauncher.R

/** Shared typography, colours and dot-matrix drawing so every element looks like one family. */
object ElementUi {

    fun mono(context: Context): Typeface? = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (e: Exception) { null }
    fun monoBold(context: Context): Typeface? = try { ResourcesCompat.getFont(context, R.font.space_mono_bold) } catch (e: Exception) { null }
    fun grotesk(context: Context): Typeface? = try { ResourcesCompat.getFont(context, R.font.space_grotesk) } catch (e: Exception) { null }

    fun color(context: Context, res: Int) = ContextCompat.getColor(context, res)

    fun dotOn(context: Context) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(context, R.color.dot_on) }
    fun dotOff(context: Context) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(context, R.color.dot_off) }
    fun accent(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    /** Uppercase monospace label paint (the Nothing "data" voice). */
    fun labelPaint(context: Context, sizeSp: Float = 10f, primary: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(context, if (primary) R.color.text_primary else R.color.text_secondary)
        textSize = sizeSp * context.resources.displayMetrics.scaledDensity
        letterSpacing = 0.08f
        typeface = mono(context)
    }

    fun bodyPaint(context: Context, sizeSp: Float = 14f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(context, R.color.text_primary)
        textSize = sizeSp * context.resources.displayMetrics.scaledDensity
        typeface = grotesk(context)
    }

    /** Width in px of [text] rendered with [DotFont] at [pitch]. */
    fun dotTextWidth(text: String, pitch: Float, spacing: Int = 1): Float =
        DotFont.rasterize(text, spacing).columns * pitch

    /** Largest pitch so [text] fits in [maxW]×[maxH]. */
    fun fitPitch(text: String, maxW: Float, maxH: Float, spacing: Int = 1): Float {
        val cols = DotFont.rasterize(text, spacing).columns.coerceAtLeast(1)
        return minOf(maxW / cols, maxH / DotFont.ROWS).coerceAtLeast(0.5f)
    }

    /**
     * Draws [text] as dots with top-left at ([x], [y]). Unlit dots are drawn with [off] if given.
     * Characters whose index is in [accentChars] use [accentPaint]. Returns the drawn width.
     */
    fun drawDotText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        pitch: Float,
        on: Paint,
        off: Paint? = null,
        accentPaint: Paint? = null,
        accentChars: Set<Int> = emptySet(),
        dotScale: Float = 0.38f,
        spacing: Int = 1
    ): Float {
        val r = DotFont.rasterize(text, spacing)
        val radius = pitch * dotScale
        for (row in 0 until DotFont.ROWS) {
            for (col in 0 until r.columns) {
                val cx = x + (col + 0.5f) * pitch
                val cy = y + (row + 0.5f) * pitch
                if (r.lit[row][col]) {
                    val ci = r.charIndexAtColumn[col]
                    val p = if (accentPaint != null && ci in accentChars) accentPaint else on
                    canvas.drawCircle(cx, cy, radius, p)
                } else if (off != null) {
                    canvas.drawCircle(cx, cy, radius * 0.8f, off)
                }
            }
        }
        return r.columns * pitch
    }

    /** Small accent dot followed by an uppercase label, baseline at [y]. Returns end x. */
    fun drawHeader(canvas: Canvas, x: Float, y: Float, title: String, accentPaint: Paint, label: TextPaint): Float {
        val d = label.textSize * 0.22f
        canvas.drawCircle(x + d, y - label.textSize * 0.33f, d, accentPaint)
        val tx = x + d * 2 + label.textSize * 0.6f
        canvas.drawText(title.uppercase(), tx, y, label)
        return tx + label.measureText(title.uppercase())
    }
}
