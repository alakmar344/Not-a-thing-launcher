package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.WeatherDotsView
import com.nothinglauncher.WeatherRepository

/** Live weather + 12h dot-matrix sparkline (Open-Meteo, no key). Tap to refresh. */
class WeatherWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_weather
    override val descriptionRes = R.string.element_weather_sub
    override val previewRes = R.drawable.preview_widget_weather
    override val spanX = 4
    override val spanY = 2
    override val minSpanX = 2
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val prefs = NothingPrefs.get(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_weather, host, false)
        val weatherView = root.findViewById<WeatherDotsView>(R.id.weatherView)
        fun bindWeather(w: WeatherRepository.Weather?) {
            AccentTheme.applyWidgetSurface(root, ctx, "weather", defaultLight = false)
            weatherView.accentColor = AccentTheme.accentColor(ctx)
            weatherView.applySurfaceColors(AccentTheme.surfaceColors(ctx, "weather", defaultLight = false))
            weatherView.bind(w)
        }
        val listener: (WeatherRepository.Weather?) -> Unit = { weather ->
            if (root.isAttachedToWindow) bindWeather(weather)
        }
        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (root.isAttachedToWindow) bindWeather(WeatherRepository.cached(prefs))
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                prefs.addListener(prefListener)
                bindWeather(WeatherRepository.addListener(prefs, listener))
                WeatherRepository.refresh(ctx, prefs) { }
            }

            override fun onViewDetachedFromWindow(v: View) {
                prefs.removeListener(prefListener)
                WeatherRepository.removeListener(listener)
            }
        })
        bindWeather(WeatherRepository.cached(prefs))
        root.setOnClickListener {
            com.nothinglauncher.Tactile.tick(it)
            WeatherRepository.refresh(ctx, prefs, force = true) { }
        }
        return root
    }
}
