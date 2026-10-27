package com.nothinglauncher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shared step-counter bridge. The hardware reports steps since boot, so the first reading
 * of each day becomes a baseline and the UI shows the honest difference. Nothing is
 * estimated when the sensor or the motion permission is unavailable.
 */
object StepRepository {

    data class State(
        val sensorAvailable: Boolean = false,
        val permissionGranted: Boolean = false,
        val steps: Int = 0,
        val goal: Int = 10000
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var registered = false
    private var paused = false
    private var latestTotal = -1
    private var state = State()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
            latestTotal = event.values.firstOrNull()?.toInt() ?: return
            publishSteps()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun addListener(context: Context, callback: (State) -> Unit): State {
        appContext = context.applicationContext
        listeners += callback
        ensureSensor()
        refresh()
        // Skip the replay if the caller was unbound before the post ran.
        main.post { if (callback in listeners) callback(state) }
        return state
    }

    fun removeListener(callback: (State) -> Unit) {
        listeners -= callback
        if (listeners.isEmpty()) unregister()
    }

    fun pause() {
        if (!paused) {
            paused = true
            unregister()
        }
    }

    fun resume() {
        if (paused) {
            paused = false
            if (listeners.isNotEmpty()) {
                ensureSensor()
                refresh()
            }
        }
    }

    fun refresh() {
        val ctx = appContext
        if (ctx == null) {
            update(State())
            return
        }
        val prefs = LauncherPrefs(ctx)
        val permission = LauncherSystem.hasActivityRecognition(ctx)
        if (!permission) {
            unregister()
            update(State(sensorAvailable = stepSensor != null, permissionGranted = false, goal = prefs.stepGoal))
            return
        }
        ensureSensor()
        if (latestTotal < 0) {
            update(
                State(
                    sensorAvailable = stepSensor != null,
                    permissionGranted = true,
                    steps = 0,
                    goal = prefs.stepGoal
                )
            )
        } else {
            publishSteps()
        }
    }

    private fun ensureSensor() {
        val ctx = appContext ?: return
        val sm = sensorManager ?: (ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager).also {
            sensorManager = it
        }
        val sensor = stepSensor ?: sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER).also {
            stepSensor = it
        }
        if (sensor == null) {
            update(State(sensorAvailable = false, permissionGranted = LauncherSystem.hasActivityRecognition(ctx)))
            return
        }
        // Only hold the sensor while something on screen is listening and not paused
        if (!registered && !paused && listeners.isNotEmpty() && LauncherSystem.hasActivityRecognition(ctx)) {
            registered = try {
                sm?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 200_000) == true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun unregister() {
        if (registered) {
            try {
                sensorManager?.unregisterListener(listener)
            } catch (_: Exception) {
            }
            registered = false
        }
    }

    private fun publishSteps() {
        val ctx = appContext ?: return
        val prefs = LauncherPrefs(ctx)
        val today = LocalDate.now().toEpochDay()
        var baseline = prefs.stepBaseline
        if (prefs.stepDay != today || baseline < 0 || latestTotal < baseline) {
            // First reading today (or after a reboot): count from this moment onward.
            baseline = latestTotal
            prefs.stepBaseline = baseline
            prefs.stepDay = today
        }
        val steps = (latestTotal - baseline).coerceAtLeast(0)
        update(
            State(
                sensorAvailable = true,
                permissionGranted = true,
                steps = steps,
                goal = prefs.stepGoal
            )
        )
    }

    private fun update(newState: State) {
        state = newState
        main.post { listeners.forEach { it(newState) } }
    }
}

/**
 * Heading provider. Rotation vector is preferred; accelerometer + magnetometer is the
 * compatibility path. Values are smoothed before publication to keep the dial calm.
 */
object CompassRepository {

    data class State(
        val sensorAvailable: Boolean = false,
        val heading: Int = 0,
        val direction: String = "N",
        val calibrating: Boolean = true
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var registered = false
    private var paused = false
    private var state = State()

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false
    private var smoothedHeading = -1f
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

    private fun directionFor(heading: Int): String {
        val labels = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return labels[((heading + 22.5f) / 45f).toInt().mod(8)]
    }

    private fun publishAzimuth(azimuthRadians: Float, status: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH) {
        accuracy = if (status == SensorManager.SENSOR_STATUS_UNRELIABLE) status else SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        var degrees = ((Math.toDegrees(azimuthRadians.toDouble()) + 360.0) % 360.0).toFloat()
        if (smoothedHeading < 0f) {
            smoothedHeading = degrees
        } else {
            var delta = degrees - smoothedHeading
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            smoothedHeading = (smoothedHeading + delta * 0.18f + 360f) % 360f
            degrees = smoothedHeading
        }
        val heading = degrees.roundToInt().mod(360)
        update(
            State(
                sensorAvailable = true,
                heading = heading,
                direction = directionFor(heading),
                calibrating = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
            )
        )
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    try {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        publishAzimuth(orientation[0], event.accuracy)
                    } catch (_: Exception) {
                    }
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    lowPass(event.values, gravity)
                    hasGravity = true
                    updateFallback()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    lowPass(event.values, geomagnetic)
                    hasGeomagnetic = true
                    updateFallback()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, newAccuracy: Int) {
            if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                accuracy = newAccuracy
            }
        }
    }

    private fun lowPass(input: FloatArray, output: FloatArray) {
        for (i in 0 until minOf(input.size, output.size, 3)) {
            output[i] += (input[i] - output[i]) * 0.25f
        }
    }

    private fun updateFallback() {
        if (!hasGravity || !hasGeomagnetic) return
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            SensorManager.getOrientation(rotationMatrix, orientation)
            publishAzimuth(orientation[0])
        }
    }

