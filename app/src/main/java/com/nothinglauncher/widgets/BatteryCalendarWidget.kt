package com.nothinglauncher.widgets

import android.app.AlarmManager
import android.appwidget.AppWidgetHostView
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.nothinglauncher.BatteryDotsView
import com.nothinglauncher.DotMatrixView
import com.nothinglauncher.Intents
import com.nothinglauncher.R
import java.util.Calendar
import java.util.Locale

/** Battery dot gauge next to a calendar tile (day, weekday and next alarm). */
class BatteryCalendarWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_battery_cal
    override val descriptionRes = R.string.element_battery_cal_sub
    override val spanX = 4
    override val spanY = 2
    override val minSpanX = 4
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val inflater = LayoutInflater.from(ctx)
        val accent = ctx.getColor(R.color.nothing_red)
        val gap = ctx.resources.getDimensionPixelSize(R.dimen.nt_tile_gap)
        var onShown: () -> Unit = {}
        // Rebinds whenever home becomes visible again: DATE_CHANGED isn't sent when the clock
        // is set backwards and can arrive late from doze, which left yesterday's date showing.
        val row = object : LinearLayout(ctx) {
            override fun onWindowVisibilityChanged(visibility: Int) {
                super.onWindowVisibilityChanged(visibility)
                if (visibility == VISIBLE && isAttachedToWindow) onShown()
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }

        val battery = inflater.inflate(R.layout.widget_battery, row, false)
        battery.findViewById<BatteryDotsView>(R.id.battery).accentColor = accent
        battery.setOnClickListener { Intents.openBatterySettings(it.context, it) }

        val calendar = inflater.inflate(R.layout.widget_calendar, row, false)
        calendar.findViewById<View>(R.id.calDot).background.mutate().setTint(accent)
        calendar.setOnClickListener { Intents.openCalendar(it.context, it) }

        row.addView(battery, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            .apply { marginEnd = gap / 2 })
        row.addView(calendar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            .apply { marginStart = gap / 2 })

        fun bindCalendar() {
            val now = Calendar.getInstance()
            calendar.findViewById<TextView>(R.id.calMonth).text =
                DateFormat.format("MMM", now).toString().uppercase(Locale.getDefault())
            calendar.findViewById<DotMatrixView>(R.id.calDay).text =
                now.get(Calendar.DAY_OF_MONTH).toString()
            calendar.findViewById<TextView>(R.id.calWeekday).text = DateFormat.format("EEEE", now)
            val alarm = (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.nextAlarmClock
            calendar.findViewById<TextView>(R.id.calAlarm).apply {
                isVisible = alarm != null
                if (alarm != null) {
                    val pattern = if (DateFormat.is24HourFormat(ctx)) "EEE HH:mm" else "EEE h:mm a"
                    text = DateFormat.format(pattern, alarm.triggerTime)
                }
            }
        }

        onShown = ::bindCalendar
        // Day changes and alarm edits arrive as broadcasts; only listen while on screen.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = bindCalendar()
        }
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_DATE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
                }
                androidx.core.content.ContextCompat.registerReceiver(
                    ctx, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
                bindCalendar()
            }

            override fun onViewDetachedFromWindow(v: View) {
                try { ctx.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
            }
        })
        bindCalendar()
        return row
    }
}
