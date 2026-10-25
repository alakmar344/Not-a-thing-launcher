package com.nothinglauncher.elements

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.Settings
import com.nothinglauncher.R
import java.util.Locale

/**
 * Real device vitals readable by apps: RAM in use (ActivityManager), battery temperature and
 * uptime. CPU load is not exposed to apps on modern Android, so it is deliberately not shown.
 */
class SystemMonitorElement(context: Context) : ElementView(context) {

    private val p = EaPaints(context, accentColor)
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val memInfo = ActivityManager.MemoryInfo()
    private val rowPaint = ElementUi.labelPaint(context, 9f, primary = true)

    private var totalMem = -1L
    private var availMem = -1L
    private var tempC = Float.NaN
    private var uptimeMs = 0L

    private val ticker = object : Runnable {
        override fun run() {
            sample()
            EaKit.main.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(0)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (isHostReady) {
                EaKit.tap(this)
                host.startActivitySafely(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), this)
            }
        }
    }

    override fun onBound() {
        sample()
    }

    override fun onLiveChanged(live: Boolean) {
        EaKit.main.removeCallbacks(ticker)
        if (live) EaKit.main.post(ticker)
    }

    override fun onDetachedFromWindow() {
        EaKit.main.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun sample() {
        try {
            am?.getMemoryInfo(memInfo)
            if (memInfo.totalMem > 0) {
                totalMem = memInfo.totalMem
                availMem = memInfo.availMem.coerceIn(0L, memInfo.totalMem)
            }
        } catch (_: Exception) {}
        val battery = EaKit.batterySticky(context)
        val t = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        tempC = if (t != Int.MIN_VALUE && t > -500) t / 10f else Float.NaN
        uptimeMs = SystemClock.elapsedRealtime()
        updateDescription()
        invalidate()
    }

    private fun ramPct(): Int = if (totalMem > 0) (((totalMem - availMem) * 100) / totalMem).toInt().coerceIn(0, 100) else 0

    private fun ramText(): String = context.getString(R.string.ea_ram_used_of, EaKit.gb(totalMem - availMem), EaKit.gb(totalMem))

    private fun tempText(): String = String.format(Locale.US, "%.1f°C", tempC)

    private fun uptimeText(): String {
        val totalMin = uptimeMs / 60_000L
        val d = totalMin / (60 * 24)
        val h = (totalMin / 60) % 24
        val m = totalMin % 60
        return if (d > 0) context.getString(R.string.ea_uptime_dh, d, h) else context.getString(R.string.ea_duration_hm, h, m)
    }

    private fun updateDescription() {
        val parts = ArrayList<String>()
        if (totalMem > 0) parts += context.getString(R.string.ea_sys_cd_ram, ramPct(), ramText())
        if (!tempC.isNaN()) parts += context.getString(R.string.ea_sys_cd_temp, tempText())
        parts += context.getString(R.string.ea_sys_cd_uptime, uptimeText())
        contentDescription = parts.joinToString(", ")
    }

    override fun onAccentChanged(color: Int) {
        p.setAccent(color)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val headerH = if (h > EaKit.dp(context, 90f)) p.label.textSize * 1.9f else 0f
        if (headerH > 0) ElementUi.drawHeader(canvas, 0f, p.label.textSize, context.getString(R.string.ea_system), p.accent, p.label)
        var y = headerH
        val known = totalMem > 0
        val pct = ramPct()

        // Info rows (bottom-up) that fit after the gauge.
        val rowH = rowPaint.textSize * 1.75f
        val rows = ArrayList<Pair<String, String>>()
        if (known) rows += context.getString(R.string.ea_ram) to ramText()
        if (!tempC.isNaN()) rows += context.getString(R.string.ea_temp) to tempText()
        rows += context.getString(R.string.ea_uptime) to uptimeText()

        val barDots = 20
        val barPitch = w / barDots
        val minGauge = EaKit.dp(context, 22f)
        val available = h - y - barPitch - EaKit.dp(context, 8f)
        val maxRows = ((available - minGauge) / rowH).toInt().coerceIn(0, rows.size)
        val rowsH = maxRows * rowH
        val gaugeH = (available - rowsH).coerceAtLeast(0f)

        // Big RAM %: "62%" dot-matrix with a small RAM label.
        val pctText = if (known) "$pct%" else "--"
        val labelW = p.label.measureText(context.getString(R.string.ea_ram).uppercase()) + EaKit.dp(context, 8f)
        val pitch = ElementUi.fitPitch(pctText, w - labelW, minOf(gaugeH, EaKit.dp(context, 44f)))
        val dotH = pitch * 7
        val numTop = y + (gaugeH - dotH) / 2f
        val numW = ElementUi.drawDotText(canvas, pctText, 0f, numTop, pitch, p.on, accentPaint = p.accent,
            accentChars = if (known && pct >= 85) pctText.indices.toSet() else emptySet())
        canvas.drawText(context.getString(R.string.ea_ram).uppercase(), numW + EaKit.dp(context, 8f), numTop + dotH, p.label)
        y += gaugeH + EaKit.dp(context, 4f)

        // Dot bar of RAM in use.
        val lit = if (known) (pct * barDots + 50) / 100 else 0
        val r = barPitch * 0.3f
        for (i in 0 until barDots) {
            val on = i < lit
            canvas.drawCircle((i + 0.5f) * barPitch, y + barPitch / 2f, if (on) r else r * 0.8f,
                if (on) (if (i == lit - 1) p.accent else p.on) else p.off)
        }
        y += barPitch + EaKit.dp(context, 4f)

        for (i in 0 until maxRows) {
            val (k, v) = rows[i]
            val base = y + rowH * i + rowPaint.textSize
            val kk = k.uppercase()
            canvas.drawText(kk, 0f, base, p.label)
            val kw = p.label.measureText(kk) + EaKit.dp(context, 8f)
            EaKit.drawFitText(canvas, v.uppercase(), w, base, w - kw, rowPaint, Paint.Align.RIGHT)
        }
    }

    companion object {
        private const val REFRESH_MS = 2_000L
    }
}
