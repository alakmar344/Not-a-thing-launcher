package com.nothinglauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A Nothing-style Battery Gauge Element (v7.6).
 * Displays live battery percentage in DotFont with a circular dot capacity gauge and charging status.
 */
class BatteryGaugeDotsView @JvmOverloads constructor(
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

    var batteryLevel: Int = 85
        set(value) {
            field = value.coerceIn(0, 100)
            pctText = "$field%"
            updateDescription()
            invalidate()
        }

    var isCharging: Boolean = false
        set(value) { field = value; updateDescription(); invalidate() }

    private var pctText = "85%"

    // Resolved once instead of on every frame.
    private val titleText = context.getString(R.string.battery_dots_label)
    private val chargingText = context.getString(R.string.battery_charging_label)
    private val lowText = context.getString(R.string.battery_low).uppercase()
    private val normalText = context.getString(R.string.battery_status_normal)

    /** Same rule as [BatteryDotsView]: a charging battery is never shown as "low". */
    private val isLow: Boolean get() = batteryLevel <= LOW_THRESHOLD && !isCharging

    private fun updateDescription() {
        contentDescription = "${context.getString(R.string.battery_widget)} $batteryLevel%" +
            if (isCharging) ", ${context.getString(R.string.charging)}" else ""
    }

    private var receiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level >= 0 && scale > 0) {
                batteryLevel = (level * 100 / scale.toFloat()).roundToInt()
            }
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    init {
        queryInitialBattery()
        updateDescription()
    }

    private fun queryInitialBattery() {
        if (isInEditMode) return
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            if (batteryStatus != null) {
                val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (level >= 0 && scale > 0) {
                    batteryLevel = (level * 100 / scale.toFloat()).roundToInt()
                }
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (_: Throwable) {}
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!receiverRegistered) {
            try {
                context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                receiverRegistered = true
            } catch (_: Throwable) {}
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver)
                receiverRegistered = false
            } catch (_: Throwable) {}
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val low = isLow

        // 1. Draw Title
        canvas.drawText(titleText, 0f, 12f * density, titlePaint)

        // 2. Draw DotFont battery percentage
        val raster = DotFont.rasterizeCached(pctText, 1)
        val dotPitch = 2.8f * density
        val dotRadius = dotPitch * 0.38f
        val startY = 22f * density
        val startX = 0f

        for (r in 0 until DotFont.ROWS) {
            for (c in 0 until raster.columns) {
                if (raster.lit[r][c]) {
                    val cx = startX + c * dotPitch + dotRadius
                    val cy = startY + r * dotPitch + dotRadius
                    val paint = if (low) accentPaint else onPaint
                    canvas.drawCircle(cx, cy, dotRadius, paint)
                }
            }
        }

        // 3. Draw Subtitle
        val sub = when {
            isCharging -> chargingText
            low -> lowText
            else -> normalText
        }
        canvas.drawText(sub, 0f, startY + DotFont.ROWS * dotPitch + 15f * density, textPaint)

        // 4. Circular dot capacity ring on the right
        val ringCenterX = w - 36f * density
        val ringCenterY = h / 2f + 4f * density
        val ringRadius = 24f * density
        val totalDots = 28
        val activeDots = ((batteryLevel / 100f) * totalDots).toInt().coerceIn(if (batteryLevel > 0) 1 else 0, totalDots)

        for (i in 0 until totalDots) {
            val angle = (i.toFloat() / totalDots) * (2 * Math.PI) - (Math.PI / 2)
            val cx = ringCenterX + ringRadius * cos(angle).toFloat()
            val cy = ringCenterY + ringRadius * sin(angle).toFloat()

            val paint = if (i < activeDots) {
                if (isCharging || low) accentPaint else onPaint
            } else offPaint

            val r = if (i == 0 || i == totalDots / 2) 2.2f * density else 1.5f * density
            canvas.drawCircle(cx, cy, r, paint)
        }

        // Center dot / bolt in circle
        if (isCharging) {
            canvas.drawCircle(ringCenterX, ringCenterY, 3f * density, accentPaint)
        } else {
            canvas.drawCircle(ringCenterX, ringCenterY, 2f * density, onPaint)
        }
    }

    private companion object {
        const val LOW_THRESHOLD = 15
    }
}
