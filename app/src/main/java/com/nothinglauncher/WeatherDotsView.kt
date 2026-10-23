package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
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

    var city: String = context.getString(R.string.weather_city_default)
        set(value) { field = value; invalidate() }

    var temperature: String = "21°"
        set(value) { field = value; invalidate() }

    var condition: String = context.getString(R.string.weather_condition_default)
        set(value) { field = value; invalidate() }

    var highLow: String = "H:24° L:15°"
        set(value) { field = value; invalidate() }

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

        // 3. Center-Right: Procedural Dot-Matrix Weather Graphic (Sun with orbital dots)
        val iconCx = width - padX - 32f * density
        val iconCy = tempY0 + (DotFont.ROWS * pitch) / 2f
        val sunR = 12f * density
        val rayR = 20f * density

        // Sun center dots
        canvas.drawCircle(iconCx, iconCy, sunR * 0.6f, onPaint)
        // 8 orbiting ray dots
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val rx = iconCx + (rayR * cos(angle)).toFloat()
            val ry = iconCy + (rayR * sin(angle)).toFloat()
            canvas.drawCircle(rx, ry, 1.8f * density, if (i % 2 == 0) accentPaint else onPaint)
        }
        // Cloud arc dots overlay
        val cloudY = iconCy + 6f * density
        for (c in -3..3) {
            val cx = iconCx + c * 5f * density
            val cy = cloudY - (3 - kotlin.math.abs(c)) * 2f * density
            canvas.drawCircle(cx, cy, 2.2f * density, onPaint)
        }

        // 4. Footer: Condition label & High/Low
        val footerY = height - 14f * density
        canvas.drawText(condition.uppercase(), padX, footerY - 12f * density, titlePaint)
        canvas.drawText(highLow, padX, footerY, textPaint)
    }
}
