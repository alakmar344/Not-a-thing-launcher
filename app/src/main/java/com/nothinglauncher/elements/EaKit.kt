package com.nothinglauncher.elements

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

/** Small drawing / system helpers shared by the sensor & system elements. */
internal object EaKit {

    val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** Single background thread for short blocking queries (usage stats, storage…). */
    val io: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "ea-elements").apply { isDaemon = true } }
    }

    fun dp(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

    /**
     * Draws [count] dots evenly on a circle starting at [startDeg] (0° = 3 o'clock, -90° = top),
     * clockwise. The first [lit] dots use [on], the rest [off]. [paintFor] can override per dot.
     */
    fun drawRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        count: Int,
        lit: Int,
        dotR: Float,
        on: Paint,
        off: Paint,
        startDeg: Float = -90f,
        paintFor: ((index: Int, isLit: Boolean) -> Paint?)? = null
    ) {
        if (count <= 0) return
        for (i in 0 until count) {
            val a = Math.toRadians((startDeg + 360.0 * i / count))
            val x = cx + radius * cos(a).toFloat()
            val y = cy + radius * sin(a).toFloat()
            val isLit = i < lit
            val p = paintFor?.invoke(i, isLit) ?: if (isLit) on else off
            canvas.drawCircle(x, y, if (isLit) dotR else dotR * 0.85f, p)
        }
    }

    fun ellipsize(text: String, paint: TextPaint, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (paint.measureText(text) <= maxW) return text
        return TextUtils.ellipsize(text, paint, maxW, TextUtils.TruncateAt.END).toString()
    }

    /**
     * Draws [text] with its baseline at [baseline], shrinking the paint (down to 70 %) and then
     * ellipsizing so it never exceeds [maxW]. [align] is the x anchor: centre, left or right edge.
     */
    fun drawFitText(canvas: Canvas, text: String, x: Float, baseline: Float, maxW: Float, paint: TextPaint, align: Paint.Align = Paint.Align.CENTER) {
        if (maxW <= 0f || text.isEmpty()) return
        val orig = paint.textSize
        val origAlign = paint.textAlign
        var w = paint.measureText(text)
        if (w > maxW) {
            paint.textSize = (orig * maxW / w).coerceAtLeast(orig * 0.7f)
            w = paint.measureText(text)
        }
        val t = if (w > maxW) ellipsize(text, paint, maxW) else text
        paint.textAlign = align
        canvas.drawText(t, x, baseline, paint)
        paint.textAlign = origAlign
        paint.textSize = orig
    }

    /** Draws [text] dot-matrix centred horizontally on [cx] with top at [top], fitting [maxW]×[maxH]. */
    fun drawDotCentered(
        canvas: Canvas,
        text: String,
        cx: Float,
        top: Float,
        maxW: Float,
        maxH: Float,
        on: Paint,
        off: Paint? = null,
        accent: Paint? = null,
        accentChars: Set<Int> = emptySet()
    ): Float {
        val pitch = ElementUi.fitPitch(text, maxW, maxH)
        val w = ElementUi.dotTextWidth(text, pitch)
        ElementUi.drawDotText(canvas, text, cx - w / 2f, top, pitch, on, off, accent, accentChars)
        return pitch
    }

    /** Registers a receiver for system broadcasts; returns the current sticky intent if any. */
    fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter): Intent? = try {
        ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    } catch (e: Exception) {
        null
    }

    fun unregister(context: Context, receiver: BroadcastReceiver) {
        try { context.applicationContext.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    fun batterySticky(context: Context): Intent? = try {
        context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (e: Exception) {
        null
    }

    fun tap(v: View) {
        if (v.isHapticFeedbackEnabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Formats a byte count as GB with one decimal under 100 GB, e.g. "12.4". */
    fun gb(bytes: Long): String {
        val g = bytes / 1_000_000_000.0
        return if (g >= 100) g.toInt().toString() else String.format(java.util.Locale.US, "%.1f", g)
    }

    /** "3H 12M" / "42M" style duration (uppercase, dot-font friendly). */
    fun hm(ms: Long): Pair<Long, Long> {
        val totalMin = (ms / 60_000L).coerceAtLeast(0)
        return Pair(totalMin / 60, totalMin % 60)
    }
}

/** Shared label/paint bundle each element allocates once. */
internal class EaPaints(context: Context, accent: Int) {
    val on: Paint = ElementUi.dotOn(context)
    val off: Paint = ElementUi.dotOff(context)
    val accent: Paint = ElementUi.accent(accent)
    val label: TextPaint = ElementUi.labelPaint(context, 9f)
    val labelPrimary: TextPaint = ElementUi.labelPaint(context, 10f, primary = true)
    val small: TextPaint = ElementUi.labelPaint(context, 8f)

    fun setAccent(c: Int) {
        accent.color = c
    }
}
