package com.nothinglauncher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Calendar

/**
 * A Nothing-style Screen Time tile. Values come from UsageStatsManager when usage access
 * has been granted; without it the tile says so instead of inventing a number.
 */
class ScreenTimeDotsView @JvmOverloads constructor(
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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        letterSpacing = 0.08f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (e: Exception) { null }
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        letterSpacing = 0.10f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_bold) } catch (e: Exception) { null }
    }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    var timeText: String = "--"
        set(value) { field = value; invalidate() }

    var subtitleText: String = context.getString(R.string.screen_time_no_access)
        set(value) { field = value; invalidate() }

    var hasAccess: Boolean = false
        private set

    private var dailyBars = FloatArray(7)

    private var refreshing = false
    private var refreshPending = false
    private var running = false

    private val refreshTicker = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            postDelayed(this, 60_000L)
        }
    }

    init {
        refresh()
    }

    private val titleText = context.getString(R.string.screen_time_label)

    private fun updateRunning() {
        // Also pause when the tile itself (or an ancestor page) is hidden, not only the window.
        val shouldRun = isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE && isShown
        if (shouldRun == running) return
        running = shouldRun
        removeCallbacks(refreshTicker)
        if (running) post(refreshTicker)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(refreshTicker)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateRunning()
    }

    private class Snapshot(val days: LongArray, val unlocks: Int)

    /**
     * Seven UsageStats queries plus a full event scan are far too slow for the UI thread
     * (hundreds of ms on busy devices), so the numbers are gathered on a worker thread.
     */
    fun refresh() {
        if (isInEditMode) return
        hasAccess = try { LauncherSystem.hasUsageAccess(context) } catch (_: Throwable) { false }
        val usm = try {
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        } catch (_: Throwable) {
            null
        }
        if (!hasAccess || usm == null) {
            timeText = "--"
            subtitleText = context.getString(R.string.screen_time_no_access)
            dailyBars = FloatArray(7)
            contentDescription = subtitleText
            invalidate()
            return
        }
        if (refreshing) {
            refreshPending = true
            return
        }
        refreshing = true
        worker.execute {
            val snapshot = try { query(usm) } catch (_: Throwable) { null }
            post {
                refreshing = false
                if (snapshot != null) applySnapshot(snapshot)
                if (refreshPending) {
                    refreshPending = false
                    refresh()
                }
            }
        }
    }

    private fun query(usm: UsageStatsManager): Snapshot {
        val days = LongArray(7)
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val end = System.currentTimeMillis()
        val todayStart = calendar.timeInMillis

        for (offset in 0 until 7) {
            // Step back by calendar days so DST changes don't shift the day boundaries.
            val day = calendar.clone() as Calendar
            day.add(Calendar.DAY_OF_YEAR, -offset)
            val dayStart = day.timeInMillis
            day.add(Calendar.DAY_OF_YEAR, 1)
            val dayEnd = minOf(day.timeInMillis, end)
            days[6 - offset] = foregroundMillis(usm, dayStart, dayEnd)
        }
        return Snapshot(days, unlockCount(usm, todayStart, end))
    }

    private fun applySnapshot(snapshot: Snapshot) {
        val days = snapshot.days
        val today = days.last()
        val hours = today / HOUR_MS
        val minutes = (today / MINUTE_MS) % 60
        timeText = if (today <= 0L) "0m" else "${hours}h ${minutes}m"

        val unlocks = snapshot.unlocks
        subtitleText = if (unlocks >= 0) {
            context.getString(R.string.screen_time_unlocks, unlocks)
        } else {
            context.getString(R.string.screen_time_today)
        }

        val max = days.maxOrNull() ?: 0L
        dailyBars = FloatArray(7) { index ->
            if (max > 0L) (days[index].toFloat() / max).coerceIn(0f, 1f) else 0f
        }
        contentDescription = "$timeText, $subtitleText"
        invalidate()
    }

    private fun foregroundMillis(usm: UsageStatsManager, start: Long, end: Long): Long {
        return try {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            stats.orEmpty()
                .filter { it.lastTimeUsed in start..end || it.firstTimeStamp in start..end }
                .sumOf { it.totalTimeInForeground.coerceAtLeast(0L) }
        } catch (_: Exception) {
            0L
        }
    }

    private fun unlockCount(usm: UsageStatsManager, start: Long, end: Long): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return -1
        return try {
            val events = usm.queryEvents(start, end) ?: return -1
            val event = UsageEvents.Event()
            var count = 0
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.SCREEN_INTERACTIVE) count++
            }
            count
        } catch (_: Exception) {
            -1
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawText(titleText, 0f, 12f * density, titlePaint)

        val raster = DotFont.rasterizeCached(timeText, 1)
        val dotPitch = 2.8f * density
        val dotRadius = dotPitch * 0.38f
        val startY = 22f * density
        for (r in 0 until DotFont.ROWS) {
            for (c in 0 until raster.columns) {
                if (raster.lit[r][c]) {
                    canvas.drawCircle(c * dotPitch + dotRadius, startY + r * dotPitch + dotRadius, dotRadius, onPaint)
                }
            }
        }

        val barAreaRight = w - 4f * density
        val barWidth = 4f * density
        val barGap = 4.5f * density
        val totalBarsWidth = 7 * barWidth + 6 * barGap
        val barAreaLeft = barAreaRight - totalBarsWidth

        // Keep the subtitle ("TAP TO GRANT USAGE ACCESS") from running under the bars.
        val subMaxW = (barAreaLeft - 8f * density).coerceAtLeast(0f)
        val subFit = textPaint.breakText(subtitleText, true, subMaxW, null)
        canvas.drawText(subtitleText, 0, subFit, 0f, startY + DotFont.ROWS * dotPitch + 15f * density, textPaint)
        val barBottom = h - 6f * density
        val barMaxHeight = h - 24f * density

        for (i in 0 until 7) {
            val bx = barAreaLeft + i * (barWidth + barGap) + barWidth / 2f
            val dotCount = 8
            val activeDots = (dailyBars[i] * dotCount).toInt().coerceIn(if (dailyBars[i] > 0f) 1 else 0, dotCount)
            val dotDist = barMaxHeight / dotCount
            for (d in 0 until dotCount) {
                val dy = barBottom - (d * dotDist)
                val paint = if (d < activeDots) {
                    if (i == 6) accentPaint else onPaint
                } else offPaint
                canvas.drawCircle(bx, dy, 1.4f * density, paint)
            }
        }
    }

    private companion object {
        val worker: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "screen-time").apply { isDaemon = true }
        }
        const val HOUR_MS = 60 * 60 * 1000L
        const val MINUTE_MS = 60 * 1000L
    }
}
