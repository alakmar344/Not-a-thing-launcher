package com.nothinglauncher.elements

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.provider.CalendarContract
import android.view.MotionEvent
import com.nothinglauncher.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** "Battery & Date": a 40-dot battery ring beside today's date. */
class BatteryDateElement(context: Context) : ElementView(context) {

    private val p = EaPaints(context, accentColor)
    private val weekdayPaint = ElementUi.labelPaint(context, 10f, primary = true)

    private var level = -1
    private var charging = false
    private var dayOfMonth = 0
    private var weekday = ""
    private var month = ""

    private val batteryRect = RectF()
    private val dateRect = RectF()
    private var showBattery = true
    private var showDate = true
    private var stacked = false
    private var downX = 0f
    private var downY = 0f

    private var batteryReg = false
    private var timeReg = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent != null) applyBattery(intent)
        }
    }
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            updateDate()
        }
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
        EaKit.batterySticky(context)?.let { applyBattery(it) }
        updateDate()
    }

    private fun applyBattery(intent: Intent) {
        val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val newLevel = if (l >= 0 && s > 0) (l * 100f / s).toInt().coerceIn(0, 100) else level
        val newCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
        if (newLevel != level || newCharging != charging) {
            level = newLevel
            charging = newCharging
            updateDescription()
            invalidate()
        }
    }

    private fun updateDate() {
        val cal = Calendar.getInstance()
        val loc = Locale.getDefault()
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val wd = SimpleDateFormat("EEEE", loc).format(cal.time)
        val m = SimpleDateFormat("LLLL yyyy", loc).format(cal.time)
        if (d != dayOfMonth || wd != weekday || m != month) {
            dayOfMonth = d
            weekday = wd
            month = m
            updateDescription()
            invalidate()
        }
    }

    override fun onLiveChanged(live: Boolean) {
        if (live) {
            if (!batteryReg) {
                EaKit.register(context, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { applyBattery(it) }
                batteryReg = true
            }
            if (!timeReg) {
                val f = IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_DATE_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_LOCALE_CHANGED)
                }
                EaKit.register(context, timeReceiver, f)
                timeReg = true
            }
            updateDate()
        } else {
            unregisterAll()
        }
    }

    private fun unregisterAll() {
        if (batteryReg) { EaKit.unregister(context, batteryReceiver); batteryReg = false }
        if (timeReg) { EaKit.unregister(context, timeReceiver); timeReg = false }
    }

    override fun onDetachedFromWindow() {
        unregisterAll()
        super.onDetachedFromWindow()
    }

    override fun onAccentChanged(color: Int) {
        p.setAccent(color)
        invalidate()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x
            downY = ev.y
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun onTap() {
        if (!isHostReady) return
        EaKit.tap(this)
        val onBattery = when {
            showBattery && !showDate -> true
            showDate && !showBattery -> false
            else -> {
                // Pick the nearer half so taps in the gap still do something sensible.
                val db = distance(batteryRect, downX, downY)
                val dd = distance(dateRect, downX, downY)
                db <= dd
            }
        }
        if (onBattery) BatteryGaugeElement.openBatterySettings(host, this) else openCalendar()
    }

    private fun distance(r: RectF, x: Float, y: Float): Float {
        val dx = maxOf(r.left - x, 0f, x - r.right)
        val dy = maxOf(r.top - y, 0f, y - r.bottom)
        return dx * dx + dy * dy
    }

    private fun openCalendar() {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            .appendPath(System.currentTimeMillis().toString()).build()
        try {
            host.activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            return
        } catch (_: Exception) {}
        host.startActivitySafely(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), this)
    }

    private fun updateDescription() {
        val date = "$weekday $dayOfMonth $month"
        contentDescription = if (level >= 0) context.getString(R.string.ea_battery_date_cd, level, date) else date
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val gap = EaKit.dp(context, 12f)
        val minHalf = EaKit.dp(context, 48f)
        stacked = false
        when {
            w >= h * 1.3f && w / 2f - gap / 2f >= minHalf -> {
                // Side by side: ring on the left, date on the right.
                val ringW = minOf(h, w * 0.5f - gap / 2f)
                batteryRect.set(0f, 0f, ringW, h)
                dateRect.set(ringW + gap, 0f, w, h)
                showBattery = true; showDate = true
            }
            h / 2f - gap / 2f >= minHalf -> {
                val ringH = minOf(w, h * 0.5f - gap / 2f)
                batteryRect.set(0f, 0f, w, ringH)
                dateRect.set(0f, ringH + gap, w, h)
                showBattery = true; showDate = true
                stacked = true
            }
            else -> {
                // Only room for one half: keep the battery ring (the element's headline).
                batteryRect.set(0f, 0f, w, h)
                dateRect.setEmpty()
                showBattery = true; showDate = false
            }
        }
        if (showBattery) drawBattery(canvas, batteryRect)
        if (showDate) drawDate(canvas, dateRect)
    }

    private fun drawBattery(canvas: Canvas, r: RectF) {
        val size = minOf(r.width(), r.height())
        val cx = r.centerX()
        val cy = r.centerY()
        val dotR = (size / 46f).coerceAtLeast(EaKit.dp(context, 1.1f))
        val radius = size / 2f - dotR * 1.5f
        val count = 40
        val low = level in 0..15
        val lit = if (level >= 0) (level * count + 50) / 100 else 0
        EaKit.drawRing(canvas, cx, cy, radius, count, lit, dotR, p.on, p.off) { i, isLit ->
            if (isLit && (low || (charging && i == lit - 1))) p.accent else null
        }
        val text = if (level >= 0) "$level" else "--"
        val inner = (radius - dotR * 2.5f) * 2f * 0.78f
        if (inner <= 0) return
        val showLabel = inner > p.small.textSize * 4.5f
        val maxH = inner * 0.36f
        val pitch = ElementUi.fitPitch(text, inner, maxH)
        val dotH = pitch * 7
        val labelGap = p.small.textSize * 1.4f
        val top = cy - (dotH + if (showLabel) labelGap else 0f) / 2f
        EaKit.drawDotCentered(canvas, text, cx, top, inner, maxH, p.on, accent = p.accent,
            accentChars = if (low) text.indices.toSet() else emptySet())
        if (showLabel) {
            val label = context.getString(if (charging) R.string.ea_charging else R.string.ea_battery_pct).uppercase()
            EaKit.drawFitText(canvas, label, cx, top + dotH + labelGap, inner, p.small)
        }
    }

    private fun drawDate(canvas: Canvas, r: RectF) {
        val maxW = r.width()
        val align = if (stacked) Paint.Align.CENTER else Paint.Align.LEFT
        val x = if (stacked) r.centerX() else r.left
        val wd = weekday.uppercase(Locale.getDefault())
        val mo = month.uppercase(Locale.getDefault())
        val dayText = dayOfMonth.toString()
        val labelH = weekdayPaint.textSize * 1.6f
        val showMonth = r.height() > labelH * 2 + EaKit.dp(context, 20f)
        val dotMaxH = (r.height() - labelH - if (showMonth) labelH else 0f).coerceAtLeast(EaKit.dp(context, 10f))
        val pitch = ElementUi.fitPitch(dayText, maxW, minOf(dotMaxH, EaKit.dp(context, 64f)))
        val dotH = pitch * 7
        val blockH = labelH + dotH + if (showMonth) labelH else 0f
        var y = r.top + (r.height() - blockH) / 2f
        // Weekday in accent (the "today" marker), day number big, month underneath.
        val origColor = weekdayPaint.color
        weekdayPaint.color = p.accent.color
        EaKit.drawFitText(canvas, wd, x, y + weekdayPaint.textSize, maxW, weekdayPaint, align)
        weekdayPaint.color = origColor
        y += labelH
        val dayX = if (stacked) x - ElementUi.dotTextWidth(dayText, pitch) / 2f else x
        ElementUi.drawDotText(canvas, dayText, dayX, y, pitch, p.on)
        y += dotH
        if (showMonth) EaKit.drawFitText(canvas, mo, x, y + labelH * 0.85f, maxW, p.label, align)
    }
}
