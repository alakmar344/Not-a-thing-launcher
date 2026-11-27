package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.DotRingGaugeView
import com.nothinglauncher.FocusTimerRepository
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Tactile

/**
 * Interactive 2x2 Dot-Ring Focus Timer (Pomodoro) & Stopwatch synced with Essential Space.
 * Tap center ring to Start/Pause; tap header to switch Timer/Stopwatch; tap bottom chip to cycle duration or reset.
 * Uses [NothingWidget.LifecycleFrameLayout] so the 500ms ticker stops completely when hidden or backgrounded.
 */
class FocusTimerWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_timer
    override val descriptionRes = R.string.element_timer_sub
    override val previewRes = R.drawable.preview_widget_timer
    override val spanX = 2
    override val spanY = 2
    override val minSpanX = 2
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val prefs = NothingPrefs.get(ctx)
        lateinit var wrapper: LifecycleFrameLayout
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_timer, host, false)
        val dot = root.findViewById<View>(R.id.timerDot)
        val modeLabel = root.findViewById<TextView>(R.id.timerModeLabel)
        val resetBtn = root.findViewById<TextView>(R.id.timerActionBtn)
        val ring = root.findViewById<DotRingGaugeView>(R.id.timerRing)
        val handler = Handler(Looper.getMainLooper())
        var runningLoop = false
        var visibleInWindow = false

        fun bind() {
            val state = FocusTimerRepository.snapshot(ctx)
            val accent = AccentTheme.accentColor(ctx)
            AccentTheme.applyWidgetSurface(root, ctx, "timer", defaultLight = false)
            val sc = AccentTheme.surfaceColors(ctx, "timer", defaultLight = false)
            AccentTheme.tintDot(dot, ctx)
            dot.alpha = if (state.running) 1f else 0.45f
            modeLabel.setTextColor(sc.textSecondary)
            resetBtn.setTextColor(sc.textPrimary)
            ring.accentColor = accent
            ring.applySurfaceColors(sc)
            ring.highlightActive = state.running
            ring.progress01 = state.progress01
            ring.centerText = state.display
            ring.subtitle = when {
                state.finished -> ctx.getString(R.string.timer_done)
                state.running -> ctx.getString(R.string.timer_running)
                else -> ctx.getString(R.string.timer_tap)
            }
            modeLabel.setText(if (state.isStopwatch) R.string.stopwatch_label else R.string.timer_label)
            resetBtn.text = if (state.running || state.isStopwatch) {
                ctx.getString(R.string.timer_reset)
            } else {
                "${state.durationMinutes}M"
            }
        }

        val ticker = object : Runnable {
            override fun run() {
                if (!runningLoop) return
                bind()
                val st = FocusTimerRepository.snapshot(ctx)
                if (visibleInWindow && st.running) {
                    handler.postDelayed(this, 500L)
                } else {
                    runningLoop = false
                }
            }
        }

        fun updateTicker(forceVisible: Boolean = visibleInWindow) {
            visibleInWindow = forceVisible
            val shouldTick = visibleInWindow &&
                wrapper.isAttachedToWindow &&
                wrapper.isShown &&
                FocusTimerRepository.snapshot(ctx).running
            if (shouldTick && !runningLoop) {
                runningLoop = true
                handler.post(ticker)
            } else if (!shouldTick && runningLoop) {
                runningLoop = false
                handler.removeCallbacks(ticker)
            }
        }

        val repoListener: () -> Unit = {
            if (wrapper.isAttachedToWindow) {
                bind()
                updateTicker()
            }
        }
        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (wrapper.isAttachedToWindow) bind()
        }

        wrapper = LifecycleFrameLayout(ctx) { visible ->
            updateTicker(visible)
            if (visible) bind()
        }.apply {
            addView(root)
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    FocusTimerRepository.addListener(repoListener)
                    prefs.addListener(prefListener)
                    bind()
                    updateTicker(windowVisibility == View.VISIBLE && isShown)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    updateTicker(false)
                    FocusTimerRepository.removeListener(repoListener)
                    prefs.removeListener(prefListener)
                }
            })
        }

        ring.setOnClickListener {
            Tactile.confirm(it)
            FocusTimerRepository.toggleStartPause(ctx)
            bind()
            updateTicker()
        }
        ring.setOnLongClickListener {
            Tactile.tick(it)
            FocusTimerRepository.toggleMode(ctx)
            bind()
            updateTicker()
            true
        }
        modeLabel.setOnClickListener {
            Tactile.tick(it)
            FocusTimerRepository.toggleMode(ctx)
            bind()
            updateTicker()
        }
        resetBtn.setOnClickListener {
            Tactile.tick(it)
            val st = FocusTimerRepository.snapshot(ctx)
            if (st.running || st.isStopwatch) FocusTimerRepository.reset(ctx)
            else FocusTimerRepository.cyclePresetOrAdd(ctx)
            bind()
            updateTicker()
        }
        bind()
        return wrapper
    }
}
