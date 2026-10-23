package com.nothinglauncher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The signature background: pure black with a fine, evenly spaced dot grid and a soft
 * vignette, like the unlit face of an LED panel. Rendered from a single repeating tile.
 */
class DotGridDrawable(
    density: Float,
    private val backgroundColor: Int = Color.BLACK,
    dotColor: Int = 0xFF1A1A1D.toInt(),
    pitchDp: Float = 18f,
    dotRadiusDp: Float = 1.1f
) : Drawable() {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val pitch = max(4, (pitchDp * density).roundToInt())
        val tile = Bitmap.createBitmap(pitch, pitch, Bitmap.Config.ARGB_8888)
        Canvas(tile).drawCircle(
            pitch / 2f, pitch / 2f, dotRadiusDp * density,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor }
        )
        gridPaint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) return
        vignettePaint.shader = RadialGradient(
            bounds.exactCenterX(), bounds.top + bounds.height() * 0.38f,
            max(bounds.width(), bounds.height()) * 0.78f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0xCC000000.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(backgroundColor)
        canvas.drawRect(b, gridPaint)
        canvas.drawRect(b, vignettePaint)
    }

    override fun setAlpha(alpha: Int) {
        gridPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        gridPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
