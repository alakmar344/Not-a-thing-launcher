package com.nothinglauncher

import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Calendar

/**
 * A Nothing-style Screen Time / Digital Wellbeing Tile.
 * Shows active daily screen usage in DotFont with a 7-segment dot usage bar.
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

    var timeText: String = "3h 42m"
        set(value) { field = value; invalidate() }

    var subtitleText: String = "TODAY · 18 UNLOCKS"
        set(value) { field = value; invalidate() }

    // Proportions for 7 days (values between 0.2 and 1.0)
    private val dailyBars = floatArrayOf(0.45f, 0.70f, 0.55f, 0.85f, 0.60f, 0.90f, 0.65f)

    init {
        refreshUsageStats()
    }

    private fun refreshUsageStats() {
        if (isInEditMode) return
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                val start = cal.timeInMillis
                val end = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                if (!stats.isNullOrEmpty()) {
                    var totalTimeMs = 0L
                    for (s in stats) totalTimeMs += s.totalTimeInForeground
                    if (totalTimeMs > 0) {
                        val hours = totalTimeMs / (1000 * 60 * 60)
                        val mins = (totalTimeMs / (1000 * 60)) % 60
                        timeText = "${hours}h ${mins}m"
                    }
                }
            }
        } catch (_: Throwable) {
            // Keep default
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Draw Section Title
        val title = context.getString(R.string.screen_time_label)
        canvas.drawText(title, 0f, 12f * density, titlePaint)

        // 2. Draw DotFont Digits
        val raster = DotFont.rasterize(timeText, 1)
        val dotPitch = 2.8f * density
        val dotRadius = dotPitch * 0.38f
        val startY = 22f * density
        val startX = 0f

        for (r in 0 until DotFont.ROWS) {
            for (c in 0 until raster.columns) {
                if (raster.lit[r][c]) {
                    val cx = startX + c * dotPitch + dotRadius
                    val cy = startY + r * dotPitch + dotRadius
                    canvas.drawCircle(cx, cy, dotRadius, onPaint)
                }
            }
        }

        // 3. Draw Subtitle
        canvas.drawText(subtitleText, 0f, startY + DotFont.ROWS * dotPitch + 15f * density, textPaint)

        // 4. Draw 7-Segment Dot Bar Chart on the right
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
            val activeDots = (dailyBars[i] * dotCount).toInt().coerceIn(1, dotCount)
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
}
