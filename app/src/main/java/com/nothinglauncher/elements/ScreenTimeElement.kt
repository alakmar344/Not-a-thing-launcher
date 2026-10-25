package com.nothinglauncher.elements

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.provider.Settings
import com.nothinglauncher.R
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/**
 * Today's foreground screen time computed from raw usage events (resume/pause pairs), with the
 * top apps and a 24-hour dot histogram. Requires usage access.
 */
class ScreenTimeElement(context: Context) : ElementView(context) {

    private class AppUse(val label: String, val ms: Long)
    private class Snapshot(val totalMs: Long, val hourly: LongArray, val top: List<AppUse>, val currentHour: Int)

    private val p = EaPaints(context, accentColor)
    private val msgPaint = ElementUi.labelPaint(context, 10f, primary = true)
    private val appPaint = ElementUi.labelPaint(context, 9f, primary = true)
    private val secondaryColor = ElementUi.color(context, R.color.text_secondary)

    private var snapshot: Snapshot? = null
    private var allowed = false
    private val generation = AtomicInteger(0)
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            EaKit.main.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
    }

    override fun onBound() {
        allowed = safeHasAccess()
        updateDescription()
    }

    private fun safeHasAccess(): Boolean = isHostReady && try { host.hasUsageAccess() } catch (e: Exception) { false }

    override fun onLiveChanged(live: Boolean) {
        EaKit.main.removeCallbacks(refresher)
        if (live) {
            EaKit.main.post(refresher)
        } else {
            generation.incrementAndGet()
        }
    }

    override fun onDetachedFromWindow() {
        EaKit.main.removeCallbacks(refresher)
        generation.incrementAndGet()
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        val nowAllowed = safeHasAccess()
        if (nowAllowed != allowed) {
            allowed = nowAllowed
            if (!allowed) snapshot = null
            updateDescription()
            invalidate()
        }
        if (!allowed) return
        val gen = generation.incrementAndGet()
        val app = context.applicationContext
        try {
            EaKit.io.execute {
                val result = try { compute(app) } catch (e: Exception) { null }
                EaKit.main.post {
                    if (gen != generation.get() || result == null) return@post
                    snapshot = result
                    updateDescription()
                    invalidate()
                }
            }
        } catch (_: Exception) {}
    }

    /** Runs on the background thread. */
    private fun compute(ctx: Context): Snapshot? {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val midnight = cal.timeInMillis
        val now = System.currentTimeMillis()
        val hourStarts = LongArray(25) { h ->
            val c = cal.clone() as Calendar
            c.add(Calendar.HOUR_OF_DAY, h)
            c.timeInMillis
        }
        val own = ctx.packageName
        val totals = HashMap<String, Long>()
        val hourly = LongArray(24)
        val sessionStart = HashMap<String, Long>()          // package -> foreground since
        val resumed = HashMap<String, MutableSet<String>>() // package -> resumed activity classes
        val seen = HashSet<String>()

        fun addSpan(pkg: String, start: Long, end: Long) {
            val s = maxOf(start, midnight)
            val e = minOf(end, now)
            if (e <= s || pkg == own) return
            totals[pkg] = (totals[pkg] ?: 0L) + (e - s)
            for (h in 0 until 24) {
                val hs = hourStarts[h]
                val he = hourStarts[h + 1]
                val overlap = minOf(e, he) - maxOf(s, hs)
                if (overlap > 0) hourly[h] += overlap
            }
        }

        fun closeAll(ts: Long) {
            for ((pkg, start) in sessionStart) addSpan(pkg, start, ts)
            sessionStart.clear()
            resumed.clear()
        }

        val events = usm.queryEvents(midnight, now) ?: return null
        val ev = UsageEvents.Event()
        val screenOff = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) UsageEvents.Event.SCREEN_NON_INTERACTIVE else -1
        @Suppress("DEPRECATION")
        val resumeType = UsageEvents.Event.MOVE_TO_FOREGROUND   // == ACTIVITY_RESUMED
        @Suppress("DEPRECATION")
        val pauseType = UsageEvents.Event.MOVE_TO_BACKGROUND    // == ACTIVITY_PAUSED
        val stopType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) UsageEvents.Event.ACTIVITY_STOPPED else -1

        while (events.hasNextEvent()) {
            if (!events.getNextEvent(ev)) break
            val pkg = ev.packageName ?: continue
            val ts = ev.timeStamp
            val type = ev.eventType
            val cls = ev.className ?: ""
            when {
                type == resumeType -> {
                    val set = resumed.getOrPut(pkg) { HashSet() }
                    if (set.isEmpty()) sessionStart[pkg] = ts
                    set.add(cls)
                    seen.add(pkg)
                }
                type == pauseType || type == stopType -> {
                    val set = resumed[pkg]
                    if (set != null && set.remove(cls)) {
                        if (set.isEmpty()) {
                            sessionStart.remove(pkg)?.let { addSpan(pkg, it, ts) }
                            resumed.remove(pkg)
                        }
                    } else if (type == pauseType && pkg !in seen) {
                        // Resumed before midnight and still in front at the day's start.
                        addSpan(pkg, midnight, ts)
                    }
                    seen.add(pkg)
                }
                type == screenOff -> closeAll(ts)
            }
        }
        closeAll(now)

        val pm = ctx.packageManager
        val top = totals.entries.sortedByDescending { it.value }.take(3).map { (pkg, ms) ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg.substringAfterLast('.')
            }
            AppUse(label, ms)
        }
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return Snapshot(totals.values.sum(), hourly, top, currentHour)
    }

    private fun onTap() {
        if (!isHostReady) return
        EaKit.tap(this)
        if (!safeHasAccess()) {
            host.requestUsageAccess()
            return
        }
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.google.android.apps.wellbeing", "com.google.android.apps.wellbeing.settings.TopLevelSettingsActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.forest", "com.samsung.android.forest.launcher.LauncherActivity"))
        )
        for (i in candidates) {
            try {
                host.activity.startActivity(i)
                return
            } catch (_: Exception) {}
        }
        host.startActivitySafely(Intent(Settings.ACTION_SETTINGS), this)
    }

    override fun onAccentChanged(color: Int) {
        p.setAccent(color)
        invalidate()
    }

    private fun durationText(ms: Long): String {
        val (h, m) = EaKit.hm(ms)
        return if (h > 0) context.getString(R.string.ea_duration_hm, h, m) else context.getString(R.string.ea_duration_m, m)
    }

    private fun updateDescription() {
        val s = snapshot
        contentDescription = when {
            !allowed -> context.getString(R.string.ea_usage_allow)
            s == null -> context.getString(R.string.element_screen_time)
            else -> context.getString(R.string.ea_screen_time_cd, durationText(s.totalMs))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val headerH = if (h > EaKit.dp(context, 70f)) p.label.textSize * 1.9f else 0f
        if (headerH > 0) ElementUi.drawHeader(canvas, 0f, p.label.textSize, context.getString(R.string.element_screen_time), p.accent, p.label)
        val top = headerH
        val areaH = h - top

        if (!allowed) {
            val cy = top + areaH / 2f
            EaKit.drawFitText(canvas, context.getString(R.string.ea_tap_to_allow).uppercase(), w / 2f, cy, w, msgPaint)
            EaKit.drawFitText(canvas, context.getString(R.string.ea_usage_access).uppercase(), w / 2f, cy + p.label.textSize * 1.6f, w, p.label)
            return
        }
        val s = snapshot
        val total = s?.let { durationText(it.totalMs) } ?: "--"

        // Right column with top apps when the element is wide enough.
        val showApps = s != null && s.top.isNotEmpty() && w > EaKit.dp(context, 220f) && areaH > EaKit.dp(context, 70f)
        val leftW = if (showApps) w * 0.55f else w
        val gap = EaKit.dp(context, 12f)

        // Histogram height: 4 dot rows (15 min each).
        val rows = 4
        val pitchBar = minOf((leftW / 24f), areaH * 0.45f / rows)
        val barH = pitchBar * rows
        val showBar = areaH > EaKit.dp(context, 56f)
        val numberMaxH = (areaH - (if (showBar) barH + gap else 0f)).coerceAtLeast(0f)
        val pitch = ElementUi.fitPitch(total, leftW, minOf(numberMaxH, EaKit.dp(context, 40f)))
        val numTop = top + (if (showBar) 0f else (areaH - pitch * 7) / 2f)
        ElementUi.drawDotText(canvas, total, 0f, numTop, pitch, p.on)

        if (showBar && s != null) {
            val barTop = h - barH
            val dotR = pitchBar * 0.36f
            for (hr in 0 until 24) {
                val minutes = s.hourly[hr] / 60_000f
                val lit = when {
                    minutes <= 0f -> 0
                    else -> kotlin.math.ceil(minutes / 15f).toInt().coerceIn(1, rows)
                }
                val x = (hr + 0.5f) * pitchBar
                for (r in 0 until rows) {
                    val y = barTop + (rows - r - 0.5f) * pitchBar
                    val on = r < lit
                    val paint = when {
                        hr > s.currentHour -> null
                        on && hr == s.currentHour -> p.accent
                        on -> p.on
                        else -> p.off
                    }
                    if (paint != null) canvas.drawCircle(x, y, if (on) dotR else dotR * 0.8f, paint)
                }
            }
        }

        if (showApps && s != null) {
            val x0 = leftW + gap
            val colW = w - x0
            val lineH = appPaint.textSize * 2.6f
            val maxRows = ((areaH) / lineH).toInt().coerceAtMost(s.top.size)
            var y = top + appPaint.textSize
            for (i in 0 until maxRows) {
                val a = s.top[i]
                val dur = durationText(a.ms)
                p.label.textAlign = Paint.Align.RIGHT
                canvas.drawText(dur, w, y, p.label)
                p.label.textAlign = Paint.Align.LEFT
                val durW = p.label.measureText(dur) + EaKit.dp(context, 6f)
                EaKit.drawFitText(canvas, a.label.uppercase(), x0, y, colW - durW, appPaint, Paint.Align.LEFT)
                // Thin dot bar proportional to the leader.
                val frac = if (s.top[0].ms > 0) a.ms.toFloat() / s.top[0].ms else 0f
                val dots = 12
                val dp = colW / dots
                val litN = kotlin.math.ceil(frac * dots).toInt()
                for (d in 0 until dots) {
                    canvas.drawCircle(x0 + (d + 0.5f) * dp, y + appPaint.textSize * 0.9f, dp * 0.18f, if (d < litN) (if (i == 0) p.accent else p.on) else p.off)
                }
                y += lineH
            }
        }
        p.label.color = secondaryColor
    }

    companion object {
        private const val REFRESH_MS = 60_000L
    }
}
