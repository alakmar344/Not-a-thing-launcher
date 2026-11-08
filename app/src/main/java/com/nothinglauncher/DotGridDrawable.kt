package com.nothinglauncher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The signature background: a fine, evenly spaced dot grid like the unlit face of an LED panel.
 * Rendered from a single repeating tile, so it costs the same however large the screen is.
 *
 * Optional layers, chosen by [com.nothinglauncher.perf.VisualTier]:
 *  - [vignette]: edges fade into the background colour;
 *  - [lightDotColor]: a soft "light pool" near the top where the dots glow brighter and a
 *    touch larger, as if the panel were lit from above. One extra composed shader pass.
 */
class DotGridDrawable(
    density: Float,
    private val backgroundColor: Int = Color.BLACK,
    dotColor: Int = 0xFF1A1A1D.toInt(),
    pitchDp: Float = 18f,
    dotRadiusDp: Float = 1.1f,
    private val vignette: Boolean = true,
    lightDotColor: Int = 0
) : Drawable() {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint().apply { color = backgroundColor }
    private val lightTile: BitmapShader?

    init {
        val pitch = max(4, (pitchDp * density).roundToInt())
        gridPaint.shader = tileShader(pitch, dotRadiusDp * density, dotColor)
        // A composed bitmap × gradient shader is only reliably hardware-accelerated from
        // Android 9; older versions simply get the flat grid.
        lightTile = if (lightDotColor != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tileShader(pitch, dotRadiusDp * density * 1.3f, lightDotColor)
        } else null
    }

    private fun tileShader(pitch: Int, radius: Float, color: Int): BitmapShader {
        val tile = Bitmap.createBitmap(pitch, pitch, Bitmap.Config.ARGB_8888)
        Canvas(tile).drawCircle(
            pitch / 2f, pitch / 2f, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        )
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) return
        if (vignette) {
            vignettePaint.shader = RadialGradient(
                bounds.exactCenterX(), bounds.top + bounds.height() * 0.38f,
                max(bounds.width(), bounds.height()) * 0.78f,
                // Fade towards the background colour rather than hard-coded black, so the light
                // theme's edges don't turn muddy grey.
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, (backgroundColor and 0x00FFFFFF) or 0xCC000000.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        lightTile?.let { tile ->
            val mask = RadialGradient(
                // Centred a third of the way down: below the usual top clock tile, around the
                // first rows of icons, where the backdrop is actually visible.
                bounds.exactCenterX(), bounds.top + bounds.height() * 0.32f,
                max(bounds.width(), bounds.height()) * 0.62f,
                intArrayOf(0xFF000000.toInt(), 0x80000000.toInt(), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            // DST_IN keeps the bright tile only where the gradient is opaque.
            lightPaint.shader = ComposeShader(tile, mask, PorterDuff.Mode.DST_IN)
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        // Fill only our bounds; drawColor() would paint the whole clip.
        canvas.drawRect(b, backgroundPaint)
        canvas.drawRect(b, gridPaint)
        if (lightPaint.shader != null) canvas.drawRect(b, lightPaint)
        if (vignette) canvas.drawRect(b, vignettePaint)
    }

    override fun setAlpha(alpha: Int) {
        gridPaint.alpha = alpha
        lightPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        gridPaint.colorFilter = colorFilter
        lightPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int =
        if (Color.alpha(backgroundColor) == 0xFF) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    companion object {
        /** The backdrop for [context] at its current visual tier. */
        fun forTier(context: android.content.Context): DotGridDrawable {
            val fx = com.nothinglauncher.perf.VisualTier.effects(context)
            return DotGridDrawable(
                context.resources.displayMetrics.density,
                backgroundColor = context.getColor(R.color.nothing_bg),
                dotColor = context.getColor(R.color.dot_off),
                vignette = fx.vignette,
                lightDotColor = if (fx.backdropLight) context.getColor(R.color.dot_lit_ambient) else 0
            )
        }
    }
}
