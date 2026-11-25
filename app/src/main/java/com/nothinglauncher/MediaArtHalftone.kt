package com.nothinglauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.content.Context
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Converts album artwork into a crisp 10x10 Nothing-style Bayer 4x4 ordered-dither dot portrait.
 */
object MediaArtHalftone {

    private const val GRID = 10
    private val BAYER_4X4 = arrayOf(
        floatArrayOf(0f / 16f, 8f / 16f, 2f / 16f, 10f / 16f),
        floatArrayOf(12f / 16f, 4f / 16f, 14f / 16f, 6f / 16f),
        floatArrayOf(3f / 16f, 11f / 16f, 1f / 16f, 9f / 16f),
        floatArrayOf(15f / 16f, 7f / 16f, 13f / 16f, 5f / 16f)
    )

    /** Pure matrix computation (0 = off, 1 = monochrome lit, 2 = red accent highlight). */
    fun quantize(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val scaled = Bitmap.createScaledBitmap(bitmap, GRID, GRID, true)
        val pixels = IntArray(GRID * GRID)
        scaled.getPixels(pixels, 0, GRID, 0, 0, GRID, GRID)
        if (scaled !== bitmap) scaled.recycle()

        val out = ByteArray(GRID * GRID)
        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                val p = pixels[y * GRID + x]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val threshold = BAYER_4X4[y and 3][x and 3]
                val redDominance = (r - (g + b) * 0.5f) / 255f
                out[y * GRID + x] = when {
                    redDominance > 0.24f && lum > 0.18f -> 2
                    lum > threshold * 0.88f -> 1
                    else -> 0
                }
            }
        }
        return out
    }
}

class MediaArtDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dot_on) }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dot_off) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.nothing_red) }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    var matrix: ByteArray? = null
        set(value) {
            if (field?.contentEquals(value ?: byteArrayOf()) == true && value != null) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = matrix ?: return
        if (m.size != 100 || width <= 0 || height <= 0) return
        val size = min(width, height).toFloat()
        val x0 = (width - size) / 2f
        val y0 = (height - size) / 2f
        val pitch = size / 10f
        val r = pitch * 0.36f
        for (row in 0 until 10) {
            val cy = y0 + (row + 0.5f) * pitch
            for (col in 0 until 10) {
                val cx = x0 + (col + 0.5f) * pitch
                val v = m[row * 10 + col].toInt()
                val paint = when (v) {
                    2 -> accentPaint
                    1 -> onPaint
                    else -> offPaint
                }
                canvas.drawCircle(cx, cy, if (v == 0) r * 0.72f else r, paint)
            }
        }
    }
}
