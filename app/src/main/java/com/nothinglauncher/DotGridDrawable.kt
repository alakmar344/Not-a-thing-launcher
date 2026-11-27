package com.nothinglauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.animation.DecelerateInterpolator
import com.nothinglauncher.perf.VisualTier
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The signature dot-matrix backdrop: a fine, evenly spaced dot grid like the unlit face of an LED panel.
 *
 * v13 additions:
 * - Scroll-reactive light pool parallax (`setParallax`) via zero-allocation Shader `LocalMatrix`.
 * - Touch-reactive dot ripple (`triggerRipple`) on empty-space taps (`BALANCED` and `RICH`).
 * - Bedtime night-dimming (`nightDim`) lowering ambient dot brightness during late hours or Focus Mode.
 */
class DotGridDrawable(
    private val density: Float,
    private val backgroundColor: Int = Color.BLACK,
    dotColor: Int = 0xFF1A1A1D.toInt(),
    pitchDp: Float = 18f,
    dotRadiusDp: Float = 1.1f,
    private val vignette: Boolean = true,
    lightDotColor: Int = 0,
    private val rippleEnabled: Boolean = false,
    private val rippleColor: Int = 0xFFD71921.toInt(),
    private val dimDots: Boolean = false
) : Drawable() {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint().apply { color = backgroundColor }
    private val lightTile: BitmapShader?
    private val rippleTile: BitmapShader?
    private val lightMatrix = Matrix()
    private var lightMask: RadialGradient? = null

    private var parallaxOffsetX = 0f
    private var drawerFade = 0f

    private var rippleX = 0f
    private var rippleY = 0f
    private var rippleProgress = 0f
    private var rippleActive = false
    private var rippleAnimator: ValueAnimator? = null

    private var drawableAlpha = 255

    init {
        val pitch = max(4, (pitchDp * density).roundToInt())
        gridPaint.shader = tileShader(pitch, dotRadiusDp * density, dotColor)
        gridPaint.alpha = if (dimDots) 135 else 255
        lightTile = if (lightDotColor != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tileShader(pitch, dotRadiusDp * density * 1.3f, lightDotColor)
        } else null
        rippleTile = if (rippleEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tileShader(pitch, dotRadiusDp * density * 1.45f, rippleColor)
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

    fun setParallax(fractionX: Float, drawerProgress: Float = 0f) {
        val clampedX = fractionX.coerceIn(-1f, 1f)
        val clampedDrawer = drawerProgress.coerceIn(0f, 1f)
        if (clampedX == parallaxOffsetX && clampedDrawer == drawerFade) return
        parallaxOffsetX = clampedX
        drawerFade = clampedDrawer
        updateLightMatrix()
        invalidateSelf()
    }

    private fun updateLightMatrix() {
        val mask = lightMask ?: return
        val w = bounds.width().toFloat()
        if (w <= 0f) return
        lightMatrix.setTranslate(parallaxOffsetX * w * 0.16f, -drawerFade * bounds.height() * 0.12f)
        mask.setLocalMatrix(lightMatrix)
        val baseLight = (1f - drawerFade * 0.7f) * (if (dimDots) 140f else 255f)
        lightPaint.alpha = (baseLight * (drawableAlpha / 255f)).roundToInt().coerceIn(0, 255)
    }

    fun triggerRipple(x: Float, y: Float) {
        if (rippleTile == null || bounds.isEmpty) return
        rippleX = x
        rippleY = y
        rippleAnimator?.cancel()
        rippleActive = true
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                rippleProgress = it.animatedValue as Float
                updateRippleShader()
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rippleActive = false
                    rippleProgress = 0f
                    ripplePaint.shader = null
                    invalidateSelf()
                }
            })
            start()
        }
    }

    private fun updateRippleShader() {
        val tile = rippleTile ?: return
        val maxR = max(bounds.width(), bounds.height()) * 0.42f
        val radius = (24f * density + maxR * rippleProgress).coerceAtLeast(1f)
        val alpha = ((1f - rippleProgress) * 185f).roundToInt().coerceIn(0, 255)
        val innerEdge = (rippleProgress * 0.65f).coerceIn(0f, 0.85f)
        val midEdge = (innerEdge + 0.14f).coerceIn(innerEdge + 0.01f, 0.98f)
        val ringMask = RadialGradient(
            rippleX, rippleY, radius,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(alpha, 0, 0, 0),
                Color.argb((alpha * 0.4f).roundToInt(), 0, 0, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, innerEdge, midEdge, 1f),
            Shader.TileMode.CLAMP
        )
        ripplePaint.shader = ComposeShader(tile, ringMask, PorterDuff.Mode.DST_IN)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) return
        if (vignette) {
            vignettePaint.shader = RadialGradient(
                bounds.exactCenterX(), bounds.top + bounds.height() * 0.38f,
                max(bounds.width(), bounds.height()) * 0.78f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, (backgroundColor and 0x00FFFFFF) or 0xCC000000.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        lightTile?.let { tile ->
            val mask = RadialGradient(
                bounds.exactCenterX(), bounds.top + bounds.height() * 0.32f,
                max(bounds.width(), bounds.height()) * 0.62f,
                intArrayOf(0xFF000000.toInt(), 0x80000000.toInt(), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            lightMask = mask
            updateLightMatrix()
            lightPaint.shader = ComposeShader(tile, mask, PorterDuff.Mode.DST_IN)
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, backgroundPaint)
        canvas.drawRect(b, gridPaint)
        if (lightPaint.shader != null) canvas.drawRect(b, lightPaint)
        if (rippleActive && ripplePaint.shader != null) canvas.drawRect(b, ripplePaint)
        if (vignette) canvas.drawRect(b, vignettePaint)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            rippleAnimator?.cancel()
            rippleActive = false
            rippleProgress = 0f
            ripplePaint.shader = null
        }
        return changed
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        gridPaint.alpha = ((if (dimDots) 135 else 255) * drawableAlpha / 255f).roundToInt().coerceIn(0, 255)
        updateLightMatrix()
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
        private fun isNightDimActive(context: Context): Boolean {
            val prefs = NothingPrefs.get(context)
            if (prefs.focusMode) return true
            if (!prefs.nightDimDots) return false
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return hour >= 22 || hour < 7
        }

        /** The backdrop for [context] at its current visual tier and hardware accent. */
        fun forTier(context: Context): DotGridDrawable {
            val fx = VisualTier.effects(context)
            val tier = VisualTier.current(context)
            return DotGridDrawable(
                context.resources.displayMetrics.density,
                backgroundColor = context.getColor(R.color.nothing_bg),
                dotColor = context.getColor(R.color.dot_off),
                vignette = fx.vignette,
                lightDotColor = if (fx.backdropLight) context.getColor(R.color.dot_lit_ambient) else 0,
                rippleEnabled = tier != VisualTier.Tier.LITE && VisualTier.motionAllowed(context),
                rippleColor = AccentTheme.accentColor(context),
                dimDots = isNightDimActive(context)
            )
        }
    }
}
