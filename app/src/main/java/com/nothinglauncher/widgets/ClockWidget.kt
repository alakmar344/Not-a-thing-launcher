package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.nothinglauncher.DotMatrixClockView
import androidx.core.view.isVisible
import com.nothinglauncher.Intents
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.SecondsTickerView
import java.util.Calendar
import java.util.Locale

/** Large dot-matrix clock with date and a seconds ticker. Tap opens alarms. */
class ClockWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_clock
    override val descriptionRes = R.string.element_clock_sub
    override val spanX = 4
    override val spanY = 2
    override val minSpanX = 3
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_clock, host, false)
        val accent = ctx.getColor(R.color.nothing_red)
        val clock = root.findViewById<DotMatrixClockView>(R.id.clock)
        val date = root.findViewById<TextView>(R.id.clockDate)
        val meridiem = root.findViewById<TextView>(R.id.clockMeridiem)
        root.findViewById<View>(R.id.liveDot).background.mutate().setTint(accent)
        clock.accentColor = accent
        root.findViewById<SecondsTickerView>(R.id.secondsTicker).apply {
            accentColor = accent
            // Settings › Seconds ticker; re-checked whenever the tile is shown again.
            val prefs = NothingPrefs.get(ctx)
            isVisible = prefs.showSeconds
            // Follow Settings › Seconds ticker live (home stays attached in the background).
            // The listener is held by this closure while attached (prefs keep weak references).
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                isVisible = prefs.showSeconds
            }
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    isVisible = prefs.showSeconds
                    prefs.addListener(listener)
                }
                override fun onViewDetachedFromWindow(v: View) { prefs.removeListener(listener) }
            })
        }
        fun bindDate(cal: Calendar) {
            date.text = DateFormat.format(
                DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), cal
            )
            meridiem.text = if (DateFormat.is24HourFormat(ctx)) {
                ctx.getString(R.string.format_24h)
            } else {
                DateFormat.format("a", cal).toString().uppercase(Locale.getDefault())
            }
        }
        clock.onMinuteChanged = ::bindDate
        bindDate(Calendar.getInstance())
        clock.tick()
        root.setOnClickListener { Intents.openAlarms(it.context, it) }
        return root
    }
}
