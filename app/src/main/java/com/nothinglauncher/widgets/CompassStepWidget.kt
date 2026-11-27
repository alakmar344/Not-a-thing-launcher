package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.DotRingGaugeView
import com.nothinglauncher.R
import com.nothinglauncher.Tactile
import kotlin.math.roundToInt

/**
 * 2x2 Compass Rose & Step Counter tile.
 * Strictly registers sensors ONLY while attached AND visible on screen (`onVisibilityAggregated`),
 * completely unregistering when home is obscured or on another workspace page.
 */
class CompassStepWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_compass
    override val descriptionRes = R.string.element_compass_sub
    override val previewRes = R.drawable.preview_widget_compass
    override val spanX = 2
    override val spanY = 2
    override val minSpanX = 2
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val sm = ctx.getSystemService(SensorManager::class.java)
        var stepMode = false
        var azimuth = 0f
        var stepsSinceBoot = -1
        var stepBaseline = -1
        var registered = false

        lateinit var cardRoot: View
        lateinit var dot: View
        lateinit var label: TextView
        lateinit var ring: DotRingGaugeView
        lateinit var footer: TextView

        fun cardinal(deg: Float): String = when (((deg + 22.5f).toInt() % 360) / 45) {
            0 -> "N"; 1 -> "NE"; 2 -> "E"; 3 -> "SE"
            4 -> "S"; 5 -> "SW"; 6 -> "W"; else -> "NW"
        }

        fun bind() {
            val accent = AccentTheme.accentColor(ctx)
            AccentTheme.applyWidgetSurface(cardRoot, ctx, "compass", defaultLight = false)
            val sc = AccentTheme.surfaceColors(ctx, "compass", defaultLight = false)
            AccentTheme.tintDot(dot, ctx)
            label.setTextColor(sc.textSecondary)
            footer.setTextColor(sc.textSecondary)
            ring.accentColor = accent
            ring.applySurfaceColors(sc)
            if (!stepMode) {
                label.setText(R.string.compass_label)
                val d = ((azimuth % 360f) + 360f) % 360f
                ring.compassDegrees = d
                ring.centerText = "${d.roundToInt()}\u00B0"
                ring.subtitle = cardinal(d)
                footer.setText(R.string.compass_tap_steps)
            } else {
                label.setText(R.string.steps_label)
                ring.compassDegrees = null
                val today = if (stepsSinceBoot >= 0 && stepBaseline >= 0) {
                    (stepsSinceBoot - stepBaseline).coerceAtLeast(0)
                } else 0
                ring.progress01 = (today / 8000f).coerceIn(0f, 1f)
                ring.highlightActive = today >= 8000
                ring.centerText = if (today >= 1000) "${today / 1000}K" else today.toString()
                ring.subtitle = "STEPS"
                footer.text = "$today / 8000"
            }
        }

        val rotMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                        SensorManager.getOrientation(rotMatrix, orientation)
                        val deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        if (kotlin.math.abs(deg - azimuth) >= 1.5f) {
                            azimuth = deg
                            if (!stepMode) bind()
                        }
                    }
                    Sensor.TYPE_STEP_COUNTER -> {
                        val total = event.values.firstOrNull()?.toInt() ?: return
                        if (stepBaseline < 0) stepBaseline = total
                        stepsSinceBoot = total
                        if (stepMode) bind()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        fun updateSensorRegistration(visible: Boolean) {
            if (sm == null) return
            if (visible && !registered) {
                sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                    sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
                }
                sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                    sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
                }
                registered = true
            } else if (!visible && registered) {
                sm.unregisterListener(sensorListener)
                registered = false
            }
        }

        val prefs = com.nothinglauncher.NothingPrefs.get(ctx)
        lateinit var wrapper: LifecycleFrameLayout
        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (wrapper.isAttachedToWindow) bind()
        }
        wrapper = LifecycleFrameLayout(ctx) { visible ->
            updateSensorRegistration(visible)
        }.apply {
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    prefs.addListener(prefListener)
                    bind()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    prefs.removeListener(prefListener)
                }
            })
        }

        cardRoot = LayoutInflater.from(ctx).inflate(R.layout.widget_vitals, wrapper, false)
        wrapper.addView(cardRoot)
        dot = cardRoot.findViewById(R.id.vitalsDot)
        label = cardRoot.findViewById(R.id.vitalsLabel)
        ring = cardRoot.findViewById(R.id.vitalsRing)
        footer = cardRoot.findViewById(R.id.vitalsFooter)

        cardRoot.setOnClickListener {
            Tactile.tick(it)
            stepMode = !stepMode
            bind()
        }
        bind()
        return wrapper
    }
}
