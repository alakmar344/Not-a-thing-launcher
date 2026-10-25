package com.nothinglauncher.elements

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import com.nothinglauncher.DotFont
import com.nothinglauncher.R
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * LED dot-matrix clock: HH:MM with a blinking accent colon, an optional 60-dot seconds ticker,
 * the localized date and the next system alarm — each shown only when the card is big enough.
 */
class ClockElement(context: Context) : ElementView(context) {

    private val d = resources.displayMetrics.density

    private val onPaint = ElementUi.dotOn(context)
    private val offPaint = ElementUi.dotOff(context)
    private val accentPaint = ElementUi.accent(accentColor)
    private val pastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ElementUi.color(context, R.color.text_secondary) }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ElementUi.color(context, R.color.hairline_strong) }
    private val datePaint = ElementUi.labelPaint(context, 11f, primary = true)
    private val subPaint = ElementUi.labelPaint(context, 10f)

    private val labelH = EcKit.lineHeight(datePaint)
    private val labelAscent = EcKit.ascent(datePaint)
    private val subAscent = EcKit.ascent(subPaint)

    private val time = EcKit.DotLine()
    private var is24 = true
    private var amPm = ""
    private var dateText = ""
    private var alarmText: String? = null
    private var second = 0
    private var lastMinute = -1L
    private var colonVisible = true
    private var ticking = false

    private val receiver = EcKit.TimeReceiver(
        context,
        arrayOf(
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED
        )
    ) { refresh() }

    private val ticker = object : Runnable {
        override fun run() {
            tickSecond()
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
            colonVisible = true
            invalidate()
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
        is24 = EcKit.is24(context)
        val cal = Calendar.getInstance()
        applyTime(cal)
        dateText = EcKit.skeleton("EEEEdMMMM").format(cal.time).uppercase(Locale.getDefault())
        alarmText = nextAlarm()
        updateDescription()
        invalidate()
    }

    private fun applyTime(cal: Calendar) {
        time.set(EcKit.hm(cal, is24))
        amPm = if (is24) "" else EcKit.amPm(cal).uppercase(Locale.getDefault())
        second = cal.get(Calendar.SECOND)
        lastMinute = cal.timeInMillis / 60_000L
        colonVisible = !ticking || second % 2 == 0
    }

    private fun tickSecond() {
        val cal = Calendar.getInstance()
        if (cal.timeInMillis / 60_000L != lastMinute) {
            refresh()
        } else {
            second = cal.get(Calendar.SECOND)
            colonVisible = second % 2 == 0
            invalidate()
        }
    }

    private fun nextAlarm(): String? {
        val am = context.getSystemService(AlarmManager::class.java) ?: return null
        val info = try {
            am.nextAlarmClock
        } catch (e: Exception) {
            null
        } ?: return null
        val t = info.triggerTime
        val now = System.currentTimeMillis()
        if (t < now) return null
        val skeleton = if (t - now < 24L * 3_600_000L) {
            if (is24) "Hm" else "hm"
        } else {
            if (is24) "EEEHm" else "EEEhm"
        }
        val formatted = EcKit.skeleton(skeleton).format(Date(t))
        return context.getString(R.string.ec_clock_alarm, formatted).uppercase(Locale.getDefault())
    }

    private fun updateDescription() {
        val t = if (amPm.isEmpty()) time.text else "${time.text} $amPm"
        val base = context.getString(R.string.ec_clock_cd, t, dateText)
        val alarm = alarmText
        contentDescription = if (alarm != null) "$base. $alarm" else base
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || time.columns == 0) return

        val gap = 6f * d
        val tall = h >= 118f * d
        val medium = !tall && h >= 84f * d
        val tickerH = 8f * d
        val showTicker = showSeconds() && (tall || h >= 104f * d)

        var top = 0f
        var bottom = h

        val alarm = alarmText
        if (tall) {
            // Top row: date (left) and next alarm (right) — alarm drops to its own row if needed.
            val alarmW = if (alarm != null) subPaint.measureText(alarm) else 0f
            val dateW = datePaint.measureText(dateText)
            val sameRow = alarm == null || dateW + gap * 2 + alarmW <= w
            val dateAvail = if (alarm != null && sameRow) w - alarmW - gap * 2 else w
            canvas.drawText(EcKit.ellipsize(dateText, datePaint, dateAvail), 0f, labelAscent, datePaint)
            top = labelH + gap
            if (alarm != null) {
                if (sameRow) {
                    canvas.drawText(alarm, w - alarmW, labelAscent, subPaint)
                } else if (h >= 150f * d) {
                    val a = EcKit.ellipsize(alarm, subPaint, w)
                    canvas.drawText(a, 0f, top + subAscent, subPaint)
                    top += EcKit.lineHeight(subPaint) + gap
                }
            }
        } else if (medium) {
            val y = h - labelH
            canvas.drawText(EcKit.ellipsize(dateText, datePaint, w), 0f, y + labelAscent, datePaint)
            bottom = y - gap
        }

        if (showTicker) {
            val ty = bottom - tickerH
            drawTicker(canvas, w, ty, tickerH)
            bottom = ty - gap
        }

        // Dot-matrix time with optional AM/PM marker to its right.
        val areaH = bottom - top
        if (areaH <= 0f) return
        val amW = if (amPm.isNotEmpty()) subPaint.measureText(amPm) + gap else 0f
        val showAm = amW > 0f && amW < w * 0.3f
        val availW = w - if (showAm) amW else 0f
        val pitch = min(availW / time.columns, areaH / DotFont.ROWS)
        if (pitch <= 0f) return
        val matrixW = time.width(pitch)
        val groupW = matrixW + if (showAm) amW else 0f
        val x = (w - groupW) / 2f
        val y = top + (areaH - DotFont.ROWS * pitch) / 2f
        time.draw(canvas, x, y, pitch, onPaint, offPaint, accentPaint, time.text.indexOf(':'), colonVisible)
        if (showAm) canvas.drawText(amPm, x + matrixW + gap, y + subAscent, subPaint)
    }

    private fun drawTicker(canvas: Canvas, w: Float, y: Float, h: Float) {
        val count = 60
        val pitch = w / count
        val r = min(pitch * 0.3f, h / 2f)
        val cy = y + h / 2f
        for (i in 0 until count) {
            val cx = (i + 0.5f) * pitch
            val paint = when {
                i == second -> accentPaint
                i < second -> pastPaint
                i % 5 == 0 -> markPaint
                else -> offPaint
            }
            canvas.drawCircle(cx, cy, if (i == second) r * 1.25f else r, paint)
        }
    }
}
