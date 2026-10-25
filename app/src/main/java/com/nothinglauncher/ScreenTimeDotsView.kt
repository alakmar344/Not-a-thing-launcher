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
        textSize = 10f * resources.displayMetrics.scaledDensity
        letterSpacing = 0.08f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (e: Exception) { null }
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 11f * resources.displayMetrics.scaledDensity
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

    private val refreshTicker = object : Runnable {
        override fun run() {
            refresh()
            postDelayed(this, 60_000L)
        }
    }

    init {
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(refreshTicker)
        post(refreshTicker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refreshTicker)
        super.onDetachedFromWindow()
    }

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

        val days = LongArray(7)
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val end = System.currentTimeMillis()
        val todayStart = calendar.timeInMillis

        for (offset in 0 until 7) {
            val dayStart = todayStart - offset * DAY_MS
            val dayEnd = minOf(dayStart + DAY_MS, end)
            days[6 - offset] = foregroundMillis(usm, dayStart, dayEnd)
        }

        val today = days.last()
        val hours = today / HOUR_MS
        val minutes = (today / MINUTE_MS) % 60
        timeText = if (today <= 0L) "0m" else "${hours}h ${minutes}m"

        val unlocks = unlockCount(usm, todayStart, end)
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

        canvas.drawText(context.getString(R.string.screen_time_label), 0f, 12f * density, titlePaint)

        val raster = DotFont.rasterize(timeText, 1)
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

        canvas.drawText(subtitleText, 0f, startY + DotFont.ROWS * dotPitch + 15f * density, textPaint)

        val barAreaRight = w - 4f * density
        val barWidth = 4f * density
        val barGap = 4.5f * density
        val totalBarsWidth = 7 * barWidth + 6 * barGap
        val barAreaLeft = barAreaRight - totalBarsWidth
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
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L
        const val MINUTE_MS = 60 * 1000L
    }
}
