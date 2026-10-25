package com.nothinglauncher.elements

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import com.nothinglauncher.DotFont
import com.nothinglauncher.R
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Analog clock built from dots: a 60-dot ring with 12 hour dots (12 o'clock in the accent),
 * hour and minute hands drawn as dotted lines and an optional travelling seconds dot.
 * On wide cards the weekday, day and month are shown next to the dial.
 */
class AnalogClockElement(context: Context) : ElementView(context) {

    private val d = resources.displayMetrics.density

    private val onPaint = ElementUi.dotOn(context)
    private val offPaint = ElementUi.dotOff(context)
    private val accentPaint = ElementUi.accent(accentColor)
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ElementUi.color(context, R.color.text_secondary) }
    private val labelPaint = ElementUi.labelPaint(context, 10f)
    private val labelPrimary = ElementUi.labelPaint(context, 11f, primary = true)

    private val dayLine = EcKit.DotLine()
    private var hour = 0
    private var minute = 0
    private var second = 0
    private var weekday = ""
    private var month = ""
    private var lastMinute = -1L
    private var ticking = false

    private val receiver = EcKit.TimeReceiver(
        context,
        arrayOf(
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED
        )
    ) { refresh() }

    private val ticker = object : Runnable {
        override fun run() {
            val cal = Calendar.getInstance()
            if (cal.timeInMillis / 60_000L != lastMinute) {
                refresh()
            } else if (showSeconds()) {
                second = cal.get(Calendar.SECOND)
                invalidate()
            }
            if (ticking) postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        setOnClickListener { if (isHostReady) EcKit.openAlarms(host, this) }
        refresh()
    }

    override fun onBound() {
        refresh()
    }

    override fun onLiveChanged(live: Boolean) {
        if (live) {
            receiver.register()
            ticking = true
            refresh()
            removeCallbacks(ticker)
            post(ticker)
        } else {
            receiver.unregister()
            ticking = false
            removeCallbacks(ticker)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        receiver.unregister()
        ticking = false
        removeCallbacks(ticker)
    }

    override fun onAccentChanged(color: Int) {
        accentPaint.color = color
        invalidate()
    }

    override fun onSpanChanged(spanX: Int, spanY: Int) {
        invalidate()
    }

    private fun showSeconds(): Boolean = isHostReady && host.prefs.showSeconds

    private fun refresh() {
        val cal = Calendar.getInstance()
        hour = cal.get(Calendar.HOUR_OF_DAY)
        minute = cal.get(Calendar.MINUTE)
        second = cal.get(Calendar.SECOND)
        lastMinute = cal.timeInMillis / 60_000L
        val locale = Locale.getDefault()
        weekday = EcKit.skeleton("EEEE").format(cal.time).uppercase(locale)
        month = EcKit.skeleton("MMMM").format(cal.time).uppercase(locale)
        dayLine.set(cal.get(Calendar.DAY_OF_MONTH).toString())
        val timeText = EcKit.skeleton(if (EcKit.is24(context)) "Hm" else "hm").format(cal.time)
        contentDescription = context.getString(R.string.ec_analog_cd, timeText)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val wide = w >= h * 1.7f && h >= 70f * d
        val dial = if (wide) min(h, w * 0.55f) else min(w, h)
        val radius = dial / 2f
        val cx = if (wide) radius else w / 2f
        val cy = h / 2f
        drawDial(canvas, cx, cy, radius)

        if (wide) drawDate(canvas, dial + 14f * d, w, h)
    }

    private fun drawDial(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val ring = radius * 0.88f
        val hourR = radius * 0.055f
        val tickR = radius * 0.022f
        val showSec = showSeconds()

        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90.0)
            val x = cx + (ring * cos(a)).toFloat()
            val y = cy + (ring * sin(a)).toFloat()
            when {
                i == 0 -> canvas.drawCircle(x, y, hourR * 1.3f, accentPaint)
                i % 5 == 0 -> canvas.drawCircle(x, y, hourR, onPaint)
                else -> canvas.drawCircle(x, y, tickR, offPaint)
            }
        }

        val minuteAngle = (minute + if (showSec) second / 60f else 0f) * 6f
        val hourAngle = ((hour % 12) + minute / 60f) * 30f
        val pitch = radius * 0.105f
        drawHand(canvas, cx, cy, hourAngle, radius * 0.46f, pitch, radius * 0.05f, onPaint)
        drawHand(canvas, cx, cy, minuteAngle, radius * 0.7f, pitch, radius * 0.04f, onPaint)

        if (showSec) {
            val a = Math.toRadians(second * 6.0 - 90.0)
            val x = cx + (ring * cos(a)).toFloat()
            val y = cy + (ring * sin(a)).toFloat()
            canvas.drawCircle(x, y, hourR * 0.9f, accentPaint)
            // A faint trail dot towards the centre makes the second hand readable.
            val inner = ring - pitch
            canvas.drawCircle(cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat(), tickR * 1.4f, handPaint)
        }
        canvas.drawCircle(cx, cy, radius * 0.065f, accentPaint)
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, angleDeg: Float, length: Float, pitch: Float, r: Float, paint: Paint) {
        if (pitch <= 0f) return
        val a = Math.toRadians(angleDeg - 90.0)
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        val n = floor(length / pitch).toInt()
        for (k in 1..n) {
            val dist = k * pitch
            canvas.drawCircle(cx + dx * dist, cy + dy * dist, r, paint)
        }
    }

    private fun drawDate(canvas: Canvas, x0: Float, w: Float, h: Float) {
        val availW = w - x0
        if (availW <= 24f * d) return
        val gap = 6f * d
        val lh = EcKit.lineHeight(labelPaint)
        val lhPrimary = EcKit.lineHeight(labelPrimary)
        val cols = dayLine.columns.coerceAtLeast(1)
        val pitch = min(min(availW / cols, (h - lh - lhPrimary - gap * 2) / DotFont.ROWS), 7f * d)
        if (pitch <= 0f) return
        val blockH = lh + gap + DotFont.ROWS * pitch + gap + lhPrimary
        var y = (h - blockH) / 2f
        canvas.drawText(EcKit.ellipsize(weekday, labelPaint, availW), x0, y + EcKit.ascent(labelPaint), labelPaint)
        y += lh + gap
        dayLine.draw(canvas, x0, y, pitch, onPaint)
        y += DotFont.ROWS * pitch + gap
        canvas.drawText(EcKit.ellipsize(month, labelPrimary, availW), x0, y + EcKit.ascent(labelPrimary), labelPrimary)
    }
}
