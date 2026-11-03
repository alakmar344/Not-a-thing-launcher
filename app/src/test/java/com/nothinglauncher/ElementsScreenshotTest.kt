package com.nothinglauncher

import android.appwidget.AppWidgetHostView
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.nothinglauncher.widgets.BatteryCalendarWidget
import com.nothinglauncher.widgets.ClockWidget
import com.nothinglauncher.widgets.MediaWidget
import com.nothinglauncher.widgets.NothingWidget
import com.nothinglauncher.widgets.WeatherWidget
import org.junit.Rule
import org.junit.Test

/**
 * Renders the Not a Thing layer (v10 dot-matrix elements) the way Launcher3
 * hosts it. CI publishes these images with every release (./gradlew recordPaparazziDebug).
 */
abstract class ElementsScreenshotTest(night: Boolean) {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT,
            softButtons = false
        ),
        theme = if (night) "AppTheme.Dark" else "AppTheme",
        maxPercentDifference = 0.5
    )

    private val backdrop = if (night) Color.BLACK else Color.parseColor("#8E9AA8")

    @Test
    fun elements() {
        val ctx = paparazzi.context
        val density = ctx.resources.displayMetrics.density
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backdrop)
            val pad = (16 * density).toInt()
            setPadding(pad, (24 * density).toInt(), pad, pad)
        }
        listOf<NothingWidget>(
            ClockWidget(ctx), BatteryCalendarWidget(ctx), WeatherWidget(ctx), MediaWidget(ctx)
        ).forEach { widget ->
            val host = AppWidgetHostView(ctx)
            widget.onViewCreated(host)
            column.addView(host, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * density).toInt()
            })
        }
        paparazzi.snapshot(column)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}

class ElementsLightTest : ElementsScreenshotTest(night = false)

class ElementsDarkTest : ElementsScreenshotTest(night = true)
