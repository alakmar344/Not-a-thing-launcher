package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A Nothing-style World Clock Tile showing Local and UTC time in DotFont.
 */
class WorldClockDotsView @JvmOverloads constructor(
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
        textSize = 9.5f * density * resources.configuration.fontScale
        letterSpacing = 0.08f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (e: Exception) { null }
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 11f * density * resources.configuration.fontScale
        letterSpacing = 0.10f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_bold) } catch (e: Exception) { null }
    }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    private var localTime: String = "12:00"
    private var utcTime: String = "07:00"

    private var running = false

    /** Re-format on every minute boundary; previously the tile froze at its bind time. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            updateTimes()
            postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    init {
        updateTimes()
    }

    fun updateTimes() {
        val is24 = try { DateFormat.is24HourFormat(context) } catch (_: Exception) { true }
        val pattern = if (is24) "HH:mm" else "hh:mm"
        // Locale.US keeps ASCII digits, the only ones DotFont can draw.
        val local = SimpleDateFormat(pattern, Locale.US)
        localTime = local.format(Date())
        val utc = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        utcTime = utc.format(Date())
        invalidate()
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && windowVisibility == VISIBLE
        if (shouldRun == running) return
        running = shouldRun
        removeCallbacks(ticker)
        if (running) post(ticker)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()

        // 1. Draw Title
        val title = context.getString(R.string.world_clock_label)
        canvas.drawText(title, 0f, 12f * density, titlePaint)

        // 2. Left: Local Clock
        val city1 = context.getString(R.string.world_clock_city1)
        canvas.drawText(city1, 0f, 28f * density, textPaint)

        val raster1 = DotFont.rasterize(localTime, 1)
        val dotPitch = 2.4f * density
        val dotRadius = dotPitch * 0.38f
        val startY = 34f * density

        for (r in 0 until DotFont.ROWS) {
            for (c in 0 until raster1.columns) {
                if (raster1.lit[r][c]) {
                    val cx = c * dotPitch + dotRadius
                    val cy = startY + r * dotPitch + dotRadius
                    canvas.drawCircle(cx, cy, dotRadius, onPaint)
                }
            }
        }

        // 3. Right: UTC / London Clock
        val rightX = w / 2f + 8f * density
        val city2 = context.getString(R.string.world_clock_city2)
        canvas.drawText(city2, rightX, 28f * density, textPaint)

        val raster2 = DotFont.rasterize(utcTime, 1)
        for (r in 0 until DotFont.ROWS) {
            for (c in 0 until raster2.columns) {
                if (raster2.lit[r][c]) {
                    val cx = rightX + c * dotPitch + dotRadius
                    val cy = startY + r * dotPitch + dotRadius
                    canvas.drawCircle(cx, cy, dotRadius, onPaint)
                }
            }
        }

        // Accent live dot between the clocks
        canvas.drawCircle(w / 2f - 4f * density, startY + 12f * density, 2f * density, accentPaint)
    }
}
