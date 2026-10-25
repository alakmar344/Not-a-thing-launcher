package com.nothinglauncher.elements

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.animation.AccelerateDecelerateInterpolator
import com.nothinglauncher.R

/** Live battery level with a dot capacity ring and a breathing charge indicator. */
class BatteryGaugeElement(context: Context) : ElementView(context) {

    private val p = EaPaints(context, accentColor)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    private val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private var level = -1
    private var charging = false
    private var full = false
    private var chargeRemainingMs = -1L
    private var breath = 1f
    private var animator: ValueAnimator? = null
    private var receiverOn = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent != null) applyBattery(intent)
        }
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(0)
        isClickable = true
        isFocusable = true
        setOnClickListener { if (isHostReady) { EaKit.tap(this); openBatterySettings(host, this) } }
        EaKit.batterySticky(context)?.let { applyBattery(it) }
    }

    private fun applyBattery(intent: Intent) {
        val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val newLevel = if (l >= 0 && s > 0) (l * 100f / s).toInt().coerceIn(0, 100) else level
        val newCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val newFull = status == BatteryManager.BATTERY_STATUS_FULL && plugged != 0
        chargeRemainingMs = if (newCharging && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { bm?.computeChargeTimeRemaining() ?: -1L } catch (e: Exception) { -1L }
        } else -1L
        val changed = newLevel != level || newCharging != charging || newFull != full
        level = newLevel
        charging = newCharging
        full = newFull
        if (changed) {
            updateDescription()
            updateAnimator()
        }
        invalidate()
    }

    override fun onLiveChanged(live: Boolean) {
        if (live) {
            if (!receiverOn) {
                EaKit.register(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { applyBattery(it) }
                receiverOn = true
            }
        } else {
            if (receiverOn) {
                EaKit.unregister(context, receiver)
                receiverOn = false
            }
        }
        updateAnimator()
    }

    private fun updateAnimator() {
        val want = isLive && charging
        if (want && animator == null) {
            animator = ValueAnimator.ofFloat(0.25f, 1f).apply {
                duration = 1400L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    breath = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!want && animator != null) {
            animator?.cancel()
            animator = null
            breath = 1f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        if (receiverOn) {
            EaKit.unregister(context, receiver)
            receiverOn = false
        }
        super.onDetachedFromWindow()
    }

    override fun onAccentChanged(color: Int) {
        p.setAccent(color)
        pulsePaint.color = color
        invalidate()
    }

    private fun statusText(): String = when {
        charging && chargeRemainingMs > 0 -> {
            val (h, m) = EaKit.hm(chargeRemainingMs)
            context.getString(R.string.ea_full_in, if (h > 0) context.getString(R.string.ea_duration_hm, h, m) else context.getString(R.string.ea_duration_m, m.coerceAtLeast(1)))
        }
        charging -> context.getString(R.string.ea_charging)
        full -> context.getString(R.string.ea_charged)
        level in 0..LOW -> context.getString(R.string.ea_battery_low)
        else -> context.getString(R.string.ea_battery)
    }

    private fun updateDescription() {
        contentDescription = if (level >= 0) context.getString(R.string.ea_battery_cd, level, statusText()) else context.getString(R.string.element_battery_dots)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val wide = w > h * 1.6f
        val size = if (wide) h else minOf(w, h)
        val cx = if (wide) size / 2f else w / 2f
        val cy = h / 2f
        val count = (size / EaKit.dp(context, 4.2f)).toInt().coerceIn(28, 40)
        val dotR = (size / 46f).coerceAtLeast(EaKit.dp(context, 1.2f))
        val radius = size / 2f - dotR * 1.6f
        val lvl = level.coerceAtLeast(0)
        val lit = if (level >= 0) ((lvl * count) + 50) / 100 else 0
        val low = level in 0..LOW
        pulsePaint.alpha = (255 * breath).toInt()
        EaKit.drawRing(canvas, cx, cy, radius, count, lit, dotR, p.on, p.off) { i, isLit ->
            when {
                !isLit -> null
                charging && i == lit - 1 -> pulsePaint
                low -> p.accent
                else -> null
            }
        }

        val pctText = if (level >= 0) "$lvl" else "--"
        val status = statusText().uppercase()
        val accentChars = if (low) pctText.indices.toSet() else emptySet()
        if (wide) {
            val tx = size + EaKit.dp(context, 14f)
            val tw = w - tx
            if (tw <= 0) return
            val txt = "$pctText%"
            val pitch = ElementUi.fitPitch(txt, tw, h * 0.4f)
            val dotH = pitch * 7
            val blockTop = cy - (dotH + p.label.textSize * 1.8f) / 2f
            ElementUi.drawDotText(canvas, txt, tx, blockTop, pitch, p.on, accentPaint = p.accent, accentChars = accentChars)
            drawStatus(canvas, status, tx, blockTop + dotH + p.label.textSize * 1.6f, tw, Paint.Align.LEFT)
        } else {
            val inner = (radius - dotR * 2.5f) * 2f * 0.8f
            if (inner <= 0) return
            val maxH = inner * 0.36f
            val pitch = ElementUi.fitPitch(pctText, inner, maxH)
            val dotH = pitch * 7
            val showSub = inner > p.label.textSize * 4.5f
            val gap = p.label.textSize * 1.5f
            val blockTop = cy - (dotH + if (showSub) gap else 0f) / 2f
            EaKit.drawDotCentered(canvas, pctText, cx, blockTop, inner, maxH, p.on, accent = p.accent, accentChars = accentChars)
            if (showSub) drawStatus(canvas, status, cx, blockTop + dotH + gap, inner, Paint.Align.CENTER)
        }
    }

    private fun drawStatus(canvas: Canvas, text: String, x: Float, baseline: Float, maxW: Float, align: Paint.Align) {
        if (charging) {
            // Small breathing bolt dot in front of the status.
            val r = p.label.textSize * 0.22f
            val tw = minOf(p.label.measureText(text), maxW - r * 4)
            val dx = when (align) {
                Paint.Align.LEFT -> x + r
                else -> x - tw / 2f - r * 2.5f
            }
            canvas.drawCircle(dx, baseline - p.label.textSize * 0.33f, r, pulsePaint)
            val tx = if (align == Paint.Align.LEFT) x + r * 4 else x + r * 1.5f
            EaKit.drawFitText(canvas, text, tx, baseline, maxW - r * 4, p.label, align)
        } else {
            EaKit.drawFitText(canvas, text, x, baseline, maxW, p.label, align)
        }
    }

    companion object {
        private const val LOW = 15

        /** Battery usage screen with a generic Settings fallback. */
        internal fun openBatterySettings(host: ElementHost, source: android.view.View) {
            try {
                host.activity.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            } catch (e: Exception) {
                host.startActivitySafely(Intent(Settings.ACTION_SETTINGS), source)
            }
        }
    }
}
