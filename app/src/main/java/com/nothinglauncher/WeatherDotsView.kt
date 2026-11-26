package com.nothinglauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Responsive Nothing-style Weather view:
 * - Compact (2x2): city header, dot-matrix temperature, scaled condition glyph, concise condition.
 * - Wide (4x2+): side-by-side temperature + glyph + 12-hour dot-matrix temperature sparkline.
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
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        letterSpacing = 0.08f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (_: Exception) { null }
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        letterSpacing = 0.10f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_bold) } catch (_: Exception) { null }
    }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    fun applySurfaceColors(sc: AccentTheme.SurfaceColors) {
        onPaint.color = sc.onDot
        offPaint.color = sc.offDot
        titlePaint.color = sc.textPrimary
        textPaint.color = sc.textSecondary
        invalidate()
    }

    private val moonPath = Path()
    private val biteRect = Path()
    private var moonKeyX = Float.NaN
    private var moonKeyY = Float.NaN

    private var cityUpper = ""
    private var conditionUpper = ""
    private var conditionKey = ""

    var city: String = ""
        set(value) { field = value; cityUpper = value.uppercase(); invalidate() }

    var temperature: String = "--\u00B0"
        set(value) { field = value; invalidate() }

    var condition: String = context.getString(R.string.weather_loading)
        set(value) {
            field = value
            conditionUpper = value.uppercase()
            conditionKey = value.uppercase(Locale.US)
            invalidate()
        }

    var highLow: String = ""
        set(value) { field = value; invalidate() }

    var isDay: Boolean = true
        set(value) { field = value; invalidate() }

    var hourlyTemps: List<Int> = emptyList()
        set(value) { field = value; invalidate() }

    var hourlyRainProbs: List<Int> = emptyList()
        set(value) { field = value; invalidate() }

    var windKmh: Int = 0
        set(value) { field = value; invalidate() }

    init {
        conditionUpper = condition.uppercase()
        conditionKey = condition.uppercase(Locale.US)
        contentDescription = condition
    }

    fun bind(weather: WeatherRepository.Weather?) {
        if (weather == null) {
            if (city.isBlank()) city = context.getString(R.string.weather_local)
            temperature = "--\u00B0"
            condition = context.getString(R.string.weather_unavailable)
            highLow = context.getString(R.string.weather_tap_refresh)
            hourlyTemps = emptyList()
            hourlyRainProbs = emptyList()
            windKmh = 0
            contentDescription = condition
            return
        }
        city = weather.city
        temperature = "${weather.temperature}\u00B0"
        condition = weather.condition
        windKmh = weather.windKmh
        highLow = if (weather.windKmh > 0) {
            "H:${weather.high}\u00B0  L:${weather.low}\u00B0  ·  ${weather.windKmh}KM/H"
        } else {
            "H:${weather.high}\u00B0  L:${weather.low}\u00B0"
        }
        isDay = weather.isDay
        hourlyTemps = weather.hourlyTemps
        hourlyRainProbs = weather.hourlyRainProbs
        contentDescription = "${weather.city}, ${weather.temperature}\u00B0, ${weather.condition}"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val compact = width < height * 1.45f
        val padX = (if (compact) 12f else 14f) * density
        val padY = (if (compact) 14f else 16f) * density

        // 1. Header: Accent dot + City name
        val dotR = 2.5f * density
        canvas.drawCircle(padX + dotR, padY, dotR, accentPaint)
        val cityX = padX + dotR * 2f + 6f * density
        val maxCityW = (if (compact) width - padX - 28f * density - cityX else width - padX - cityX).coerceAtLeast(0f)
        val cityFit = titlePaint.breakText(cityUpper, true, maxCityW, null)
        canvas.drawText(cityUpper, 0, cityFit, cityX, padY + 3.5f * density, titlePaint)

        // 2. DotFont temperature
        val raster = DotFont.rasterizeCached(temperature)
        val availableH = height * (if (compact) 0.34f else 0.42f)
        val maxTempW = width * (if (compact) 0.72f else 0.46f)
        val pitch = min(availableH / DotFont.ROWS, maxTempW / raster.columns.coerceAtLeast(1))
        val dotRadius = pitch * 0.36f
        val tempY0 = padY + (if (compact) 16f else 18f) * density
        for (row in 0 until DotFont.ROWS) {
            for (col in 0 until raster.columns) {
                if (!raster.lit[row][col]) continue
                canvas.drawCircle(
                    padX + (col + 0.5f) * pitch,
                    tempY0 + (row + 0.5f) * pitch,
                    dotRadius,
                    onPaint
                )
            }
        }

        // 3. Weather graphic (scaled cleanly for compact vs wide)
        if (compact) {
            canvas.save()
            val iconScale = 0.65f
            val ix = width - padX - 18f * density
            val iy = padY + 4f * density
            canvas.translate(ix, iy)
            canvas.scale(iconScale, iconScale)
            drawConditionIcon(canvas, 0f, 0f)
            canvas.restore()
        } else {
            val iconCx = width - padX - 30f * density
            val iconCy = tempY0 + (DotFont.ROWS * pitch) / 2f - 4f * density
            drawConditionIcon(canvas, iconCx, iconCy)
        }

        // 4. Footer: condition + high/low + optional 12h dot sparkline on wide tiles
        val footerY = height - 13f * density
        val leftMaxW = if (!compact && hourlyTemps.size >= 4) width * 0.48f else (width - padX * 2f)
        val condFit = titlePaint.breakText(conditionUpper, true, leftMaxW.coerceAtLeast(0f), null)
        canvas.drawText(conditionUpper, 0, condFit, padX, footerY - 12f * density, titlePaint)
        if (highLow.isNotBlank()) {
            val hlFit = textPaint.breakText(highLow, true, leftMaxW.coerceAtLeast(0f), null)
            canvas.drawText(highLow, 0, hlFit, padX, footerY, textPaint)
        }

        if (!compact && hourlyTemps.size >= 4) {
            drawSparkline(canvas, width * 0.54f, footerY - 22f * density, width - padX, footerY + 1f * density)
        }
    }

    private fun drawSparkline(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val temps = hourlyTemps.take(12)
        if (temps.size < 2 || right <= left || bottom <= top) return
        val minT = temps.min()
        val maxT = temps.max()
        val span = (maxT - minT).coerceAtLeast(1)
        val stepX = (right - left) / (temps.size - 1).coerceAtLeast(1)
        val dotR = 1.7f * density
        temps.forEachIndexed { idx, t ->
            val cx = left + idx * stepX
            val norm = (t - minT).toFloat() / span.toFloat()
            val cy = bottom - norm * (bottom - top)
            // Subtle baseline dot + optional rain probability indicator + live curve dot
            val rainPct = hourlyRainProbs.getOrNull(idx) ?: 0
            val basePaint = if (rainPct >= 25) accentPaint else offPaint
            val baseR = if (rainPct >= 50) 1.5f * density else if (rainPct >= 25) 1.2f * density else 1f * density
            canvas.drawCircle(cx, bottom + 3f * density, baseR, basePaint)
            val paint = if (idx == 0 || t == maxT) accentPaint else onPaint
            canvas.drawCircle(cx, cy, if (idx == 0) dotR * 1.3f else dotR, paint)
        }
    }

    private fun drawConditionIcon(canvas: Canvas, cx: Float, cy: Float) {
        val c = conditionKey
        val thunder = c.contains("THUNDER")
        val rain = c.contains("RAIN") || c.contains("DRIZZLE") || c.contains("SHOWER")
        val snow = c.contains("SNOW")
        val cloudy = thunder || rain || snow || c.contains("CLOUD") || c.contains("OVERCAST") || c.contains("FOG")
        if (!cloudy && isDay) {
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
            if (cx != moonKeyX || cy != moonKeyY) {
                moonKeyX = cx; moonKeyY = cy
                moonPath.reset()
                moonPath.addCircle(cx, cy, 10f * density, Path.Direction.CW)
                biteRect.reset()
                biteRect.addCircle(cx + 5f * density, cy - 3f * density, 9f * density, Path.Direction.CW)
                moonPath.op(biteRect, Path.Op.DIFFERENCE)
            }
            canvas.drawPath(moonPath, onPaint)
            canvas.drawCircle(cx - 1f * density, cy + 1f * density, 2f * density, accentPaint)
            return
        }
        val cloudY = cy - 2f * density
        for (col in -4..4) {
            val px = cx + col * 4.2f * density
            val lift = (4 - kotlin.math.abs(col)).coerceAtLeast(0)
            canvas.drawCircle(px, cloudY - lift * 1.4f * density, 2.4f * density, onPaint)
        }
        for (col in -4..4) {
            canvas.drawCircle(cx + col * 4.2f * density, cloudY + 4f * density, 2.4f * density, onPaint)
        }
        if (thunder) {
            canvas.drawCircle(cx, cloudY + 12f * density, 2.2f * density, accentPaint)
            canvas.drawCircle(cx - 2.5f * density, cloudY + 16f * density, 1.5f * density, accentPaint)
        } else if (snow) {
            for (col in -3..3 step 2) {
                canvas.drawCircle(cx + col * 4.5f * density, cloudY + 12f * density, 1.7f * density, onPaint)
            }
        } else if (rain) {
            for (col in -3..3) {
                val px = cx + col * 4.5f * density
                val py = cloudY + 11f * density + (if (col % 2 == 0) 0f else 3f * density)
                canvas.drawCircle(px, py, 1.5f * density, if (col == 0) accentPaint else onPaint)
            }
        }
    }
}
