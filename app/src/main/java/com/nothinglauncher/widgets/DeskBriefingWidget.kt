package com.nothinglauncher.widgets

import android.app.AlarmManager
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.DeskHudActivity
import com.nothinglauncher.FocusTimerRepository
import com.nothinglauncher.HardwareControlRepository
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Tactile
import com.nothinglauncher.WeatherRepository
import java.util.Calendar
import java.util.Locale

/**
 * 12th dot-matrix home element: live Desk Briefing & 1-tap Glyph Desk HUD launcher.
 * Surfaces next calendar event, alarm, cached weather, and opens [DeskHudActivity].
 */
class DeskBriefingWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_desk
    override val descriptionRes = R.string.element_desk_sub
    override val previewRes = R.drawable.preview_widget_desk
    override val spanX = 4
    override val spanY = 2
    override val minSpanX = 2
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val prefs = NothingPrefs.get(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_desk_briefing, host, false)
        val dot = root.findViewById<View>(R.id.deskWidgetDot)
        val label = root.findViewById<TextView>(R.id.deskWidgetLabel)
        val weatherTv = root.findViewById<TextView>(R.id.deskWidgetWeather)
        val headline = root.findViewById<TextView>(R.id.deskWidgetHeadline)
        val sub = root.findViewById<TextView>(R.id.deskWidgetSub)
        val launchBtn = root.findViewById<TextView>(R.id.deskWidgetLaunch)
        val timerBtn = root.findViewById<TextView>(R.id.deskWidgetTimerBtn)

        fun bindTimerPill() {
            val st = FocusTimerRepository.snapshot(ctx)
            timerBtn.text = if (st.running) "▶ ${st.display}" else "${st.display}"
            timerBtn.setTextColor(
                if (st.running) AccentTheme.accentColor(ctx)
                else AccentTheme.surfaceColors(ctx, "desk", defaultLight = false).textSecondary
            )
        }

        fun bind() {
            AccentTheme.applyWidgetSurface(root, ctx, "desk", defaultLight = false)
            val sc = AccentTheme.surfaceColors(ctx, "desk", defaultLight = false)
            AccentTheme.tintDot(dot, ctx)
            label.setTextColor(sc.textSecondary)
            headline.setTextColor(sc.textPrimary)
            sub.setTextColor(sc.textSecondary)

            val cal = Calendar.getInstance()
            val h = cal.get(Calendar.HOUR_OF_DAY)
            headline.setText(
                when {
                    h < 12 -> R.string.greeting_morning
                    h < 17 -> R.string.greeting_afternoon
                    else -> R.string.greeting_evening
                }
            )
            val w = WeatherRepository.cached(prefs)
            weatherTv.text = if (w != null) "${w.temperature}\u00B0 ${w.city.uppercase()}" else ""
            weatherTv.setTextColor(sc.textPrimary)

            val alarm = (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.nextAlarmClock
            val alarmStr = alarm?.let {
                val p = if (DateFormat.is24HourFormat(ctx)) "EEE HH:mm" else "EEE h:mm a"
                "Alarm ${DateFormat.format(p, it.triggerTime)}"
            }
            val ev = HardwareControlRepository.queryNextCalendarEvent(ctx)
            val evStr = ev?.let { "${it.timeLabel} · ${it.title}" }
            sub.text = listOfNotNull(evStr, alarmStr).joinToString(" · ")
                .ifEmpty { ctx.getString(R.string.nt_desk_subtitle) }
            bindTimerPill()
        }

        val weatherListener: (WeatherRepository.Weather?) -> Unit = {
            if (root.isAttachedToWindow) bind()
        }
        val timerListener: () -> Unit = {
            if (root.isAttachedToWindow) bindTimerPill()
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                WeatherRepository.addListener(prefs, weatherListener)
                FocusTimerRepository.addListener(timerListener)
                bind()
            }

            override fun onViewDetachedFromWindow(v: View) {
                WeatherRepository.removeListener(weatherListener)
                FocusTimerRepository.removeListener(timerListener)
            }
        })

        val openDesk = View.OnClickListener {
            Tactile.confirm(it)
            DeskHudActivity.launch(ctx)
        }
        root.setOnClickListener(openDesk)
        launchBtn.setOnClickListener(openDesk)
        timerBtn.setOnClickListener {
            Tactile.tick(it)
            FocusTimerRepository.toggleStartPause(ctx)
            bindTimerPill()
        }
        bind()
        return root
    }
}
