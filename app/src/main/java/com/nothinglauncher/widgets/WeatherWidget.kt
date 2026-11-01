package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.WeatherDotsView
import com.nothinglauncher.WeatherRepository

/** Live weather (Open-Meteo, no key). Tap to refresh. */
class WeatherWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_weather
    override val descriptionRes = R.string.element_weather_sub
    override val spanX = 4
    override val spanY = 2
    override val minSpanX = 2
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val prefs = NothingPrefs.get(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_weather, host, false)
        val weatherView = root.findViewById<WeatherDotsView>(R.id.weatherView)
        weatherView.accentColor = ctx.getColor(R.color.nothing_red)
        val listener: (WeatherRepository.Weather?) -> Unit = { weather ->
            if (root.isAttachedToWindow) weatherView.bind(weather)
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                weatherView.bind(WeatherRepository.addListener(prefs, listener))
                WeatherRepository.refresh(ctx, prefs) { }
            }

            override fun onViewDetachedFromWindow(v: View) {
                WeatherRepository.removeListener(listener)
            }
        })
        weatherView.bind(WeatherRepository.cached(prefs))
        root.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            WeatherRepository.refresh(ctx, prefs, force = true) { }
        }
        return root
    }
}
