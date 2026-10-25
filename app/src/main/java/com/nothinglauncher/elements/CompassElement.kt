package com.nothinglauncher.elements

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import com.nothinglauncher.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Real magnetic heading with a rotating dot compass rose. */
class CompassElement(context: Context) : ElementView(context), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? = try { sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) } catch (e: Exception) { null }
    private val accelSensor: Sensor? = try { sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) } catch (e: Exception) { null }
    private val magSensor: Sensor? = try { sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) } catch (e: Exception) { null }
    private val available = rotationSensor != null || (accelSensor != null && magSensor != null)

    private val p = EaPaints(context, accentColor)
    private val cardinalPaint = ElementUi.labelPaint(context, 8f, primary = true).apply { textAlign = Paint.Align.CENTER }
    private val msgPaint = ElementUi.labelPaint(context, 10f, primary = true)

    private val cardinals: Array<String> by lazy { context.resources.getStringArray(R.array.ea_cardinals) }
    private val secondaryColor = ElementUi.color(context, R.color.text_secondary)

    private val rotMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private val gravity = FloatArray(3)
    private val geomag = FloatArray(3)
    private var hasGravity = false
    private var hasGeomag = false

    private var registered = false
    private var heading = Float.NaN   // smoothed, degrees 0..360
    private var shownDeg = -1
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

    init {
        setWillNotDraw(false)
        setBackgroundColor(0)
        contentDescription = context.getString(if (available) R.string.element_compass else R.string.ea_no_compass)
    }

    override fun onLiveChanged(live: Boolean) {
        if (live) register() else unregister()
    }

    private fun register() {
        if (registered || !available) return
        val sm = sensorManager ?: return
        try {
            registered = if (rotationSensor != null) {
                sm.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
            } else {
                val a = sm.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
                val m = sm.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_UI)
                a || m
            }
        } catch (e: Exception) {
            registered = false
        }
    }

    private fun unregister() {
        if (!registered) return
        try { sensorManager?.unregisterListener(this) } catch (_: Exception) {}
        registered = false
        hasGravity = false
        hasGeomag = false
    }

    override fun onDetachedFromWindow() {
        unregister()
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val ok = when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                try {
                    SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                    true
                } catch (e: IllegalArgumentException) {
                    // Some devices deliver more than 4 values, which older implementations reject.
                    try {
                        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values.copyOf(4))
                        true
                    } catch (e2: Exception) { false }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(event.values, gravity, hasGravity); hasGravity = true
                computeFromRaw()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lowPass(event.values, geomag, hasGeomag); hasGeomag = true
                computeFromRaw()
            }
            else -> false
        }
        if (!ok) return
        remapForDisplay()
        SensorManager.getOrientation(remapped, orientation)
        var az = Math.toDegrees(orientation[0].toDouble()).toFloat()
        az = (az + 360f) % 360f
        heading = if (heading.isNaN()) az else {
            val delta = ((az - heading + 540f) % 360f) - 180f
            (heading + delta * SMOOTHING + 360f) % 360f
        }
        val deg = heading.roundToInt() % 360
        if (deg != shownDeg) {
            shownDeg = deg
            contentDescription = context.getString(R.string.ea_compass_cd, deg, cardinal(deg))
        }
        invalidate()
    }

    private fun lowPass(input: FloatArray, out: FloatArray, primed: Boolean) {
        for (i in 0 until 3) out[i] = if (primed) out[i] + RAW_ALPHA * (input[i] - out[i]) else input[i]
    }

    private fun computeFromRaw(): Boolean =
        hasGravity && hasGeomag && SensorManager.getRotationMatrix(rotMatrix, null, gravity, geomag)

    private fun remapForDisplay() {
        val rotation = try { display?.rotation ?: Surface.ROTATION_0 } catch (e: Exception) { Surface.ROTATION_0 }
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        if (!SensorManager.remapCoordinateSystem(rotMatrix, x, y, remapped)) {
            System.arraycopy(rotMatrix, 0, remapped, 0, 9)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val t = sensor?.type ?: return
        if (t == Sensor.TYPE_ROTATION_VECTOR || t == Sensor.TYPE_MAGNETIC_FIELD) {
            if (this.accuracy != accuracy) {
                this.accuracy = accuracy
                invalidate()
            }
        }
    }

    private val needsCalibration: Boolean
        get() = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW

    private fun cardinal(deg: Int): String {
        val names = cardinals
        val idx = (((deg % 360) + 22.5f) / 45f).toInt() % 8
        return names.getOrElse(idx) { "" }
    }

    override fun onAccentChanged(color: Int) {
        p.setAccent(color)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val headerH = if (h > EaKit.dp(context, 110f)) p.label.textSize * 1.9f else 0f
        if (headerH > 0) ElementUi.drawHeader(canvas, 0f, p.label.textSize, context.getString(R.string.element_compass), p.accent, p.label)
        val top = headerH
        val areaH = h - top

        val wide = w > areaH * 1.6f
        val size = if (wide) areaH else minOf(w, areaH)
        val cx = if (wide) size / 2f else w / 2f
        val cy = top + areaH / 2f
        val dotR = (size / 50f).coerceAtLeast(EaKit.dp(context, 1.1f))
        val radius = size / 2f - dotR * 2.2f

        if (!available) {
            EaKit.drawRing(canvas, cx, cy, radius, 36, 0, dotR, p.on, p.off)
            EaKit.drawFitText(canvas, context.getString(R.string.ea_no_compass).uppercase(), cx, cy + msgPaint.textSize / 3f, radius * 1.5f, msgPaint)
            return
        }

        val hdg = if (heading.isNaN()) 0f else heading
        // Rose: dots every 10°, rotated so that world north sits at (-heading) from the top.
        val count = 36
        for (i in 0 until count) {
            val worldDeg = i * 10f
            val screenDeg = worldDeg - hdg - 90f
            val a = Math.toRadians(screenDeg.toDouble())
            val major = i % 9 == 0
            val r = if (major) dotR * 1.25f else dotR * 0.8f
            val paint = when {
                i == 0 && !heading.isNaN() -> p.accent
                major -> p.on
                else -> p.off
            }
            val x = cx + radius * cos(a).toFloat()
            val y = cy + radius * sin(a).toFloat()
            canvas.drawCircle(x, y, if (i == 0) dotR * 1.8f else r, paint)
        }
        // Cardinal letters inside the ring when there is room.
        if (size > EaKit.dp(context, 110f) && !heading.isNaN()) {
            val names = cardinals
            val lr = radius - dotR * 2f - cardinalPaint.textSize
            for (k in 0 until 4) {
                val a = Math.toRadians((k * 90f - hdg - 90f).toDouble())
                val x = cx + lr * cos(a).toFloat()
                val y = cy + lr * sin(a).toFloat() + cardinalPaint.textSize / 3f
                cardinalPaint.color = if (k == 0) p.accent.color else secondaryColor
                canvas.drawText(names.getOrElse(k * 2) { "" }, x, y, cardinalPaint)
            }
        }
        // Fixed lubber mark at the top: where the phone points.
        canvas.drawCircle(cx, cy - radius + dotR * 2.8f, dotR * 0.6f, p.on)

        val degText = if (heading.isNaN()) "---" else "${shownDeg.coerceAtLeast(0)}°"
        val card = if (heading.isNaN()) "" else cardinal(shownDeg.coerceAtLeast(0))
        val sub = if (needsCalibration) context.getString(R.string.ea_calibrate) else card

        if (wide) {
            val tx = size + EaKit.dp(context, 14f)
            val tw = w - tx
            if (tw <= 0) return
            val pitch = ElementUi.fitPitch(degText, tw, areaH * 0.36f)
            val dotH = pitch * 7
            val blockTop = cy - (dotH + p.labelPrimary.textSize * 2.6f) / 2f
            ElementUi.drawDotText(canvas, degText, tx, blockTop, pitch, p.on)
            if (card.isNotEmpty()) EaKit.drawFitText(canvas, card, tx, blockTop + dotH + p.labelPrimary.textSize * 1.4f, tw, p.labelPrimary, Paint.Align.LEFT)
            if (needsCalibration) {
                p.label.color = p.accent.color
                EaKit.drawFitText(canvas, context.getString(R.string.ea_calibrate).uppercase(), tx, blockTop + dotH + p.labelPrimary.textSize * 2.7f, tw, p.label, Paint.Align.LEFT)
                p.label.color = secondaryColor
            }
        } else {
            val labelInset = if (size > EaKit.dp(context, 110f)) cardinalPaint.textSize * 2.2f else 0f
            val inner = (radius - dotR * 2f - labelInset) * 2f * 0.85f
            if (inner <= 0) return
            val maxH = inner * 0.32f
            val pitch = ElementUi.fitPitch(degText, inner, maxH)
            val dotH = pitch * 7
            val showSub = sub.isNotEmpty() && inner > p.label.textSize * 4
            val gap = p.label.textSize * 1.5f
            val blockTop = cy - (dotH + if (showSub) gap else 0f) / 2f
            EaKit.drawDotCentered(canvas, degText, cx, blockTop, inner, maxH, p.on)
            if (showSub) {
                if (needsCalibration) p.label.color = p.accent.color
                EaKit.drawFitText(canvas, sub.uppercase(), cx, blockTop + dotH + gap, inner, p.label)
                p.label.color = secondaryColor
            }
        }
    }

    companion object {
        private const val SMOOTHING = 0.15f
        private const val RAW_ALPHA = 0.2f
    }
}
