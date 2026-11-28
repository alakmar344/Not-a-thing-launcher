package com.nothinglauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.nothinglauncher.perf.VisualTier
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedural Nothing Glyph LED Matrix Visualizer for Desk HUD's GLYPH stage.
 * Renders concentric hardware C-arcs, audio-reactive equalizer rings, Focus Timer arc,
 * and interactive touch ripples over a crisp dot lattice. Strictly visibility-gated.
 */
class GlyphMatrixCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_off)
    }
    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_on)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nothing_red)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nothing_red)
    }

    var accentColor: Int
        get() = accentPaint.color
        set(value) {
            accentPaint.color = value
            glowPaint.color = value
            invalidate()
        }

    var isAudioActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var timerProgress01: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field != v) {
                field = v
                invalidate()
            }
        }

    var timerRunning: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val effects = VisualTier.effects(context)
    private val tier = VisualTier.current(context)
    private val frameMs = if (tier == VisualTier.Tier.LITE) 140L else 48L

    private var phase = 0f
    private var rippleActive = false
    private var rippleNx = 0.5f
    private var rippleNy = 0.5f
    private var rippleRadius = 0f
    private var running = false

    private val animator = object : Runnable {
        override fun run() {
            if (!running) return
            val step = if (isAudioActive) 0.16f else if (timerRunning) 0.08f else 0.045f
            phase = (phase + step) % (Math.PI.toFloat() * 200f)
            if (rippleActive) {
                rippleRadius += 0.055f
                if (rippleRadius > 1.3f) {
                    rippleActive = false
                    rippleRadius = 0f
                }
            }
            invalidate()
            postDelayed(this, frameMs)
        }
    }

    fun triggerRipple(nx: Float = 0.5f, ny: Float = 0.5f) {
        rippleNx = nx.coerceIn(0f, 1f)
        rippleNy = ny.coerceIn(0f, 1f)
        rippleRadius = 0.04f
        rippleActive = true
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && width > 0 && height > 0) {
            Tactile.tick(this)
            triggerRipple(event.x / width.toFloat(), event.y / height.toFloat())
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun updateRunning() {
        val allowed = VisualTier.motionAllowed(context)
        val shouldRun = allowed && isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE && isShown
        if (shouldRun == running) return
        running = shouldRun
        removeCallbacks(animator)
        if (running) post(animator)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onDetachedFromWindow() { running = false; removeCallbacks(animator); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateRunning() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); updateRunning() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateRunning() }

    private var geomW = -1
    private var geomH = -1
    private var gCols = 0
    private var gRows = 0
    private var gLatR = 0f
    private var gCx = 0f
    private var gCy = 0f
    private var gSize = 0f
    private var gMaxDiag = 1f
    private var latticeX = FloatArray(0)
    private var latticeY = FloatArray(0)
    private var latticeDistCenterNorm = FloatArray(0)
    private val outerX = FloatArray(OUTER_DOTS)
    private val outerY = FloatArray(OUTER_DOTS)
    private var gOuterDotR = 0f
    private val innerX = FloatArray(INNER_DOTS)
    private val innerY = FloatArray(INNER_DOTS)
    private var gInnerDotR = 0f
    private val tickX = FloatArray(4)
    private val tickY = FloatArray(4)
    private var gBarX = 0f
    private var gBarY = 0f

    private fun ensureGeometry() {
        if (geomW == width && geomH == height) return
        geomW = width
        geomH = height
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            gCols = 0
            gRows = 0
            return
        }
        val cols = (w / (18f * density)).toInt().coerceIn(12, 34)
        val rows = (h / (18f * density)).toInt().coerceIn(10, 24)
        val stepX = w / cols
        val stepY = h / rows
        gCols = cols
        gRows = rows
        gLatR = min(stepX, stepY) * 0.14f
        val cx = w / 2f
        val cy = h / 2f
        gCx = cx
        gCy = cy
        val size = min(w, h)
        gSize = size
        gMaxDiag = (hypot(w, h) * 0.5f).coerceAtLeast(1f)
        val centerNormScale = (size * 0.55f).coerceAtLeast(1f)

        val lx = FloatArray(cols) { c -> (c + 0.5f) * stepX }
        val ly = FloatArray(rows) { r -> (r + 0.5f) * stepY }
        latticeX = lx
        latticeY = ly
        val dists = FloatArray(rows * cols)
        for (r in 0 until rows) {
            val dy = ly[r] - cy
            val rowOff = r * cols
            for (c in 0 until cols) {
                val dx = lx[c] - cx
                dists[rowOff + c] = hypot(dx, dy) / centerNormScale
            }
        }
        latticeDistCenterNorm = dists

        val outerR = size * 0.40f
        gOuterDotR = (size * 0.017f).coerceAtLeast(2.2f * density)
        for (i in 0 until OUTER_DOTS) {
            outerX[i] = cx + outerR * COS_48[i]
            outerY[i] = cy + outerR * SIN_48[i]
        }
        val innerR = size * 0.23f
        gInnerDotR = gOuterDotR * 0.85f
        for (i in 0 until INNER_DOTS) {
            innerX[i] = cx + innerR * COS_24[i]
            innerY[i] = cy + innerR * SIN_24[i]
        }
        val tickR = outerR + gOuterDotR * 3.1f
        for (cardinal in 0 until 4) {
            tickX[cardinal] = cx + tickR * COS_4[cardinal]
            tickY[cardinal] = cy + tickR * SIN_4[cardinal]
        }
        gBarX = cx + size * 0.32f
        gBarY = cy + size * 0.34f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureGeometry()
        val cols = gCols
        val rows = gRows
        if (cols <= 0 || rows <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val latR = gLatR
        val latBoostR = latR * 1.35f
        val maxDiag = gMaxDiag
        val lx = latticeX
        val ly = latticeY
        val dists = latticeDistCenterNorm
        val rx = rippleNx * w
        val ry = rippleNy * h
        val bandScale = rippleActive

        for (r in 0 until rows) {
            val py = ly[r]
            val rowOff = r * cols
            val dyRipple = py - ry
            for (c in 0 until cols) {
                val px = lx[c]
                var dotRadius = latR
                var paint = offPaint

                if (bandScale) {
                    val distNorm = hypot(px - rx, dyRipple) / maxDiag
                    val band = abs(distNorm - rippleRadius)
                    if (band < 0.11f) {
                        val boost = 1f - band / 0.11f
                        dotRadius = latR * (1f + 1.4f * boost)
                        paint = accentPaint
                    }
                } else {
                    val distCenter = dists[rowOff + c]
                    if (distCenter < 1.15f) {
                        val wave = 0.5f + 0.5f * sin(distCenter * 7f - phase)
                        if (wave > 0.78f) dotRadius = latBoostR
                    }
                }
                canvas.drawCircle(px, py, dotRadius, paint)
            }
        }

        // Concentric Glyph C-Arc (Outer hardware ring: 48 LEDs)
        val outerDotR = gOuterDotR
        val outerAccentR = outerDotR * 1.25f
        val outerGlowR = outerDotR * 1.65f
        val phaseOuter = phase * 1.4f
        val activeProgressIdx = ((timerProgress01 * OUTER_DOTS).toInt() - 1).coerceAtLeast(0)
        for (i in 0 until OUTER_DOTS) {
            if (OUTER_GAP_MASK[i]) continue
            val x = outerX[i]
            val y = outerY[i]

            val bandWave = 0.5f + 0.5f * sin(phaseOuter + i * 0.55f)
            val lit = if (isAudioActive) {
                bandWave > 0.35f
            } else if (timerRunning) {
                (i.toFloat() / OUTER_DOTS) <= timerProgress01
            } else {
                bandWave > 0.60f
            }
            val isAccent = (isAudioActive && bandWave > 0.84f) ||
                (!isAudioActive && timerRunning && i == activeProgressIdx) ||
                (!isAudioActive && !timerRunning && i % 12 == 0)

            val p = when {
                isAccent -> accentPaint
                lit -> onPaint
                else -> offPaint
            }
            if (effects.dotGlow && (lit || isAccent)) {
                glowPaint.color = if (isAccent) accentPaint.color else onPaint.color
                glowPaint.alpha = if (isAccent) 0x45 else 0x18
                canvas.drawCircle(x, y, outerGlowR, glowPaint)
            }
            canvas.drawCircle(x, y, if (isAccent) outerAccentR else outerDotR, p)
        }

        // Inner Equalizer / Telemetry Ring (24 LEDs)
        val innerDotR = gInnerDotR
        val innerDimR = innerDotR * 0.72f
        val phaseInner = phase * 1.8f
        for (i in 0 until INNER_DOTS) {
            val wave = 0.5f + 0.5f * cos(phaseInner - i * 0.65f)
            val lit = if (isAudioActive) wave > 0.42f else wave > 0.68f
            val p = if (isAudioActive && wave > 0.85f) accentPaint else if (lit) onPaint else offPaint
            canvas.drawCircle(innerX[i], innerY[i], if (lit) innerDotR else innerDimR, p)
        }

        // Cardinal calibration ticks around outer hardware bezel
        val tickDotR = outerDotR * 0.72f
        for (cardinal in 0 until 4) {
            canvas.drawCircle(tickX[cardinal], tickY[cardinal], tickDotR, if (cardinal == 0) accentPaint else onPaint)
        }

        // Bottom-right signature hardware dot + twin exclamation bar
        canvas.drawCircle(gBarX, gBarY, outerDotR * 1.3f, accentPaint)
        canvas.drawCircle(gBarX + outerDotR * 3.1f, gBarY, outerDotR * 0.85f, onPaint)
    }

    private companion object {
        const val OUTER_DOTS = 48
        const val INNER_DOTS = 24
        val OUTER_GAP_MASK = BooleanArray(OUTER_DOTS) { i ->
            val angleDeg = -90.0 + i * (360.0 / OUTER_DOTS)
            val normDeg = (angleDeg + 360.0) % 360.0
            normDeg in 32.0..52.0 || normDeg in 212.0..232.0
        }
        val COS_48 = FloatArray(OUTER_DOTS) { i -> cos(Math.toRadians(-90.0 + i * (360.0 / OUTER_DOTS))).toFloat() }
        val SIN_48 = FloatArray(OUTER_DOTS) { i -> sin(Math.toRadians(-90.0 + i * (360.0 / OUTER_DOTS))).toFloat() }
        val COS_24 = FloatArray(INNER_DOTS) { i -> cos(Math.toRadians(-90.0 + i * (360.0 / INNER_DOTS))).toFloat() }
        val SIN_24 = FloatArray(INNER_DOTS) { i -> sin(Math.toRadians(-90.0 + i * (360.0 / INNER_DOTS))).toFloat() }
        val COS_4 = FloatArray(4) { c -> cos(Math.toRadians(c * 90.0)).toFloat() }
        val SIN_4 = FloatArray(4) { c -> sin(Math.toRadians(c * 90.0)).toFloat() }
    }
}
