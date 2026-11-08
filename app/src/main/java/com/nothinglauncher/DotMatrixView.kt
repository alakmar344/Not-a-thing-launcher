package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Renders text with [DotFont] as round LED dots.
 *
 * In panel mode ([panel] = true) the whole view is filled with a grid of unlit dots and the
 * text is lit up *on that grid*, like a real LED matrix. The dot pitch always shrinks to fit
 * so text can never overflow the view.
 */
open class DotMatrixView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var raster: DotFont.Raster = DotFont.rasterize("")

    var text: String = ""
        set(value) {
            if (field == value) return
            val newRaster = DotFont.rasterize(value, letterSpacingCols)
            val widthChanged = newRaster.columns != raster.columns
            field = value
            raster = newRaster
            contentDescription = value
            if (widthChanged) requestLayout()
            invalidate()
        }

    var dotColor: Int
        get() = onPaint.color
        set(value) { onPaint.color = value; glowPaint.color = value; invalidate() }

    /** Colour of unlit dots; 0 means "don't draw unlit dots". */
    var offColor: Int = 0
        set(value) { field = value; offPaint.color = value; invalidate() }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    var panel: Boolean = false
        set(value) { field = value; requestLayout(); invalidate() }

    var panelPadRows: Int = 1
        set(value) { field = value.coerceAtLeast(0); requestLayout(); invalidate() }

    /** Dot radius as a fraction of the dot pitch. */
    var dotScale: Float = 0.36f
        set(value) { field = value.coerceIn(0.1f, 0.5f); invalidate() }

    var letterSpacingCols: Int = 1
        set(value) {
            field = value.coerceAtLeast(0)
            raster = DotFont.rasterize(text, field)
            requestLayout(); invalidate()
        }

    var glow: Boolean = false
        set(value) { field = value; invalidate() }

    /** 0 = start, 1 = center, 2 = end. */
    var gravityMode: Int = 1
        set(value) { field = value; invalidate() }

    /** Characters (by index in [text]) drawn in [accentColor]. */
    var accentIndices: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }

    /** When false, accent characters are drawn unlit (used for the blinking colon). */
    var accentVisible: Boolean = true
        set(value) { if (field != value) { field = value; invalidate() } }

    /**
     * Power-on sweep progress, 0..1 (1 = fully lit, the resting state). Columns ahead of the
     * scan line are dark, a narrow band at it glows in the accent colour.
     */
    var reveal: Float = 1f
        set(value) { val v = value.coerceIn(0f, 1f); if (v != field) { field = v; invalidate() } }

    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var revealAnimator: android.animation.ValueAnimator? = null

    /** Plays the power-on sweep once. Cheap: ~40 frames of one view. */
    fun playReveal(durationMs: Long = 720L) {
        if (!isAttachedToWindow || width == 0) return
        revealAnimator?.cancel()
        revealAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = android.view.animation.PathInterpolator(0.3f, 0f, 0.2f, 1f)
            addUpdateListener { reveal = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    reveal = 1f
                    if (revealAnimator === animation) revealAnimator = null
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        revealAnimator?.cancel()
        revealAnimator = null
        reveal = 1f
        super.onDetachedFromWindow()
    }

    /** 0 = normal, 1 = not yet revealed (dark), 2 = on the scan line. */
    private fun scanState(fraction: Float): Int {
        if (reveal >= 1f) return 0
        val front = reveal * (1f + SCAN_BAND)
        return when {
            fraction > front -> 1
            fraction > front - SCAN_BAND -> 2
            else -> 0
        }
    }

    init {
        onPaint.color = ContextCompat.getColor(context, R.color.dot_on)
        glowPaint.color = onPaint.color
        accentPaint.color = ContextCompat.getColor(context, R.color.nothing_red)
        context.obtainStyledAttributes(attrs, R.styleable.DotMatrixView, defStyleAttr, 0).use { a ->
            dotColor = a.getColor(R.styleable.DotMatrixView_dmDotColor, onPaint.color)
            offColor = a.getColor(R.styleable.DotMatrixView_dmOffColor, 0)
            accentColor = a.getColor(R.styleable.DotMatrixView_dmAccentColor, accentPaint.color)
            panel = a.getBoolean(R.styleable.DotMatrixView_dmPanel, false)
            panelPadRows = a.getInt(R.styleable.DotMatrixView_dmPanelPadRows, 1)
            dotScale = a.getFloat(R.styleable.DotMatrixView_dmDotScale, 0.36f)
            letterSpacingCols = a.getInt(R.styleable.DotMatrixView_dmLetterSpacing, 1)
            glow = a.getBoolean(R.styleable.DotMatrixView_dmGlow, false)
            gravityMode = a.getInt(R.styleable.DotMatrixView_dmGravity, 1)
            text = a.getString(R.styleable.DotMatrixView_dmText) ?: ""
        }
        glowPaint.alpha = 0x22
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private val rows: Int get() = if (panel) DotFont.ROWS + panelPadRows * 2 else DotFont.ROWS

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultPitch = 6f * density
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        // The measured height includes padding; an EXACTLY/AT_MOST spec must never be exceeded.
        val padV = paddingTop + paddingBottom
        val desiredHeight = (rows * defaultPitch).toInt() + padV
        val height = when (hMode) {
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> min(hSize, desiredHeight)
            else -> desiredHeight
        }
        val pitch = max(0, height - padV) / rows.toFloat()
        val extraCols = if (panel) 2 else 0
        val desiredWidth = ((raster.columns + extraCols) * pitch).toInt() + paddingLeft + paddingRight
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val width = when (wMode) {
            MeasureSpec.EXACTLY -> wSize
            MeasureSpec.AT_MOST -> min(wSize, desiredWidth)
            else -> desiredWidth
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()
        if (availW <= 0f || availH <= 0f) return
        val cols = raster.columns
        val extraCols = if (panel) 2 else 0
        var pitch = availH / rows
        if (cols + extraCols > 0) pitch = min(pitch, availW / (cols + extraCols))
        if (pitch <= 0f) return
        val radius = pitch * dotScale

        if (panel) {
            val gridCols = max(floor(availW / pitch).toInt(), cols)
            val x0 = paddingLeft + (availW - gridCols * pitch) / 2f
            val y0 = paddingTop + (availH - rows * pitch) / 2f
            val textStart = when (gravityMode) {
                0 -> min(1, gridCols - cols)
                2 -> max(0, gridCols - cols - 1)
                else -> (gridCols - cols) / 2
            }
            for (row in 0 until rows) {
                val cy = y0 + (row + 0.5f) * pitch
                val tr = row - panelPadRows
                for (col in 0 until gridCols) {
                    val cx = x0 + (col + 0.5f) * pitch
                    val tc = col - textStart
                    val lit = tr in 0 until DotFont.ROWS && tc in 0 until cols && raster.lit[tr][tc]
                    when (scanState(col / gridCols.toFloat())) {
                        0 -> drawDot(canvas, cx, cy, radius, lit, if (lit) raster.charIndexAtColumn[tc] else -1)
                        1 -> drawDot(canvas, cx, cy, radius, false, -1)
                        else -> drawScanDot(canvas, cx, cy, radius, lit)
                    }
                }
            }
        } else {
            if (cols == 0) return
            val textW = cols * pitch
            val x0 = paddingLeft + when (gravityMode) {
                0 -> 0f
                2 -> availW - textW
                else -> (availW - textW) / 2f
            }
            val y0 = paddingTop + (availH - DotFont.ROWS * pitch) / 2f
            for (row in 0 until DotFont.ROWS) {
                val cy = y0 + (row + 0.5f) * pitch
                for (col in 0 until cols) {
                    val lit = raster.lit[row][col]
                    if (!lit && offColor == 0) continue
                    val cx = x0 + (col + 0.5f) * pitch
                    when (scanState(col / cols.toFloat())) {
                        0 -> drawDot(canvas, cx, cy, radius, lit, raster.charIndexAtColumn[col])
                        1 -> drawDot(canvas, cx, cy, radius, false, -1)
                        else -> drawScanDot(canvas, cx, cy, radius, lit)
                    }
                }
            }
        }
    }

    private fun drawScanDot(canvas: Canvas, cx: Float, cy: Float, r: Float, lit: Boolean) {
        scanPaint.color = accentPaint.color
        // Lit dots flare at full accent; the unlit panel under the line glows faintly.
        scanPaint.alpha = if (lit) 0xFF else 0x55
        canvas.drawCircle(cx, cy, if (lit) r * 1.08f else r * 0.82f, scanPaint)
    }

    private fun drawDot(canvas: Canvas, cx: Float, cy: Float, r: Float, lit: Boolean, charIndex: Int) {
        if (!lit) {
            if (offColor != 0) canvas.drawCircle(cx, cy, r * 0.82f, offPaint)
            return
        }
        val isAccent = charIndex >= 0 && charIndex in accentIndices
        if (isAccent && !accentVisible) {
            if (offColor != 0) canvas.drawCircle(cx, cy, r * 0.82f, offPaint)
            return
        }
        val paint = if (isAccent) accentPaint else onPaint
        if (glow) {
            glowPaint.color = paint.color
            glowPaint.alpha = if (isAccent) 0x40 else 0x16
            canvas.drawCircle(cx, cy, r * 1.55f, glowPaint)
        }
        canvas.drawCircle(cx, cy, r, paint)
    }

    private companion object {
        /** Width of the glowing scan line, as a fraction of the panel. */
        const val SCAN_BAND = 0.14f
    }
}