    fun addListener(context: Context, callback: (State) -> Unit): State {
        appContext = context.applicationContext
        listeners += callback
        ensureSensors()
        main.post { if (callback in listeners) callback(state) }
        return state
    }

    fun removeListener(callback: (State) -> Unit) {
        listeners -= callback
        if (listeners.isEmpty()) {
            unregisterSensors()
        }
    }

    fun pause() {
        if (!paused) {
            paused = true
            unregisterSensors()
        }
    }

    fun resume() {
        if (paused) {
            paused = false
            if (listeners.isNotEmpty()) {
                ensureSensors()
            }
        }
    }

    private fun unregisterSensors() {
        if (registered) {
            try {
                sensorManager?.unregisterListener(listener)
            } catch (_: Exception) {
            }
            registered = false
            hasGravity = false
            hasGeomagnetic = false
            smoothedHeading = -1f
        }
    }

    fun refresh() {
        ensureSensors()
    }

    private fun ensureSensors() {
        val ctx = appContext ?: return
        val sm = sensorManager ?: (ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager).also {
            sensorManager = it
        } ?: run {
            update(State(sensorAvailable = false))
            return
        }
        rotationSensor = rotationSensor ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelerometer = accelerometer ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = magnetometer ?: sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val hasRotation = rotationSensor != null
        val hasFallback = accelerometer != null && magnetometer != null
        if (!hasRotation && !hasFallback) {
            update(State(sensorAvailable = false, calibrating = false))
            return
        }
        // Rotation vector / magnetometer are power hungry: never register without a listener or when paused.
        if (!registered && !paused && listeners.isNotEmpty()) {
            registered = try {
                if (hasRotation) {
                    sm.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
                } else {
                    val ok = sm.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI) &&
                        sm.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_UI)
                    if (!ok) sm.unregisterListener(listener)
                    ok
                }
            } catch (e: Exception) {
                false
            }
        }
        if (!state.sensorAvailable) {
            update(state.copy(sensorAvailable = true))
        }
    }

    private fun update(newState: State) {
        state = newState
        main.post { listeners.forEach { it(newState) } }
    }
}
