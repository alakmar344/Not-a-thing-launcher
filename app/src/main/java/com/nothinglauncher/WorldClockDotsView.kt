package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Calendar
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
        textSize = 9.5f * resources.displayMetrics.scaledDensity
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

    private var localTime: String = "12:00"
    private var utcTime: String = "07:00"

    init {
        updateTimes()
    }

    fun updateTimes() {
        val is24 = DateFormat.is24HourFormat(context)
        val format = if (is24) "HH:mm" else "hh:mm"

        val localCal = Calendar.getInstance()
        localTime = DateFormat.format(format, localCal).toString()

        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utcTime = DateFormat.format(format, utcCal).toString()
        invalidate()
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
