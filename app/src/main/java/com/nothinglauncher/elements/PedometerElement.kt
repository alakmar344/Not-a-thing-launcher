package com.nothinglauncher.elements

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.text.InputType
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.nothinglauncher.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Daily step count from the hardware step counter. The counter is cumulative since boot, so a
 * per-day baseline is kept in shared element storage (all pedometer instances agree).
 */
class PedometerElement(context: Context) : ElementView(context), SensorEventListener {

    private val shared = ElementStore(context, ElementStore.SHARED_ID)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val stepSensor: Sensor? = try { sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } catch (e: Exception) { null }

    private val p = EaPaints(context, accentColor)
    private val bigLabel = ElementUi.labelPaint(context, 10f, primary = true)

    private var registered = false
    private var steps: Int? = null

    private val goal: Int get() = if (isHostReady) store.getInt(KEY_GOAL, DEFAULT_GOAL).coerceAtLeast(1) else DEFAULT_GOAL

    init {
        setWillNotDraw(false)
        setBackgroundColor(0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
    }

    override fun onBound() {
        loadCachedSteps()
        updateDescription()
        invalidate()
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun loadCachedSteps() {
        steps = if (shared.getString(K_DATE) == today() && shared.contains(K_TODAY)) shared.getInt(K_TODAY) else null
    }

    override fun onLiveChanged(live: Boolean) {
        if (live) {
            loadCachedSteps()
            register()
        } else {
            unregister()
        }
        updateDescription()
        invalidate()
    }

    private fun register() {
        if (registered || stepSensor == null || !hasPermission()) return
        registered = try {
            sensorManager?.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI) == true
        } catch (e: Exception) {
            false
        }
    }

    private fun unregister() {
        if (!registered) return
        try { sensorManager?.unregisterListener(this) } catch (_: Exception) {}
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_COUNTER || event.values.isEmpty()) return
        val counter = event.values[0]
        val now = System.currentTimeMillis()
        val day = today()
        var baseline = shared.getFloat(K_BASELINE, Float.NaN)
        val last = shared.getFloat(K_LAST, Float.NaN)
        val lastTime = shared.getLong(K_LAST_TIME, 0L)
        val storedDay = shared.getString(K_DATE)

        if (storedDay != day || baseline.isNaN()) {
            // New day (or first run). If our previous reading was taken shortly before now and the
            // counter has not reset, it is the best estimate of the counter value at midnight.
            baseline = if (!last.isNaN() && counter >= last && now - lastTime in 0..ROLLOVER_WINDOW_MS) last else counter
            shared.putString(K_DATE, day)
        } else if (!last.isNaN() && counter < last) {
            // Device rebooted: the counter restarted from zero. Keep the steps already counted today.
            val already = shared.getInt(K_TODAY, 0)
            baseline = -already.toFloat()
        }
        val today = (counter - baseline).toInt().coerceAtLeast(0)
        shared.putFloat(K_BASELINE, baseline)
        shared.putFloat(K_LAST, counter)
        shared.putLong(K_LAST_TIME, now)
        shared.putInt(K_TODAY, today)
        if (steps != today) {
            steps = today
            updateDescription()
            invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onTap() {
        if (!isHostReady) return
        EaKit.tap(this)
        if (stepSensor == null) return
        if (!hasPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                host.requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) { granted ->
                    if (granted && isLive) register()
                    updateDescription()
                    invalidate()
                }
            }
            return
        }
        val fitness = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FITNESS)
        try {
            host.activity.startActivity(fitness)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.ea_no_fitness_app, Toast.LENGTH_SHORT).show()
        }
    }

    override val hasSettings: Boolean get() = true

    override fun openSettings() {
        if (!isHostReady) return
        val sheet = ElementSheet(host.activity, context.getString(R.string.ea_ped_settings_title))
        val field = sheet.addField(
            context.getString(R.string.ea_ped_goal_label),
            goal.toString(),
            DEFAULT_GOAL.toString(),
            InputType.TYPE_CLASS_NUMBER
        )
        sheet.addText(context.getString(R.string.ea_ped_goal_hint))
        sheet.buttons(context.getString(R.string.ea_save), onPrimary = {
            val v = field.text?.toString()?.trim()?.toIntOrNull()
            if (v == null || v < MIN_GOAL || v > MAX_GOAL) {
                field.error = context.getString(R.string.ea_ped_goal_invalid, MIN_GOAL, MAX_GOAL)
                false
            } else {
                store.putInt(KEY_GOAL, v)
                updateDescription()
                invalidate()
                true
            }
        })
        sheet.show(host)
    }

    override fun onAccentChanged(color: Int) {
        p.setAccent(color)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        unregister()
        super.onDetachedFromWindow()
    }

    private fun updateDescription() {
        val s = steps
        contentDescription = when {
            stepSensor == null -> context.getString(R.string.ea_no_step_sensor)
            !hasPermission() -> context.getString(R.string.ea_ped_cd_allow)
            s == null -> context.getString(R.string.ea_ped_cd_waiting)
            else -> context.getString(R.string.ea_ped_cd, s, s * 100 / goal, goal)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val title = context.getString(R.string.element_pedometer)

        val headerH = if (h > EaKit.dp(context, 100f)) p.label.textSize * 1.9f else 0f
        if (headerH > 0) ElementUi.drawHeader(canvas, 0f, p.label.textSize, title, p.accent, p.label)
        val top = headerH
        val areaH = h - top

        when {
            stepSensor == null -> { drawMessage(canvas, top, areaH, context.getString(R.string.ea_no_step_sensor), null); return }
            !hasPermission() -> { drawMessage(canvas, top, areaH, context.getString(R.string.ea_tap_to_allow), context.getString(R.string.ea_ped_needs_activity)); return }
        }

        val s = steps
        val g = goal
        val pct = if (s == null) 0 else (s.toLong() * 100 / g).toInt()
        val wide = w > areaH * 1.6f
        val ringSize = if (wide) areaH else minOf(w, areaH)
        val cx = if (wide) ringSize / 2f else w / 2f
        val cy = top + areaH / 2f
        val dotR = (ringSize / 48f).coerceAtLeast(EaKit.dp(context, 1.2f))
        val radius = ringSize / 2f - dotR * 1.5f
        val count = 40
        val lit = ((pct.coerceIn(0, 100) * count) / 100f).toInt()
        EaKit.drawRing(canvas, cx, cy, radius, count, lit, dotR, p.on, p.off) { i, isLit ->
            // Accent marks the leading edge of progress, or the whole ring once the goal is met.
            if (isLit && (pct >= 100 || i == lit - 1)) p.accent else null
        }

        val stepText = s?.toString() ?: "--"
        if (wide) {
            val tx = ringSize + EaKit.dp(context, 14f)
            val tw = w - tx
            if (tw <= 0) return
            val pitch = ElementUi.fitPitch(stepText, tw, areaH * 0.38f)
            val dotH = pitch * 7
            val blockTop = cy - (dotH + p.labelPrimary.textSize * 2.6f) / 2f
            ElementUi.drawDotText(canvas, stepText, tx, blockTop, pitch, p.on)
            EaKit.drawFitText(canvas, context.getString(R.string.ea_steps).uppercase(), tx, blockTop + dotH + p.labelPrimary.textSize * 1.3f, tw, p.labelPrimary, android.graphics.Paint.Align.LEFT)
            val sub = if (s == null) context.getString(R.string.ea_walk_to_start) else context.getString(R.string.ea_pct_of_goal_n, pct, g)
            EaKit.drawFitText(canvas, sub.uppercase(), tx, blockTop + dotH + p.labelPrimary.textSize * 2.6f, tw, p.label, android.graphics.Paint.Align.LEFT)
        } else {
            val inner = (radius - dotR * 2.5f) * 2f * 0.80f
            val pitch = ElementUi.fitPitch(stepText, inner, inner * 0.30f)
            val dotH = pitch * 7
            val labelGap = p.label.textSize * 1.4f
            val showSub = inner > p.label.textSize * 5
            val blockH = dotH + if (showSub) labelGap else 0f
            val blockTop = cy - blockH / 2f
            EaKit.drawDotCentered(canvas, stepText, cx, blockTop, inner, inner * 0.30f, p.on)
            if (showSub) {
                val sub = if (s == null) context.getString(R.string.ea_walk_to_start) else context.getString(R.string.ea_pct_of_goal, pct)
                EaKit.drawFitText(canvas, sub.uppercase(), cx, blockTop + dotH + labelGap, inner, p.label)
            }
        }
    }

    private fun drawMessage(canvas: Canvas, top: Float, areaH: Float, main: String, sub: String?) {
        val w = width.toFloat()
        val cy = top + areaH / 2f
        val dotR = EaKit.dp(context, 1.6f)
        val ringSize = minOf(w, areaH)
        EaKit.drawRing(canvas, w / 2f, cy, ringSize / 2f - dotR * 2, 40, 0, dotR, p.on, p.off)
        val maxW = ringSize * 0.7f
        if (sub != null) {
            EaKit.drawFitText(canvas, main.uppercase(), w / 2f, cy, maxW, bigLabel)
            EaKit.drawFitText(canvas, sub.uppercase(), w / 2f, cy + p.label.textSize * 1.5f, maxW, p.label)
        } else {
            EaKit.drawFitText(canvas, main.uppercase(), w / 2f, cy + bigLabel.textSize / 3f, maxW, bigLabel)
        }
    }

    companion object {
        private const val KEY_GOAL = "goal"
        private const val DEFAULT_GOAL = 10000
        private const val MIN_GOAL = 100
        private const val MAX_GOAL = 100000
        private const val ROLLOVER_WINDOW_MS = 2 * 60 * 60 * 1000L
        private const val K_BASELINE = "steps.baseline"
        private const val K_DATE = "steps.date"
        private const val K_LAST = "steps.last"
        private const val K_LAST_TIME = "steps.lastTime"
        private const val K_TODAY = "steps.today"
    }
}
