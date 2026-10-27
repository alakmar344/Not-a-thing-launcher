package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.random.Random

/**
 * A Nothing OS-inspired Voice Memo Tile.
 * Features a live recording timer in [DotFont] and pulsating dot wave visualizer.
 */
class VoiceRecorderDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_on)
    }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_off)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nothing_red)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 10f * resources.displayMetrics.scaledDensity
        letterSpacing = 0.08f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (e: Exception) { null }
    }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    var isRecording: Boolean = false
        private set

    private var recordStartTime: Long = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (isRecording) {
                invalidate()
                postDelayed(this, 120)
            }
        }
    }

    /** Host-driven state: pass the real recording start so a re-bind keeps the timer. */
    fun setRecording(recording: Boolean, startedAtElapsed: Long = SystemClock.elapsedRealtime()) {
        isRecording = recording
        if (recording) {
            recordStartTime = if (startedAtElapsed > 0L) startedAtElapsed else SystemClock.elapsedRealtime()
            removeCallbacks(ticker)
            post(ticker)
        } else {
            removeCallbacks(ticker)
        }
        contentDescription = if (recording) {
            context.getString(R.string.recorder_recording)
        } else {
            context.getString(R.string.recorder_idle)
        }
        invalidate()
    }

    fun toggle(): Boolean {
        setRecording(!isRecording)
        return isRecording
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Keep the timer moving after a detach/re-attach while a recording is active.
        if (isRecording && windowVisibility == VISIBLE && isShown) {
            removeCallbacks(ticker)
            post(ticker)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            removeCallbacks(ticker)
        } else if (isRecording && isShown) {
            removeCallbacks(ticker)
            post(ticker)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            removeCallbacks(ticker)
        } else if (isRecording && isShown) {
            removeCallbacks(ticker)
            post(ticker)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) {
            removeCallbacks(ticker)
        } else if (isRecording && isShown) {
            removeCallbacks(ticker)
            post(ticker)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val elapsedSec = if (isRecording) (SystemClock.elapsedRealtime() - recordStartTime) / 1000 else 0L
        val min = elapsedSec / 60
        val sec = elapsedSec % 60
        val timeStr = String.format(java.util.Locale.US, "%02d:%02d", min, sec) // DotFont is ASCII-only

        val dotPitch = 3.8f * density
        val dotRadius = 1.5f * density

        // Draw timer in DotFont
        var startX = 0f
        val startY = 4f * density

        for (ch in timeStr) {
            val glyph = DotFont.glyphFor(ch)
            val cols = glyph.firstOrNull()?.length ?: 0
            for (r in 0 until DotFont.ROWS) {
                val rowStr = glyph.getOrNull(r) ?: ""
                for (c in 0 until cols) {
                    val isLit = rowStr.getOrNull(c) == '#'
                    val cx = startX + (c * dotPitch)
                    val cy = startY + (r * dotPitch)
                    val paint = when {
                        isLit && ch == ':' && isRecording -> accentPaint
                        isLit -> onPaint
                        else -> offPaint
                    }
                    canvas.drawCircle(cx, cy, dotRadius, paint)
                }
            }
            startX += (cols * dotPitch) + (dotPitch * 0.8f)
        }

        // Draw audio wave dots next to timer
        val waveStartX = startX + 16f * density
        val waveBars = 8
        for (b in 0 until waveBars) {
            val bx = waveStartX + (b * 7f * density)
            val heightDots = if (isRecording) Random.nextInt(1, 5) else 1
            for (dr in 0 until 4) {
                val dy = startY + (DotFont.ROWS * dotPitch) - (dr * 6f * density)
                val paint = if (dr < heightDots) (if (isRecording) accentPaint else onPaint) else offPaint
                canvas.drawCircle(bx, dy, dotRadius, paint)
            }
        }

        val statusText = if (isRecording) {
            "${context.getString(R.string.recorder_recording)} · ${context.getString(R.string.recorder_tap_save)}"
        } else {
            "READY · ${context.getString(R.string.recorder_idle)}"
        }
        canvas.drawText(statusText, 0f, h - 2f * density, labelPaint)
    }
}
