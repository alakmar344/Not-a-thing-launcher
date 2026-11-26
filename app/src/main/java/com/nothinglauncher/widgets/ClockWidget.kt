package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.AnalogDotClockView
import com.nothinglauncher.DotMatrixClockView
import com.nothinglauncher.DotMatrixView
import com.nothinglauncher.Intents
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.SecondsTickerView
import com.nothinglauncher.Tactile
import com.nothinglauncher.WordClockFormatter
import com.nothinglauncher.perf.VisualTier
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Multi-face Nothing Clock element:
 * Tap mode pill (`24H`/`AM`/`ANALOG`/`WORD`/`WORLD`) to cycle faces; tap body to open Alarms.
 */
class ClockWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_clock
    override val descriptionRes = R.string.element_clock_sub
    override val previewRes = R.drawable.preview_widget_clock
    override val spanX = 4
    override val spanY = 2
    override val minSpanX = 3
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_clock, host, false)
        val prefs = NothingPrefs.get(ctx)
        val liveDot = root.findViewById<View>(R.id.liveDot)
        val clockLabel = root.findViewById<TextView>(R.id.clockLabel)
        val clock = root.findViewById<DotMatrixClockView>(R.id.clock)
        val analog = root.findViewById<AnalogDotClockView>(R.id.clockAnalog)
        val word = root.findViewById<DotMatrixView>(R.id.clockWord)
        val date = root.findViewById<TextView>(R.id.clockDate)
        val meridiem = root.findViewById<TextView>(R.id.clockMeridiem)
        val ticker = root.findViewById<SecondsTickerView>(R.id.secondsTicker)

        val fx = VisualTier.effects(ctx)
        clock.glow = clock.glow && fx.dotGlow
        clock.powerOnEnabled = fx.clockPowerOn

        fun refreshAccentAndFace() {
            val accent = AccentTheme.accentColor(ctx)
            AccentTheme.applyWidgetSurface(root, ctx, "clock", defaultLight = false)
            val sc = AccentTheme.surfaceColors(ctx, "clock", defaultLight = false)
            AccentTheme.tintDot(liveDot, ctx)
            clockLabel.setTextColor(sc.textSecondary)
            meridiem.setTextColor(sc.textPrimary)
            date.setTextColor(sc.textSecondary)
            clock.dotColor = sc.onDot
            clock.offColor = sc.offDot
            clock.accentColor = accent
            analog.accentColor = accent
            analog.applySurfaceColors(sc)
            word.dotColor = sc.onDot
            word.offColor = sc.offDot
            word.accentColor = accent
            ticker.accentColor = accent
            ticker.applySurfaceColors(sc)

            val mode = prefs.clockFaceMode
            clock.isVisible = mode == 0 || mode == 3
            analog.isVisible = mode == 1
            word.isVisible = mode == 2
            ticker.isVisible = (mode == 0) && prefs.showSeconds
        }

        fun bindDate(cal: Calendar) {
            refreshAccentAndFace()
            val mode = prefs.clockFaceMode
            when (mode) {
                0 -> {
                    clockLabel.setText(R.string.local_time)
                    date.text = DateFormat.format(
                        DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), cal
                    )
                    meridiem.text = if (DateFormat.is24HourFormat(ctx)) {
                        ctx.getString(R.string.format_24h)
                    } else {
                        DateFormat.format("a", cal).toString().uppercase(Locale.getDefault())
                    }
                }
                1 -> {
                    clockLabel.setText(R.string.clock_mode_analog)
                    meridiem.text = ctx.getString(R.string.clock_mode_analog_short)
                    date.text = DateFormat.format(
                        DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), cal
                    )
                }
                2 -> {
                    clockLabel.setText(R.string.clock_mode_word)
                    meridiem.text = ctx.getString(R.string.clock_mode_word_short)
                    val (l1, l2) = WordClockFormatter.format(cal)
                    word.text = "$l1\n$l2"
                    date.text = DateFormat.format(
                        DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), cal
                    )
                }
                else -> {
                    val tz = TimeZone.getTimeZone(prefs.worldClockZoneId)
                    val worldCal = Calendar.getInstance(tz)
                    val wh = worldCal.get(Calendar.HOUR_OF_DAY)
                    val wm = worldCal.get(Calendar.MINUTE)
                    val wStr = String.format(Locale.US, "%02d:%02d", wh, wm)
                    val cityTag = tz.id.substringAfterLast('/').replace('_', ' ').uppercase(Locale.US)
                    clockLabel.text = "$cityTag $wStr"
                    meridiem.text = ctx.getString(R.string.clock_mode_world_short)
                    date.text = DateFormat.format(
                        DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), cal
                    )
                }
            }
        }

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            bindDate(Calendar.getInstance())
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                bindDate(Calendar.getInstance())
                prefs.addListener(listener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                prefs.removeListener(listener)
            }
        })

        clock.onMinuteChanged = ::bindDate
        bindDate(Calendar.getInstance())
        clock.tick()

        meridiem.setOnClickListener {
            Tactile.tick(it)
            prefs.clockFaceMode = (prefs.clockFaceMode + 1) % 4
            bindDate(Calendar.getInstance())
        }
        root.setOnClickListener { Intents.openAlarms(it.context, it) }
        return root
    }
}
