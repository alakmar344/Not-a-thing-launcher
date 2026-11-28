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

    private var fitCityKey: String? = null
    private var fitCityMaxW = -1f
    private var cachedCityFit = 0
    private var fitCondKey: String? = null
    private var fitCondMaxW = -1f
    private var cachedCondFit = 0
    private var fitHlKey: String? = null
    private var fitHlMaxW = -1f
    private var cachedHlFit = 0

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
        if (fitCityKey != cityUpper || fitCityMaxW != maxCityW) {
            cachedCityFit = titlePaint.breakText(cityUpper, true, maxCityW, null)
            fitCityKey = cityUpper
            fitCityMaxW = maxCityW
        }
        canvas.drawText(cityUpper, 0, cachedCityFit, cityX, padY + 3.5f * density, titlePaint)

        // 2. DotFont temperature
        val raster = DotFont.rasterizeCached(temperature)
        val availableH = height * (if (compact) 0.34f else 0.42f)
        val maxTempW = width * (if (compact) 0.72f else 0.46f)
        val pitch = min(availableH / DotFont.ROWS, maxTempW / raster.columns.coerceAtLeast(1))
        val dotRadius = pitch * 0.36f
        val tempY0 = padY + (if (compact) 16f else 18f) * density
        var ry = tempY0 + 0.5f * pitch
        for (row in 0 until DotFont.ROWS) {
            val rowLit = raster.lit[row]
            var rx = padX + 0.5f * pitch
            for (col in 0 until raster.columns) {
                if (rowLit[col]) canvas.drawCircle(rx, ry, dotRadius, onPaint)
                rx += pitch
            }
            ry += pitch
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
        val leftMaxW = (if (!compact && hourlyTemps.size >= 4) width * 0.48f else (width - padX * 2f)).coerceAtLeast(0f)
        if (fitCondKey != conditionUpper || fitCondMaxW != leftMaxW) {
            cachedCondFit = titlePaint.breakText(conditionUpper, true, leftMaxW, null)
            fitCondKey = conditionUpper
            fitCondMaxW = leftMaxW
        }
        canvas.drawText(conditionUpper, 0, cachedCondFit, padX, footerY - 12f * density, titlePaint)
        if (highLow.isNotBlank()) {
            if (fitHlKey != highLow || fitHlMaxW != leftMaxW) {
                cachedHlFit = textPaint.breakText(highLow, true, leftMaxW, null)
                fitHlKey = highLow
                fitHlMaxW = leftMaxW
            }
            canvas.drawText(highLow, 0, cachedHlFit, padX, footerY, textPaint)
        }

        if (!compact && hourlyTemps.size >= 4) {
            drawSparkline(canvas, width * 0.54f, footerY - 24f * density, width - padX, footerY - 2f * density)
        }
    }

    private fun drawSparkline(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val count = min(hourlyTemps.size, 12)
        if (count < 2 || right <= left || bottom <= top) return
        var minT = Int.MAX_VALUE
        var maxT = Int.MIN_VALUE
        for (i in 0 until count) {
            val v = hourlyTemps[i]
            if (v < minT) minT = v
            if (v > maxT) maxT = v
        }
        val span = (maxT - minT).coerceAtLeast(1)
        val stepX = (right - left) / (count - 1).coerceAtLeast(1)
        val midY = (top + bottom) * 0.5f
        val dotR = 1.75f * density
        val midDotR = 0.7f * density
        val rainY = bottom + 4.5f * density
        val heightSpan = bottom - top
        var cx = left
        for (idx in 0 until count) {
            val t = hourlyTemps[idx]
            canvas.drawCircle(cx, midY, midDotR, offPaint)
            val norm = (t - minT).toFloat() / span.toFloat()
            val cy = bottom - norm * heightSpan
            // Baseline rain probability indicator + live temperature dot
            val rainPct = if (idx < hourlyRainProbs.size) hourlyRainProbs[idx] else 0
            val basePaint = if (rainPct >= 25) accentPaint else offPaint
            val baseR = when {
                rainPct >= 60 -> 1.65f * density
                rainPct >= 25 -> 1.25f * density
                else -> 0.9f * density
            }
            canvas.drawCircle(cx, rainY, baseR, basePaint)
            val paint = if (idx == 0 || t == maxT) accentPaint else onPaint
            canvas.drawCircle(cx, cy, if (idx == 0) dotR * 1.35f else dotR, paint)
            cx += stepX
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
            val rayDist = 18f * density
            val rayR = 1.8f * density
            for (i in 0 until 8) {
                val rx = cx + rayDist * SUN_COS_8[i]
                val ry = cy + rayDist * SUN_SIN_8[i]
                canvas.drawCircle(rx, ry, rayR, if (i % 2 == 0) accentPaint else onPaint)
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

    private companion object {
        val SUN_COS_8 = FloatArray(8) { i -> cos(Math.toRadians(i * 45.0)).toFloat() }
        val SUN_SIN_8 = FloatArray(8) { i -> sin(Math.toRadians(i * 45.0)).toFloat() }
    }
}
