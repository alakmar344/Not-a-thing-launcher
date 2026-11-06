package com.nothinglauncher

import android.content.Context
import android.text.format.DateFormat
import android.util.AttributeSet
import java.util.Calendar

/**
 * Live dot-matrix clock. Respects the system 12/24-hour setting and blinks the colon
 * in the accent colour once per second. It only ticks while actually visible.
 */
class DotMatrixClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DotMatrixView(context, attrs, defStyleAttr) {

    var blinkColon: Boolean = true
        set(value) { field = value; tick() }

    /** Called whenever the displayed minute changes (and once on first tick). */
    var onMinuteChanged: ((Calendar) -> Unit)? = null

    /** Freezes the clock at a given time (previews / screenshot tests). */
    var previewTime: Pair<Int, Int>? = null
        set(value) { field = value; tick(); updateRunning() }

    private var lastMinuteStamp = -1L
    private var lastTextKey = -1
    private var lastColonIndex = -2
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (running) postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    init {
        tick()
    }

    fun tick() {
        val cal = Calendar.getInstance()
        val preview = previewTime
        if (preview != null) {
            cal.set(Calendar.HOUR_OF_DAY, preview.first)
            cal.set(Calendar.MINUTE, preview.second)
            cal.set(Calendar.SECOND, 0)
        }
        val is24 = is24Hour()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        // This runs every second; the digits only change once a minute (or on a 12/24h switch).
        val key = (hour * 60 + minute) * 2 + if (is24) 1 else 0
        if (key != lastTextKey) {
            lastTextKey = key
            val h = if (is24) hour else if (hour % 12 == 0) 12 else hour % 12
            val hh = if (is24 && h < 10) "0$h" else h.toString()
            text = if (minute < 10) "$hh:0$minute" else "$hh:$minute"
        }
        val colon = text.indexOf(':')
        if (colon != lastColonIndex) {
            lastColonIndex = colon
            accentIndices = setOf(colon)
        }
        accentVisible = !blinkColon || preview != null || cal.get(Calendar.SECOND) % 2 == 0

        val stamp = cal.timeInMillis / 60_000L
        if (stamp != lastMinuteStamp) {
            lastMinuteStamp = stamp
            onMinuteChanged?.invoke(cal)
        }
    }

    fun is24Hour(): Boolean = try {
        DateFormat.is24HourFormat(context)
    } catch (e: Exception) {
        true
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE && isShown && previewTime == null
        if (shouldRun == running) return
        running = shouldRun
        removeCallbacks(ticker)
        if (running) post(ticker)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        removeCallbacks(ticker)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateRunning()
    }
}
