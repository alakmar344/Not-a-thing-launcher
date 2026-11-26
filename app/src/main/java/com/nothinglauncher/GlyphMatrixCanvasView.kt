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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Background PCB-inspired dot lattice
        val cols = (w / (18f * density)).toInt().coerceIn(12, 34)
        val rows = (h / (18f * density)).toInt().coerceIn(10, 24)
        val stepX = w / cols
        val stepY = h / rows
        val latR = min(stepX, stepY) * 0.14f

        val cx = w / 2f
        val cy = h / 2f
        val size = min(w, h)
        val maxDiag = hypot(w, h) * 0.5f

        for (r in 0 until rows) {
            val py = (r + 0.5f) * stepY
            for (c in 0 until cols) {
                val px = (c + 0.5f) * stepX
                var dotRadius = latR
                var paint = offPaint

                if (rippleActive) {
                    val rx = rippleNx * w
                    val ry = rippleNy * h
                    val distNorm = hypot(px - rx, py - ry) / maxDiag.coerceAtLeast(1f)
                    val band = abs(distNorm - rippleRadius)
                    if (band < 0.11f) {
                        val boost = 1f - band / 0.11f
                        dotRadius = latR * (1f + 1.4f * boost)
                        paint = accentPaint
                    }
                } else {
                    val distCenter = hypot(px - cx, py - cy) / (size * 0.55f).coerceAtLeast(1f)
                    val wave = 0.5f + 0.5f * sin(distCenter * 7f - phase)
                    if (wave > 0.78f && distCenter < 1.15f) {
                        dotRadius = latR * 1.35f
                    }
                }
                canvas.drawCircle(px, py, dotRadius, paint)
            }
        }

        // Concentric Glyph C-Arc (Outer hardware ring: 48 LEDs)
        val outerR = size * 0.40f
        val outerDots = 48
        val outerDotR = (size * 0.017f).coerceAtLeast(2.2f * density)
        for (i in 0 until outerDots) {
            val angleDeg = -90.0 + i * (360.0 / outerDots)
            // Classic Nothing C-ring gaps at top-right & bottom-left
            val normDeg = (angleDeg + 360.0) % 360.0
            if (normDeg in 32.0..52.0 || normDeg in 212.0..232.0) continue

            val rad = Math.toRadians(angleDeg)
            val x = cx + (outerR * cos(rad)).toFloat()
            val y = cy + (outerR * sin(rad)).toFloat()

            val bandWave = 0.5f + 0.5f * sin(phase * 1.4f + i * 0.55f)
            val lit = if (isAudioActive) {
                bandWave > 0.35f
            } else if (timerRunning) {
                (i.toFloat() / outerDots) <= timerProgress01
            } else {
                bandWave > 0.60f
            }
            val isAccent = (isAudioActive && bandWave > 0.84f) ||
                (!isAudioActive && timerRunning && i == ((timerProgress01 * outerDots).toInt() - 1).coerceAtLeast(0)) ||
                (!isAudioActive && !timerRunning && i % 12 == 0)

            val p = when {
                isAccent -> accentPaint
                lit -> onPaint
                else -> offPaint
            }
            if (effects.dotGlow && (lit || isAccent)) {
                glowPaint.color = if (isAccent) accentPaint.color else onPaint.color
                glowPaint.alpha = if (isAccent) 0x45 else 0x18
                canvas.drawCircle(x, y, outerDotR * 1.65f, glowPaint)
            }
            canvas.drawCircle(x, y, if (isAccent) outerDotR * 1.25f else outerDotR, p)
        }

        // Inner Equalizer / Telemetry Ring (24 LEDs)
        val innerR = size * 0.23f
        val innerDots = 24
        val innerDotR = outerDotR * 0.85f
        for (i in 0 until innerDots) {
            val rad = Math.toRadians(-90.0 + i * (360.0 / innerDots))
            val x = cx + (innerR * cos(rad)).toFloat()
            val y = cy + (innerR * sin(rad)).toFloat()
            val wave = 0.5f + 0.5f * cos(phase * 1.8f - i * 0.65f)
            val lit = if (isAudioActive) wave > 0.42f else wave > 0.68f
            val p = if (isAudioActive && wave > 0.85f) accentPaint else if (lit) onPaint else offPaint
            canvas.drawCircle(x, y, if (lit) innerDotR else innerDotR * 0.72f, p)
        }

        // Bottom-right signature hardware dot + exclamation bar
        val barX = cx + size * 0.32f
        val barY = cy + size * 0.34f
        canvas.drawCircle(barX, barY, outerDotR * 1.3f, accentPaint)
    }
}
