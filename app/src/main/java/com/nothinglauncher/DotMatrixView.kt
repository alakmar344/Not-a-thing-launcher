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

    private var rasterLines: List<DotFont.Raster> = listOf(DotFont.rasterize(""))
    private var lineCharOffsets: IntArray = intArrayOf(0)
    private var maxCols: Int = 0
    private var accentMask: BooleanArray = BooleanArray(0)
    private var hasAccents: Boolean = false

    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            rebuildRaster()
            contentDescription = if (value.indexOf('\n') >= 0) value.replace('\n', ' ') else value
            requestLayout()
            invalidate()
        }

    var maxPitchDp: Float = 0f
        set(value) { field = value.coerceAtLeast(0f); requestLayout(); invalidate() }

    private fun rebuildRaster() {
        val raw = text
        if (raw.indexOf('\n') < 0) {
            val single = DotFont.rasterizeCached(raw, letterSpacingCols)
            rasterLines = listOf(single)
            lineCharOffsets = intArrayOf(0)
            maxCols = single.columns
            return
        }
        val parts = raw.split('\n')
        val list = ArrayList<DotFont.Raster>(parts.size)
        val offsets = IntArray(parts.size)
        var acc = 0
        var widest = 0
        for (i in 0 until parts.size) {
            val p = parts[i]
            val r = DotFont.rasterizeCached(p, letterSpacingCols)
            list.add(r)
            if (r.columns > widest) widest = r.columns
            offsets[i] = acc
            acc += p.length + 1 // +1 for '\n'
        }
        rasterLines = list
        lineCharOffsets = offsets
        maxCols = widest
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
            rebuildRaster()
            requestLayout(); invalidate()
        }

    var glow: Boolean = false
        set(value) { field = value; invalidate() }

    /** 0 = start, 1 = center, 2 = end. */
    var gravityMode: Int = 1
        set(value) { field = value; invalidate() }

    /** Characters (by index in [text]) drawn in [accentColor]. */
    var accentIndices: Set<Int> = emptySet()
        set(value) {
            field = value
            hasAccents = value.isNotEmpty()
            if (!hasAccents) {
                if (accentMask.isNotEmpty()) java.util.Arrays.fill(accentMask, false)
            } else {
                var maxIdx = -1
                for (idx in value) if (idx in 0..255 && idx > maxIdx) maxIdx = idx
                val needed = maxIdx + 1
                if (accentMask.size < needed) accentMask = BooleanArray(needed) else java.util.Arrays.fill(accentMask, false)
                for (idx in value) if (idx in 0 until accentMask.size) accentMask[idx] = true
            }
            invalidate()
        }

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
            maxPitchDp = a.getDimension(R.styleable.DotMatrixView_dmMaxPitchDp, 0f) / density
            glow = a.getBoolean(R.styleable.DotMatrixView_dmGlow, false)
            gravityMode = a.getInt(R.styleable.DotMatrixView_dmGravity, 1)
            text = a.getString(R.styleable.DotMatrixView_dmText) ?: ""
        }
        glowPaint.alpha = 0x22
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private val lineGapRows: Int get() = 2
    private val textRows: Int
        get() {
            val n = rasterLines.size.coerceAtLeast(1)
            return n * DotFont.ROWS + (n - 1) * lineGapRows
        }
    private val rows: Int get() = if (panel) textRows + panelPadRows * 2 else textRows

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultPitch = 6f * density
        val cappedDefaultPitch = if (maxPitchDp > 0f) min(defaultPitch, maxPitchDp * density) else defaultPitch
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        // The measured height includes padding; an EXACTLY/AT_MOST spec must never be exceeded.
        val padV = paddingTop + paddingBottom
        val desiredHeight = (rows * cappedDefaultPitch).toInt() + padV
        val height = when (hMode) {
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> min(hSize, desiredHeight)
            else -> desiredHeight
        }
        var pitch = max(0, height - padV) / rows.toFloat()
        if (maxPitchDp > 0f) pitch = min(pitch, maxPitchDp * density)
        val extraCols = if (panel) 2 else 0
        val desiredWidth = ((maxCols + extraCols) * pitch).toInt() + paddingLeft + paddingRight
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
        val cols = maxCols
        val extraCols = if (panel) 2 else 0
        var pitch = availH / rows
        if (cols + extraCols > 0) pitch = min(pitch, availW / (cols + extraCols))
        if (maxPitchDp > 0f) pitch = min(pitch, maxPitchDp * density)
        if (pitch <= 0f) return
        val radius = pitch * dotScale

        if (panel) {
            val gridCols = max(floor(availW / pitch).toInt(), cols)
            val x0 = paddingLeft + (availW - gridCols * pitch) / 2f
            val y0 = paddingTop + (availH - rows * pitch) / 2f
            val period = DotFont.ROWS + lineGapRows
            var cy = y0 + 0.5f * pitch
            for (row in 0 until rows) {
                val tr = row - panelPadRows
                val lineIdx = if (tr >= 0) tr / period else -1
                val rowInLine = if (tr >= 0) tr % period else -1
                val rLine = if (lineIdx in 0 until rasterLines.size && rowInLine in 0 until DotFont.ROWS) rasterLines[lineIdx] else null
                val lineCols = rLine?.columns ?: 0
                val textStart = when (gravityMode) {
                    0 -> min(1, gridCols - lineCols)
                    2 -> max(0, gridCols - lineCols - 1)
                    else -> (gridCols - lineCols) / 2
                }
                val charOffset = if (lineIdx in 0 until lineCharOffsets.size) lineCharOffsets[lineIdx] else 0
                val rowLit = rLine?.lit?.get(rowInLine)
                val rowCharIdx = rLine?.charIndexAtColumn
                var cx = x0 + 0.5f * pitch
                for (col in 0 until gridCols) {
                    val tc = col - textStart
                    val lit = rowLit != null && tc in 0 until lineCols && rowLit[tc]
                    val charIdx = if (lit) {
                        val rawIdx = rowCharIdx!![tc]
                        if (rawIdx >= 0) charOffset + rawIdx else -1
                    } else -1
                    when (scanState(col / gridCols.toFloat())) {
                        0 -> drawDot(canvas, cx, cy, radius, lit, charIdx)
                        1 -> drawDot(canvas, cx, cy, radius, false, -1)
                        else -> drawScanDot(canvas, cx, cy, radius, lit)
                    }
                    cx += pitch
                }
                cy += pitch
            }
        } else {
            if (cols == 0) return
            val y0 = paddingTop + (availH - textRows * pitch) / 2f
            val skipOff = offColor == 0
            for (lineIdx in 0 until rasterLines.size) {
                val rLine = rasterLines[lineIdx]
                val lineCols = rLine.columns
                if (lineCols == 0) continue
                val textW = lineCols * pitch
                val x0 = paddingLeft + when (gravityMode) {
                    0 -> 0f
                    2 -> availW - textW
                    else -> (availW - textW) / 2f
                }
                val lineY0 = y0 + lineIdx * (DotFont.ROWS + lineGapRows) * pitch
                val charOffset = lineCharOffsets[lineIdx]
                val litGrid = rLine.lit
                val colCharIdx = rLine.charIndexAtColumn
                var cy = lineY0 + 0.5f * pitch
                for (row in 0 until DotFont.ROWS) {
                    val rowLit = litGrid[row]
                    var cx = x0 + 0.5f * pitch
                    for (col in 0 until lineCols) {
                        val lit = rowLit[col]
                        if (lit || !skipOff) {
                            val rawIdx = colCharIdx[col]
                            val charIdx = if (rawIdx >= 0) charOffset + rawIdx else -1
                            when (scanState(col / cols.toFloat())) {
                                0 -> drawDot(canvas, cx, cy, radius, lit, charIdx)
                                1 -> drawDot(canvas, cx, cy, radius, false, -1)
                                else -> drawScanDot(canvas, cx, cy, radius, lit)
                            }
                        }
                        cx += pitch
                    }
                    cy += pitch
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

    private fun isAccentChar(charIndex: Int): Boolean {
        if (!hasAccents || charIndex < 0) return false
        return if (charIndex < accentMask.size) accentMask[charIndex] else charIndex in accentIndices
    }

    private fun drawDot(canvas: Canvas, cx: Float, cy: Float, r: Float, lit: Boolean, charIndex: Int) {
        if (!lit) {
            if (offColor != 0) canvas.drawCircle(cx, cy, r * 0.82f, offPaint)
            return
        }
        val isAccent = isAccentChar(charIndex)
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
