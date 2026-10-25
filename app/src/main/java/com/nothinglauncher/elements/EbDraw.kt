package com.nothinglauncher.elements

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.util.Locale

/** Small drawing helpers shared by the Weather, Media and Voice memo elements. */
internal object EbDraw {

    fun upper(s: String): String = s.uppercase(Locale.getDefault())

    /** Single-line ellipsized copy of [text] fitting [width] px with [paint]. */
    fun fit(text: String, paint: TextPaint, width: Float): String {
        if (width <= 0f) return ""
        if (paint.measureText(text) <= width) return text
        return TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()
    }

    /** Keeps only characters the 5×7 dot font can render; others become spaces. */
    fun dotSafe(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text.uppercase(Locale.US)) {
            sb.append(if (c in '0'..'9' || c in 'A'..'Z' || c in DOT_SYMBOLS) c else ' ')
        }
        return sb.toString()
    }

    private const val DOT_SYMBOLS = ":.-+/%°!?# "

    /** Draws [d] tinted with [color] centred inside [r] at [size] px. */
    fun drawIcon(canvas: Canvas, d: Drawable?, r: RectF, size: Float, color: Int) {
        if (d == null || size <= 0f) return
        val half = size / 2f
        val cx = r.centerX()
        val cy = r.centerY()
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.setTint(color)
        d.draw(canvas)
    }

    /** Builds a centred multi-line layout, trimmed to [maxLines]. */
    fun centredLayout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setMaxLines(maxLines.coerceAtLeast(1))
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    /** Draws [layout] centred in the box ([left], [top], [width], [height]). */
    fun drawLayoutCentred(canvas: Canvas, layout: StaticLayout, left: Float, top: Float, width: Float, height: Float) {
        canvas.save()
        canvas.translate(left + (width - layout.width) / 2f, top + (height - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }
}
