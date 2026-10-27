package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A Nothing-style Weather Tile featuring dot-matrix temperature and procedural
 * dot-matrix weather icon (sun with orbital dot rays and cloud silhouette).
 */
class WeatherDotsView @JvmOverloads constructor(
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
        textSize = 10f * density * resources.configuration.fontScale
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

    var city: String = ""
        set(value) { field = value; invalidate() }

    var temperature: String = "--\u00B0"
        set(value) { field = value; invalidate() }

    var condition: String = context.getString(R.string.weather_loading)
        set(value) { field = value; invalidate() }

    var highLow: String = ""
        set(value) { field = value; invalidate() }

    var isDay: Boolean = true
        set(value) { field = value; invalidate() }

    fun bind(weather: WeatherRepository.Weather?) {
        if (weather == null) {
            if (city.isBlank()) city = context.getString(R.string.weather_local)
            temperature = "--\u00B0"
            condition = context.getString(R.string.weather_unavailable)
            highLow = context.getString(R.string.weather_tap_refresh)
            contentDescription = condition
            return
        }
        city = weather.city
        temperature = "${weather.temperature}\u00B0"
        condition = weather.condition
        highLow = "H:${weather.high}\u00B0  L:${weather.low}\u00B0"
        isDay = weather.isDay
        contentDescription = "${weather.city}, ${weather.temperature}, ${weather.condition}"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padX = 14f * density
        val padY = 16f * density

        // 1. Header: Accent dot + City name
        val dotR = 2.5f * density
        canvas.drawCircle(padX + dotR, padY, dotR, accentPaint)
        canvas.drawText(city.uppercase(), padX + dotR * 2f + 6f * density, padY + 3.5f * density, titlePaint)

        // 2. Center-Left: Temperature in DotFont
        val raster = DotFont.rasterize(temperature)
        val availableH = height * 0.44f
        val pitch = min(availableH / DotFont.ROWS, (width * 0.45f) / raster.columns.coerceAtLeast(1))
        val dotRadius = pitch * 0.36f
        val tempY0 = padY + 18f * density
        for (row in 0 until DotFont.ROWS) {
            for (col in 0 until raster.columns) {
                val cx = padX + (col + 0.5f) * pitch
                val cy = tempY0 + (row + 0.5f) * pitch
                if (raster.lit[row][col]) {
                    canvas.drawCircle(cx, cy, dotRadius, onPaint)
                }
            }
        }

        // 3. Center-Right: procedural dot-matrix weather graphic
        val iconCx = width - padX - 32f * density
        val iconCy = tempY0 + (DotFont.ROWS * pitch) / 2f
        drawConditionIcon(canvas, iconCx, iconCy)

        // 4. Footer: Condition label & High/Low
        val footerY = height - 14f * density
        canvas.drawText(condition.uppercase(), padX, footerY - 12f * density, titlePaint)
        if (highLow.isNotBlank()) canvas.drawText(highLow, padX, footerY, textPaint)
    }

    private fun drawConditionIcon(canvas: Canvas, cx: Float, cy: Float) {
        val c = condition.uppercase(Locale.US)
        val rain = c.contains("RAIN") || c.contains("DRIZZLE") || c.contains("SHOWER") || c.contains("THUNDER")
        val snow = c.contains("SNOW")
        val cloudy = rain || snow || c.contains("CLOUD") || c.contains("OVERCAST") || c.contains("FOG")
        if (!cloudy && isDay) {
            // Sun: solid core with orbital rays.
            canvas.drawCircle(cx, cy, 8f * density, onPaint)
            for (i in 0 until 8) {
                val angle = Math.toRadians(i * 45.0)
                val rx = cx + (18f * density * cos(angle)).toFloat()
                val ry = cy + (18f * density * sin(angle)).toFloat()
                canvas.drawCircle(rx, ry, 1.8f * density, if (i % 2 == 0) accentPaint else onPaint)
            }
            return
        }
        if (!cloudy && !isDay) {
            // Crescent moon made from two overlapping circles.
            val clear = ContextCompat.getColor(context, R.color.nothing_bg)
            canvas.drawCircle(cx, cy, 10f * density, onPaint)
            canvas.drawCircle(cx + 5f * density, cy - 3f * density, 9f * density,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = clear })
            canvas.drawCircle(cx - 1f * density, cy + 1f * density, 2f * density, accentPaint)
            return
        }
        // Cloud silhouette.
        val cloudY = cy - 2f * density
        for (col in -4..4) {
            val px = cx + col * 4.2f * density
            val lift = (4 - kotlin.math.abs(col)).coerceAtLeast(0)
            canvas.drawCircle(px, cloudY - lift * 1.4f * density, 2.4f * density, onPaint)
        }
        for (col in -4..4) {
            canvas.drawCircle(cx + col * 4.2f * density, cloudY + 4f * density, 2.4f * density, onPaint)
        }
        if (rain) {
            for (col in -3..3) {
                val px = cx + col * 4.5f * density
                val py = cloudY + 11f * density + (if (col % 2 == 0) 0f else 3f * density)
                canvas.drawCircle(px, py, 1.5f * density, if (col == 0) accentPaint else onPaint)
            }
        } else if (snow) {
            for (col in -3..3 step 2) {
                canvas.drawCircle(cx + col * 4.5f * density, cloudY + 12f * density, 1.7f * density, onPaint)
            }
        } else if (c.contains("THUNDER")) {
            canvas.drawCircle(cx, cloudY + 12f * density, 2.2f * density, accentPaint)
            canvas.drawCircle(cx - 2.5f * density, cloudY + 16f * density, 1.5f * density, accentPaint)
        }
    }
}
