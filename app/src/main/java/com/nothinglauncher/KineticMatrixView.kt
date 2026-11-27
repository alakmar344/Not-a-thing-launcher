package com.nothinglauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.nothinglauncher.perf.VisualTier
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Interactive 16x10 LED matrix playground supporting 3 modes:
 * - [Mode.GRAVITY]: accelerometer tilt-driven rolling LED beads with finger attract/fling physics
 * - [Mode.LIFE]: Conway's Game of Life cellular simulation (tap any dot to toggle cells alive/dead)
 * - [Mode.SNAKE]: Playable home-screen / desk-mode Dot Snake (tap edges relative to center to steer)
 *
 * Strictly visibility- and [VisualTier.motionAllowed]-gated: zero sensor/frame drain off-screen.
 */
class KineticMatrixView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    enum class Mode(val prefValue: Int) {
        GRAVITY(0),
        LIFE(1),
        SNAKE(2);

        companion object {
            fun fromPref(v: Int): Mode = entries.firstOrNull { it.prefValue == v } ?: GRAVITY
        }
    }

    companion object {
        const val COLS = 16
        const val ROWS = 10
        private const val BEAD_COUNT = 18
    }

    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_off)
    }
    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_on)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nothing_red)
    }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    fun applySurfaceColors(sc: AccentTheme.SurfaceColors) {
        onPaint.color = sc.onDot
        offPaint.color = sc.offDot
        invalidate()
    }

    var mode: Mode = Mode.GRAVITY
        set(value) {
            if (field != value) {
                field = value
                if (value == Mode.LIFE && lifeAliveCount() == 0) seedLife()
                if (value == Mode.SNAKE) resetSnake()
                updateRunning()
                invalidate()
            }
        }

    var onScoreChanged: ((Int) -> Unit)? = null

    // --- Gravity Beads State (normalized grid coordinates 0..COLS, 0..ROWS) ---
    internal val beadX = FloatArray(BEAD_COUNT) { 2f + (it % 6) * 2.2f }
    internal val beadY = FloatArray(BEAD_COUNT) { 2f + (it / 6) * 2.4f }
    private val beadVx = FloatArray(BEAD_COUNT)
    private val beadVy = FloatArray(BEAD_COUNT)
    private var accelGx = 0f
    private var accelGy = 3.2f
    private var touchActive = false
    private var touchGridX = COLS * 0.5f
    private var touchGridY = ROWS * 0.5f

    // --- Game of Life State ---
    internal var lifeGrid = BooleanArray(COLS * ROWS)
    private var nextLifeGrid = BooleanArray(COLS * ROWS)

    // --- Snake State ---
    internal val snakeBody = ArrayDeque<Pair<Int, Int>>()
    internal var snakeDirX = 1
    internal var snakeDirY = 0
    private var committedSnakeDirX = 1
    private var committedSnakeDirY = 0
    internal var snakeFood = Pair(11, 5)
    internal var snakeScore = 0
    internal var snakeGameOver = false

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var sensorRegistered = false
    private var running = false

    init {
        seedLife()
        resetSnake()
    }

    fun seedLife() {
        lifeGrid.fill(false)
        // Iconic R-pentomino + glider pair in crisp center region
        val cells = listOf(
            7 to 4, 8 to 4, 6 to 5, 7 to 5, 7 to 6,
            2 to 2, 3 to 3, 1 to 4, 2 to 4, 3 to 4,
            12 to 6, 13 to 6, 14 to 6
        )
        cells.forEach { (c, r) ->
            if (c in 0 until COLS && r in 0 until ROWS) lifeGrid[r * COLS + c] = true
        }
        invalidate()
    }

    private fun lifeAliveCount(): Int = lifeGrid.count { it }

    fun stepLife() {
        var aliveCount = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                var n = 0
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val rr = (r + dr + ROWS) % ROWS
                        val cc = (c + dc + COLS) % COLS
                        if (lifeGrid[rr * COLS + cc]) n++
                    }
                }
                val alive = lifeGrid[r * COLS + c]
                val next = if (alive) (n == 2 || n == 3) else (n == 3)
                nextLifeGrid[r * COLS + c] = next
                if (next) aliveCount++
            }
        }
        val tmp = lifeGrid
        lifeGrid = nextLifeGrid
        nextLifeGrid = tmp
        if (aliveCount == 0) seedLife()
    }

    fun resetSnake() {
        snakeBody.clear()
        snakeBody.addLast(5 to 5)
        snakeBody.addLast(6 to 5)
        snakeBody.addLast(7 to 5)
        snakeDirX = 1
        snakeDirY = 0
        committedSnakeDirX = 1
        committedSnakeDirY = 0
        snakeScore = 0
        snakeGameOver = false
        placeFoodDeterministic(1)
        onScoreChanged?.invoke(snakeScore)
    }

    internal fun steerSnake(ndx: Int, ndy: Int) {
        if (ndx == 0 && ndy == 0) return
        if (ndx != 0 && ndy != 0) return
        // Guard against the direction already committed on the current grid tick so rapid
        // double-taps (e.g. Right -> Up -> Left within 210 ms) cannot 180-reverse into the neck.
        if (ndx == -committedSnakeDirX && ndy == -committedSnakeDirY) return
        snakeDirX = ndx
        snakeDirY = ndy
    }

    private fun placeFoodDeterministic(salt: Int) {
        for (i in 0 until COLS * ROWS) {
            val idx = ((snakeScore * 17 + salt * 31 + i * 7) % (COLS * ROWS) + COLS * ROWS) % (COLS * ROWS)
            val candidate = (idx % COLS) to (idx / COLS)
            if (candidate !in snakeBody) {
                snakeFood = candidate
                return
            }
        }
    }

    fun stepSnake() {
        if (snakeGameOver) return
        committedSnakeDirX = snakeDirX
        committedSnakeDirY = snakeDirY
        val head = snakeBody.last()
        val nx = (head.first + snakeDirX + COLS) % COLS
        val ny = (head.second + snakeDirY + ROWS) % ROWS
        val next = nx to ny
        if (next in snakeBody) {
            snakeGameOver = true
            invalidate()
            return
        }
        snakeBody.addLast(next)
        if (next == snakeFood) {
            snakeScore++
            onScoreChanged?.invoke(snakeScore)
            placeFoodDeterministic(nx + ny + 3)
        } else {
            snakeBody.removeFirst()
        }
    }

    fun stepPhysics(gx: Float, gy: Float, dt: Float = 0.032f) {
        val margin = 0.65f
        val damping = 0.94f
        for (i in 0 until BEAD_COUNT) {
            beadVx[i] = (beadVx[i] + gx * dt) * damping
            beadVy[i] = (beadVy[i] + gy * dt) * damping
            if (touchActive) {
                val dx = touchGridX - beadX[i]
                val dy = touchGridY - beadY[i]
                val d = hypot(dx, dy).coerceAtLeast(0.45f)
                if (d < 5.5f) {
                    beadVx[i] += (dx / d) * 14f * dt
                    beadVy[i] += (dy / d) * 14f * dt
                }
            }
            beadX[i] += beadVx[i] * dt * 5f
            beadY[i] += beadVy[i] * dt * 5f

            if (beadX[i] < margin) { beadX[i] = margin; beadVx[i] = abs(beadVx[i]) * 0.65f }
            if (beadX[i] > COLS - margin) { beadX[i] = COLS - margin; beadVx[i] = -abs(beadVx[i]) * 0.65f }
            if (beadY[i] < margin) { beadY[i] = margin; beadVy[i] = abs(beadVy[i]) * 0.65f }
            if (beadY[i] > ROWS - margin) { beadY[i] = ROWS - margin; beadVy[i] = -abs(beadVy[i]) * 0.65f }
        }
        // Soft bead-bead collision avoidance
        val minDist = 1.05f
        for (i in 0 until BEAD_COUNT) {
            for (j in i + 1 until BEAD_COUNT) {
                val dx = beadX[j] - beadX[i]
                val dy = beadY[j] - beadY[i]
                val dist = hypot(dx, dy)
                if (dist > 0.001f && dist < minDist) {
                    val push = (minDist - dist) * 0.45f
                    val nx = dx / dist
                    val ny = dy / dist
                    beadX[i] -= nx * push
                    beadY[i] -= ny * push
                    beadX[j] += nx * push
                    beadY[j] += ny * push
                }
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            when (mode) {
                Mode.GRAVITY -> {
                    stepPhysics(accelGx, accelGy)
                    invalidate()
                    postDelayed(this, 32L)
                }
                Mode.LIFE -> {
                    stepLife()
                    invalidate()
                    postDelayed(this, 240L)
                }
                Mode.SNAKE -> {
                    stepSnake()
                    invalidate()
                    postDelayed(this, 210L)
                }
            }
        }
    }

    private fun updateRunning() {
        val allowed = VisualTier.motionAllowed(context)
        val shouldRun = allowed && isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE && isShown
        val wantSensor = shouldRun && mode == Mode.GRAVITY && accelSensor != null
        if (wantSensor && !sensorRegistered) {
            sensorRegistered = sensorManager?.registerListener(
                this, accelSensor, SensorManager.SENSOR_DELAY_GAME
            ) == true
        } else if (!wantSensor && sensorRegistered) {
            sensorManager?.unregisterListener(this)
            sensorRegistered = false
        }
        if (shouldRun == running) return
        running = shouldRun
        removeCallbacks(ticker)
        if (running) post(ticker)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(ticker)
        if (sensorRegistered) {
            sensorManager?.unregisterListener(this)
            sensorRegistered = false
        }
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateRunning() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); updateRunning() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateRunning() }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        // Android coords: tilt right -> ax < 0, tilt down -> ay > 0
        accelGx = (-event.values[0]).coerceIn(-12f, 12f)
        accelGy = event.values[1].coerceIn(-12f, 12f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val gx = ((event.x / w) * COLS).coerceIn(0f, COLS - 0.01f)
        val gy = ((event.y / h) * ROWS).coerceIn(0f, ROWS - 0.01f)

        when (mode) {
            Mode.GRAVITY -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) Tactile.tick(this)
                        touchActive = true
                        touchGridX = gx
                        touchGridY = gy
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touchActive = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
            }
            Mode.LIFE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    Tactile.tick(this)
                    val col = gx.toInt().coerceIn(0, COLS - 1)
                    val row = gy.toInt().coerceIn(0, ROWS - 1)
                    lifeGrid[row * COLS + col] = !lifeGrid[row * COLS + col]
                    invalidate()
                    return true
                }
            }
            Mode.SNAKE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    Tactile.tick(this)
                    if (snakeGameOver) {
                        resetSnake()
                        invalidate()
                        return true
                    }
                    val cx = event.x - w * 0.5f
                    val cy = event.y - h * 0.5f
                    if (abs(cx) > abs(cy)) {
                        steerSnake(if (cx >= 0f) 1 else -1, 0)
                    } else {
                        steerSnake(0, if (cy >= 0f) 1 else -1)
                    }
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cellW = w / COLS
        val cellH = h / ROWS
        val baseR = min(cellW, cellH) * 0.24f

        // Base off-dot lattice across all modes
        for (r in 0 until ROWS) {
            val cy = (r + 0.5f) * cellH
            for (c in 0 until COLS) {
                val cx = (c + 0.5f) * cellW
                when (mode) {
                    Mode.GRAVITY -> canvas.drawCircle(cx, cy, baseR * 0.72f, offPaint)
                    Mode.LIFE -> {
                        val alive = lifeGrid[r * COLS + c]
                        val isSeedAccent = alive && ((c + r) % 7 == 0)
                        val p = if (isSeedAccent) accentPaint else if (alive) onPaint else offPaint
                        canvas.drawCircle(cx, cy, if (alive) baseR * 1.45f else baseR * 0.72f, p)
                    }
                    Mode.SNAKE -> {
                        val cell = c to r
                        val isFood = cell == snakeFood
                        val isHead = snakeBody.lastOrNull() == cell
                        val isBody = !isHead && cell in snakeBody
                        val p = when {
                            isFood -> accentPaint
                            isHead || isBody -> onPaint
                            else -> offPaint
                        }
                        val rad = when {
                            isFood -> baseR * 1.5f
                            isHead -> baseR * 1.55f
                            isBody -> baseR * 1.3f
                            else -> baseR * 0.72f
                        }
                        canvas.drawCircle(cx, cy, rad, p)
                    }
                }
            }
        }

        if (mode == Mode.GRAVITY) {
            val beadR = min(cellW, cellH) * 0.42f
            for (i in 0 until BEAD_COUNT) {
                val px = beadX[i] * cellW
                val py = beadY[i] * cellH
                val paint = if (i < 3) accentPaint else onPaint
                canvas.drawCircle(px, py, if (i < 3) beadR * 1.12f else beadR, paint)
            }
        }
    }
}
