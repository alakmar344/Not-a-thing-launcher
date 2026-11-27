package com.nothinglauncher

import android.content.Context
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Shared state machine for the Focus Pomodoro Timer & Stopwatch across Essential Space and
 * [com.nothinglauncher.widgets.FocusTimerWidget]. Persists timestamps in [NothingPrefs] so
 * state survives activity recreation with zero background services or wakeups.
 */
object FocusTimerRepository {

    data class State(
        val isStopwatch: Boolean,
        val running: Boolean,
        val display: String,
        val progress01: Float,
        val finished: Boolean,
        val durationMinutes: Int
    )

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun notifyListeners() { listeners.forEach { it() } }

    fun snapshot(context: Context, nowMs: Long = System.currentTimeMillis()): State {
        val p = NothingPrefs.get(context)
        if (p.timerStopwatchMode) {
            val elapsed = if (p.timerRunning && p.stopwatchStartMs > 0L) {
                p.stopwatchAccumMs + (nowMs - p.stopwatchStartMs).coerceAtLeast(0L)
            } else {
                p.stopwatchAccumMs
            }
            val sec = (elapsed / 1000L).toInt()
            val mins = sec / 60
            val secs = sec % 60
            val text = String.format(Locale.US, "%02d:%02d", mins.coerceAtMost(999), secs)
            val progress = ((sec % 60) / 60f)
            return State(true, p.timerRunning, text, progress, false, 0)
        }

        val duration = p.timerDurationMs
        val rem = if (p.timerRunning) {
            (p.timerEndAtMs - nowMs).coerceAtLeast(0L)
        } else {
            p.timerRemainingMs.coerceIn(0L, duration)
        }
        if (p.timerRunning && rem == 0L) {
            p.timerRunning = false
            p.timerRemainingMs = 0L
        }
        val totalSec = ((rem + 999L) / 1000L).toInt()
        val mins = totalSec / 60
        val secs = totalSec % 60
        val text = String.format(Locale.US, "%02d:%02d", mins.coerceAtMost(999), secs)
        val progress = (rem.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        return State(false, p.timerRunning, text, progress, rem == 0L, (duration / 60_000L).toInt())
    }

    fun toggleStartPause(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val p = NothingPrefs.get(context)
        if (p.timerStopwatchMode) {
            if (p.timerRunning) {
                val add = if (p.stopwatchStartMs > 0L) (nowMs - p.stopwatchStartMs).coerceAtLeast(0L) else 0L
                p.stopwatchAccumMs += add
                p.stopwatchStartMs = 0L
                p.timerRunning = false
            } else {
                p.stopwatchStartMs = nowMs
                p.timerRunning = true
            }
        } else {
            if (p.timerRunning) {
                p.timerRemainingMs = (p.timerEndAtMs - nowMs).coerceAtLeast(0L)
                p.timerRunning = false
            } else {
                val base = if (p.timerRemainingMs <= 0L) p.timerDurationMs else p.timerRemainingMs
                p.timerRemainingMs = base
                p.timerEndAtMs = nowMs + base
                p.timerRunning = true
            }
        }
        notifyListeners()
    }

    fun reset(context: Context) {
        val p = NothingPrefs.get(context)
        p.timerRunning = false
        if (p.timerStopwatchMode) {
            p.stopwatchAccumMs = 0L
            p.stopwatchStartMs = 0L
        } else {
            p.timerRemainingMs = p.timerDurationMs
            p.timerEndAtMs = 0L
        }
        notifyListeners()
    }

    fun cyclePresetOrAdd(context: Context) {
        val p = NothingPrefs.get(context)
        if (p.timerStopwatchMode) {
            reset(context)
            return
        }
        val currentMin = (p.timerDurationMs / 60_000L).toInt()
        val nextMin = when {
            currentMin < 10 -> 10
            currentMin < 15 -> 15
            currentMin < 25 -> 25
            currentMin < 45 -> 45
            else -> 5
        }
        p.timerRunning = false
        p.timerDurationMs = nextMin * 60_000L
        p.timerRemainingMs = p.timerDurationMs
        p.timerEndAtMs = 0L
        notifyListeners()
    }

    fun toggleMode(context: Context) {
        val p = NothingPrefs.get(context)
        p.timerRunning = false
        p.timerStopwatchMode = !p.timerStopwatchMode
        if (!p.timerStopwatchMode && p.timerRemainingMs <= 0L) {
            p.timerRemainingMs = p.timerDurationMs
        }
        notifyListeners()
    }
}
