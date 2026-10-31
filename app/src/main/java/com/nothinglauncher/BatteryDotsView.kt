package com.nothinglauncher

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Battery level as a ring of dots with the percentage in dot-matrix type in the middle.
 * The ring turns accent-coloured when low; while charging the next dot breathes.
 * Designed for the light "paper" tile.
 */
class BatteryDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val dotCount = 40

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ink) }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.paper_dot_off) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.nothing_red) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink_secondary)
        textSize = 9.5f * density * resources.configuration.fontScale
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
        typeface = try { ResourcesCompat.getFont(context, R.font.space_mono_regular) } catch (e: Exception) { null }
    }

    var level: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            levelText = field.toString()
            updateDescription()
            invalidate()
        }

    private var levelText = "0"

    var charging: Boolean = false
        set(value) { field = value; updateDescription(); updatePulse(); invalidate() }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    private var pulse = 1f
    private var pulseAnimator: ValueAnimator? = null

    private val lowThreshold = 15

    private var receiverRegistered = false

    // Resolved once: onDraw runs at 60 fps while the charging pulse animates.
    private val labelCharging = context.getString(R.string.charging).uppercase()
    private val labelLow = context.getString(R.string.battery_low).uppercase()
    private val labelNormal = context.getString(R.string.battery).uppercase()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val lvl = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (lvl >= 0 && scale > 0) {
                level = (lvl * 100 / scale.toFloat()).roundToInt()
            }
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    init {
        queryInitialBattery()
    }

    private fun queryInitialBattery() {
        if (isInEditMode) return
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            if (batteryStatus != null) {
                val lvl = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (lvl >= 0 && scale > 0) {
                    level = (lvl * 100 / scale.toFloat()).roundToInt()
                }
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (_: Throwable) {}
    }

    private fun updateDescription() {
        contentDescription = "${context.getString(R.string.battery_widget)} $level%" +
            if (charging) ", ${context.getString(R.string.charging)}" else ""
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (size <= 0f) return
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val dotR = size * 0.026f
        val ringR = size / 2f - dotR * 1.6f
        val litCount = (level / 100f * dotCount).roundToInt()
        val low = level <= lowThreshold && !charging
        val litPaint = if (low) accentPaint else onPaint

        for (i in 0 until dotCount) {
            val angle = Math.toRadians(-90.0 + i * 360.0 / dotCount)
            val x = cx + (ringR * cos(angle)).toFloat()
            val y = cy + (ringR * sin(angle)).toFloat()
            when {
                i < litCount -> canvas.drawCircle(x, y, dotR, litPaint)
                charging && i == litCount -> {
                    accentPaint.alpha = (90 + 165 * pulse).toInt()
                    canvas.drawCircle(x, y, dotR * (0.9f + 0.3f * pulse), accentPaint)
                    accentPaint.alpha = 255
                }
                else -> canvas.drawCircle(x, y, dotR * 0.8f, offPaint)
            }
        }

        // Percentage in dot-matrix type.
        val raster = DotFont.rasterizeCached(levelText)
        val pitch = min(ringR * 0.17f, (ringR * 1.15f) / raster.columns.coerceAtLeast(1))
        val r = pitch * 0.38f
        val textW = raster.columns * pitch
        val textH = DotFont.ROWS * pitch
        val x0 = cx - textW / 2f
        val y0 = cy - textH / 2f - 6f * density
        for (row in 0 until DotFont.ROWS) {
            for (col in 0 until raster.columns) {
                if (!raster.lit[row][col]) continue
                canvas.drawCircle(x0 + (col + 0.5f) * pitch, y0 + (row + 0.5f) * pitch, r, if (low) accentPaint else onPaint)
            }
        }

        val label = when {
            charging -> labelCharging
            low -> labelLow
            else -> labelNormal
        }
        canvas.drawText(label, cx, y0 + textH + 16f * density, labelPaint)
    }

    private fun updatePulse() {
        val shouldPulse = charging && isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE && isShown
        if (shouldPulse && pulseAnimator == null) {
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (!shouldPulse) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            pulse = 1f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePulse()
        if (!receiverRegistered) {
            try {
                context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                receiverRegistered = true
            } catch (_: Throwable) {}
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        pulseAnimator = null
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver)
                receiverRegistered = false
            } catch (_: Throwable) {}
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updatePulse()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updatePulse()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updatePulse()
    }
}
