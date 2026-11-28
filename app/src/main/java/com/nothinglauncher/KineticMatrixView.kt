package com.nothinglauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.nothinglauncher.perf.VisualTier
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Interactive 16x10 LED matrix playground supporting 3 modes:
 * - [Mode.GRAVITY]: tilt-driven rolling LED beads with full-grid finger attract & fling physics
 * - [Mode.LIFE]: Conway's Game of Life cellular simulation (tap or drag cells to paint, auto-reseeds if stagnant)
 * - [Mode.SNAKE]: Playable home-screen Dot Snake (swipe in any direction or tap normalized edge quadrants to steer)
 *
 * Claims pointer gestures from launcher widget hosts on ACTION_DOWN (cancelling host long-press & page swipes),
 * pauses only during layoutlib tests or when detached/invisible, and unregisters sensors off-screen.
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
        private const val LIFE_PAUSE_AFTER_TOUCH_MS = 650L
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
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dot_on)
        alpha = 115
    }

    var accentColor: Int
        get() = accentPaint.color
        set(value) { accentPaint.color = value; invalidate() }

    fun applySurfaceColors(sc: AccentTheme.SurfaceColors) {
        onPaint.color = sc.onDot
        dimPaint.color = sc.onDot
        dimPaint.alpha = 115
        offPaint.color = sc.offDot
        invalidate()
    }

    var mode: Mode = Mode.GRAVITY
        set(value) {
            if (field != value) {
                field = value
                touchActive = false
                if (value == Mode.GRAVITY) kickGravityBeads()
                if (value == Mode.LIFE && lifeAliveCount() == 0) seedLife()
                if (value == Mode.SNAKE) resetSnake()
                updateRunning()
                invalidate()
            }
        }

    var onScoreChanged: ((Int) -> Unit)? = null
    var onGameOverChanged: ((Boolean, Int) -> Unit)? = null

    // --- Gravity Beads State (normalized grid coordinates 0..COLS, 0..ROWS) ---
    internal val beadX = FloatArray(BEAD_COUNT) { 2f + (it % 6) * 2.2f }
    internal val beadY = FloatArray(BEAD_COUNT) { 2f + (it / 6) * 2.4f }
    private val beadVx = FloatArray(BEAD_COUNT)
    private val beadVy = FloatArray(BEAD_COUNT)
    private var accelGx = 0f
    private var accelGy = 3.2f
    private var hasSensorSample = false
    private var gravityPhase = 0f
    private var touchActive = false
    private var touchGridX = COLS * 0.5f
    private var touchGridY = ROWS * 0.5f
    private var dragImpulseVx = 0f
    private var dragImpulseVy = 0f

    // --- Game of Life State ---
    internal var lifeGrid = BooleanArray(COLS * ROWS)
    private var nextLifeGrid = BooleanArray(COLS * ROWS)
    private var lifePaintValue = true
    private var lifeLastPaintIndex = -1
    private var lifePausedUntilMs = 0L
    private var prevLifeHash1 = 0
    private var prevLifeHash2 = 0
    private var stagnantLifeTicks = 0
    private var reseedCursor = 0

    // --- Snake State ---
    internal val snakeBody = ArrayDeque<Pair<Int, Int>>()
    internal var snakeDirX = 1
    internal var snakeDirY = 0
    private var committedSnakeDirX = 1
    private var committedSnakeDirY = 0
    internal var snakeFood = Pair(11, 5)
    internal var snakeScore = 0
    internal var snakeGameOver = false
    private var snakeDownX = 0f
    private var snakeDownY = 0f
    private var snakeSwiped = false

    private var geomW = -1
    private var geomH = -1
    private val cellCx = FloatArray(COLS)
    private val cellCy = FloatArray(ROWS)
    private val snakeDrawFlags = ByteArray(COLS * ROWS)
    private var cachedDisplayRotation = Surface.ROTATION_0

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceAtLeast(8f)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var sensorRegistered = false
    private var running = false
    private var reportedWindowVisible: Boolean? = null

    init {
        kickGravityBeads()
        seedLife()
        resetSnake()
    }

    private fun kickGravityBeads() {
        for (i in 0 until BEAD_COUNT) {
            beadVx[i] = ((i % 5) - 2) * 0.7f
            beadVy[i] = ((i / 5) - 1.5f) * 0.6f
        }
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
        stagnantLifeTicks = 0
        prevLifeHash1 = 0
        prevLifeHash2 = 0
        invalidate()
    }

    private fun injectLifeGlider() {
        reseedCursor = (reseedCursor + 1) % 4
        val (ox, oy) = when (reseedCursor) {
            0 -> 2 to 2
            1 -> 11 to 2
            2 -> 2 to 6
            else -> 9 to 5
        }
        val glider = listOf(1 to 0, 2 to 1, 0 to 2, 1 to 2, 2 to 2, 4 to 1, 5 to 1)
        glider.forEach { (dx, dy) ->
            val cc = (ox + dx) % COLS
            val rr = (oy + dy) % ROWS
            lifeGrid[rr * COLS + cc] = true
        }
        stagnantLifeTicks = 0
    }

    private fun lifeAliveCount(): Int = lifeGrid.count { it }

    fun stepLife() {
        stepLifeInternal()
    }

    private fun stepLifeInternal(): Int {
        var aliveCount = 0
        var hash = 1
        for (r in 0 until ROWS) {
            val rowOff = r * COLS
            val rUp = ((r + ROWS - 1) % ROWS) * COLS
            val rDown = ((r + 1) % ROWS) * COLS
            for (c in 0 until COLS) {
                val cLeft = (c + COLS - 1) % COLS
                val cRight = (c + 1) % COLS
                var n = 0
                if (lifeGrid[rUp + cLeft]) n++
                if (lifeGrid[rUp + c]) n++
                if (lifeGrid[rUp + cRight]) n++
                if (lifeGrid[rowOff + cLeft]) n++
                if (lifeGrid[rowOff + cRight]) n++
                if (lifeGrid[rDown + cLeft]) n++
                if (lifeGrid[rDown + c]) n++
                if (lifeGrid[rDown + cRight]) n++
                val idx = rowOff + c
                val alive = lifeGrid[idx]
                val next = if (alive) (n == 2 || n == 3) else (n == 3)
                nextLifeGrid[idx] = next
                hash = 31 * hash + if (next) 1231 else 1237
                if (next) aliveCount++
            }
        }
        val tmp = lifeGrid
        lifeGrid = nextLifeGrid
        nextLifeGrid = tmp
        if (aliveCount == 0) {
            seedLife()
            return lifeGrid.contentHashCode()
        }
        return hash
    }

    private fun advanceLifeInTicker() {
        val now = SystemClock.uptimeMillis()
        if (now < lifePausedUntilMs) return
        val beforeHash = lifeGrid.contentHashCode()
        val afterHash = stepLifeInternal()
        if (afterHash == beforeHash || afterHash == prevLifeHash2) {
            stagnantLifeTicks++
            if (stagnantLifeTicks >= 12) {
                if (lifeAliveCount() <= 4) seedLife() else injectLifeGlider()
            }
        } else {
            stagnantLifeTicks = 0
        }
        prevLifeHash2 = prevLifeHash1
        prevLifeHash1 = beforeHash
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
        onGameOverChanged?.invoke(false, snakeScore)
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
            onGameOverChanged?.invoke(true, snakeScore)
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
                val pull = 34f / (0.7f + d * 0.15f)
                beadVx[i] += (dx / d) * pull * dt + dragImpulseVx * 0.4f
                beadVy[i] += (dy / d) * pull * dt + dragImpulseVy * 0.4f
            }
            beadX[i] += beadVx[i] * dt * 5f
            beadY[i] += beadVy[i] * dt * 5f

            if (beadX[i] < margin) { beadX[i] = margin; beadVx[i] = abs(beadVx[i]) * 0.68f }
            if (beadX[i] > COLS - margin) { beadX[i] = COLS - margin; beadVx[i] = -abs(beadVx[i]) * 0.68f }
            if (beadY[i] < margin) { beadY[i] = margin; beadVy[i] = abs(beadVy[i]) * 0.68f }
            if (beadY[i] > ROWS - margin) { beadY[i] = ROWS - margin; beadVy[i] = -abs(beadVy[i]) * 0.68f }
        }
        dragImpulseVx *= 0.65f
        dragImpulseVy *= 0.65f
        // Soft bead-bead collision avoidance (squared distance avoids sqrt for non-colliding pairs).
        val minDist = 1.05f
        val minDistSq = minDist * minDist
        for (i in 0 until BEAD_COUNT) {
            val xi = beadX[i]
            val yi = beadY[i]
            for (j in i + 1 until BEAD_COUNT) {
                val dx = beadX[j] - xi
                val dy = beadY[j] - yi
                val distSq = dx * dx + dy * dy
                if (distSq > 0.000001f && distSq < minDistSq) {
                    val dist = kotlin.math.sqrt(distSq)
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
            val calmPower = !VisualTier.motionAllowed(context) && !touchActive
            when (mode) {
                Mode.GRAVITY -> {
                    gravityPhase += 0.08f
                    var gx = accelGx
                    var gy = accelGy
                    if (!touchActive && (!hasSensorSample || hypot(gx, gy) < 0.45f)) {
                        gx += sin(gravityPhase) * 1.4f
                        gy = 1.6f + cos(gravityPhase * 0.7f) * 1.1f
                    }
                    stepPhysics(gx, gy, dt = if (calmPower) 0.045f else 0.032f)
                    invalidate()
                    postDelayed(this, if (calmPower) 64L else 32L)
                }
                Mode.LIFE -> {
                    advanceLifeInTicker()
                    invalidate()
                    postDelayed(this, if (calmPower) 320L else 240L)
                }
                Mode.SNAKE -> {
                    stepSnake()
                    invalidate()
                    postDelayed(this, if (calmPower) 240L else 210L)
                }
            }
        }
    }

    private fun updateRunning() {
        val effectiveWindowVisible = reportedWindowVisible ?: (windowVisibility == VISIBLE)
        val shouldRun = !VisualTier.isTestOverrideActive() &&
            isAttachedToWindow &&
            effectiveWindowVisible &&
            visibility == VISIBLE &&
            isShown
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cachedDisplayRotation = currentDisplayRotation()
        updateRunning()
        post { if (isAttachedToWindow) updateRunning() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        cachedDisplayRotation = currentDisplayRotation()
    }

    override fun onDetachedFromWindow() {
        running = false
        reportedWindowVisible = null
        removeCallbacks(ticker)
        if (sensorRegistered) {
            sensorManager?.unregisterListener(this)
            sensorRegistered = false
        }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        reportedWindowVisible = (visibility == VISIBLE)
        updateRunning()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        reportedWindowVisible = isVisible
        updateRunning()
    }

    // Tell Launcher3 host & HomeGestureController this surface owns directional drags inside its bounds.
    override fun canScrollVertically(direction: Int): Boolean = true
    override fun canScrollHorizontally(direction: Int): Boolean = true

    private fun currentDisplayRotation(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return context.display.rotation
            } catch (_: Throwable) {}
        }
        return try {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation
                ?: Surface.ROTATION_0
        } catch (_: Throwable) {
            Surface.ROTATION_0
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        hasSensorSample = true
        val rawX = event.values[0]
        val rawY = event.values[1]
        val sx: Float
        val sy: Float
        when (cachedDisplayRotation) {
            Surface.ROTATION_90 -> { sx = -rawY; sy = -rawX }
            Surface.ROTATION_180 -> { sx = rawX; sy = -rawY }
            Surface.ROTATION_270 -> { sx = rawY; sy = rawX }
            else -> { sx = -rawX; sy = rawY }
        }
        accelGx = sx.coerceIn(-12f, 12f)
        accelGy = sy.coerceIn(-12f, 12f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun claimTouchFromHost(disallow: Boolean) {
        var p = parent
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(disallow)
            if (disallow && p is View) {
                p.cancelLongPress()
            }
            p = p.parent
        }
    }

    private fun steerSnakeQuadrant(eventX: Float, eventY: Float, w: Float, h: Float) {
        val nx = (eventX / w) - 0.5f
        val ny = (eventY / h) - 0.5f
        if (abs(nx) > abs(ny)) {
            steerSnake(if (nx >= 0f) 1 else -1, 0)
        } else {
            steerSnake(0, if (ny >= 0f) 1 else -1)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val gx = ((event.x / w) * COLS).coerceIn(0f, COLS - 0.01f)
        val gy = ((event.y / h) * ROWS).coerceIn(0f, ROWS - 0.01f)
        val action = event.actionMasked

        if (action == MotionEvent.ACTION_DOWN) {
            claimTouchFromHost(true)
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            claimTouchFromHost(false)
        }

        when (mode) {
            Mode.GRAVITY -> {
                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        Tactile.tick(this)
                        touchActive = true
                        touchGridX = gx
                        touchGridY = gy
                        dragImpulseVx = 0f
                        dragImpulseVy = 0f
                        if (!running) {
                            stepPhysics(accelGx, accelGy)
                        }
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        dragImpulseVx = ((gx - touchGridX) * 6f).coerceIn(-6f, 6f)
                        dragImpulseVy = ((gy - touchGridY) * 6f).coerceIn(-6f, 6f)
                        touchActive = true
                        touchGridX = gx
                        touchGridY = gy
                        if (!running) {
                            stepPhysics(accelGx, accelGy)
                        }
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touchActive = false
                        dragImpulseVx = 0f
                        dragImpulseVy = 0f
                        return true
                    }
                }
            }
            Mode.LIFE -> {
                val col = gx.toInt().coerceIn(0, COLS - 1)
                val row = gy.toInt().coerceIn(0, ROWS - 1)
                val idx = row * COLS + col
                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        Tactile.tick(this)
                        lifePaintValue = !lifeGrid[idx]
                        lifeGrid[idx] = lifePaintValue
                        lifeLastPaintIndex = idx
                        lifePausedUntilMs = SystemClock.uptimeMillis() + LIFE_PAUSE_AFTER_TOUCH_MS
                        stagnantLifeTicks = 0
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lifePausedUntilMs = SystemClock.uptimeMillis() + LIFE_PAUSE_AFTER_TOUCH_MS
                        if (idx != lifeLastPaintIndex) {
                            lifeLastPaintIndex = idx
                            lifeGrid[idx] = lifePaintValue
                            stagnantLifeTicks = 0
                            invalidate()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        lifeLastPaintIndex = -1
                        lifePausedUntilMs = SystemClock.uptimeMillis() + LIFE_PAUSE_AFTER_TOUCH_MS
                        return true
                    }
                }
            }
            Mode.SNAKE -> {
                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        Tactile.tick(this)
                        if (snakeGameOver) {
                            resetSnake()
                            invalidate()
                            return true
                        }
                        snakeDownX = event.x
                        snakeDownY = event.y
                        snakeSwiped = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (snakeGameOver) return true
                        val dx = event.x - snakeDownX
                        val dy = event.y - snakeDownY
                        val swipeThresh = touchSlop * 0.75f
                        if (abs(dx) >= swipeThresh || abs(dy) >= swipeThresh) {
                            snakeSwiped = true
                            if (abs(dx) > abs(dy)) {
                                steerSnake(if (dx > 0f) 1 else -1, 0)
                            } else {
                                steerSnake(0, if (dy > 0f) 1 else -1)
                            }
                            snakeDownX = event.x
                            snakeDownY = event.y
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!snakeSwiped && !snakeGameOver) {
                            steerSnakeQuadrant(event.x, event.y, w, h)
                        }
                        snakeSwiped = false
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        snakeSwiped = false
                        return true
                    }
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
        if (geomW != width || geomH != height) {
            geomW = width
            geomH = height
            for (c in 0 until COLS) cellCx[c] = (c + 0.5f) * cellW
            for (r in 0 until ROWS) cellCy[r] = (r + 0.5f) * cellH
        }
        val baseR = min(cellW, cellH) * 0.24f
        val dimR = baseR * 0.72f
        val lifeAliveR = baseR * 1.45f
        val foodR = baseR * 1.5f
        val headR = baseR * 1.55f
        val bodyR = baseR * 1.3f

        if (mode == Mode.SNAKE) {
            java.util.Arrays.fill(snakeDrawFlags, 0.toByte())
            val fIdx = snakeFood.second * COLS + snakeFood.first
            if (fIdx in 0 until snakeDrawFlags.size) snakeDrawFlags[fIdx] = 4.toByte()
            val bodyCount = snakeBody.size
            for ((idx, seg) in snakeBody.withIndex()) {
                val ci = seg.second * COLS + seg.first
                if (ci in 0 until snakeDrawFlags.size) {
                    val bit = if (idx == bodyCount - 1) 2 else 1
                    snakeDrawFlags[ci] = (snakeDrawFlags[ci].toInt() or bit).toByte()
                }
            }
        }

        // Base off-dot lattice across all modes
        for (r in 0 until ROWS) {
            val cy = cellCy[r]
            val rowOff = r * COLS
            for (c in 0 until COLS) {
                val cx = cellCx[c]
                when (mode) {
                    Mode.GRAVITY -> canvas.drawCircle(cx, cy, dimR, offPaint)
                    Mode.LIFE -> {
                        val alive = lifeGrid[rowOff + c]
                        val isSeedAccent = alive && ((c + r) % 7 == 0)
                        val p = if (isSeedAccent) accentPaint else if (alive) onPaint else offPaint
                        canvas.drawCircle(cx, cy, if (alive) lifeAliveR else dimR, p)
                    }
                    Mode.SNAKE -> {
                        val flags = snakeDrawFlags[rowOff + c].toInt()
                        val isFood = (flags and 4) != 0
                        val isHead = (flags and 2) != 0
                        val isBody = !isHead && (flags and 1) != 0
                        val p = when {
                            snakeGameOver && isHead -> accentPaint
                            isFood -> accentPaint
                            snakeGameOver && isBody -> dimPaint
                            isHead || isBody -> onPaint
                            else -> offPaint
                        }
                        val rad = when {
                            isFood -> foodR
                            isHead -> headR
                            isBody -> bodyR
                            else -> dimR
                        }
                        canvas.drawCircle(cx, cy, rad, p)
                    }
                }
            }
        }

        if (mode == Mode.GRAVITY) {
            val beadR = min(cellW, cellH) * 0.42f
            val accentBeadR = beadR * 1.12f
            for (i in 0 until BEAD_COUNT) {
                val px = beadX[i] * cellW
                val py = beadY[i] * cellH
                val paint = if (i < 3) accentPaint else onPaint
                canvas.drawCircle(px, py, if (i < 3) accentBeadR else beadR, paint)
            }
        }
    }
}
