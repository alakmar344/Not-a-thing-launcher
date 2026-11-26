package com.nothinglauncher

import android.appwidget.AppWidgetHostView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.nothinglauncher.perf.VisualTier
import com.nothinglauncher.widgets.BatteryCalendarWidget
import com.nothinglauncher.widgets.ClockWidget
import com.nothinglauncher.widgets.HabitPegboardView
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Paparazzi visual snapshots for big-screen / tablet / foldable (`w600dp`+ / `PIXEL_C` landscape)
 * layouts: Dual-Column Bento Studio Home, Glyph Desk HUD Cockpit, and -1 Essential Space.
 */
class BigScreenScreensTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_C.copy(
            nightMode = NightMode.NIGHT,
            softButtons = false
        ),
        theme = "NtTheme.Backdrop.Dark",
        maxPercentDifference = 0.5
    )

    private val ctx get() = paparazzi.context
    private val density get() = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(ctx, id)

    @Before
    fun setUp() = VisualTier.setForTests(VisualTier.Tier.RICH)

    @After
    fun tearDown() = VisualTier.setForTests(null)

    private fun sampleWeather() = WeatherRepository.Weather(
        city = "LONDON",
        temperature = 21,
        condition = "Clear",
        high = 24,
        low = 14,
        isDay = true,
        updatedAt = 0L,
        hourlyTemps = listOf(18, 19, 20, 21, 23, 24, 23, 21, 19, 17, 16, 15),
        hourlyRainProbs = listOf(5, 10, 15, 45, 70, 80, 50, 25, 10, 5, 5, 5),
        windKmh = 16
    )

    @Test
    fun tabletBentoHome() {
        val stage = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = DotGridDrawable.forTier(ctx)
            setPadding(dp(28), dp(28), dp(28), dp(24))
        }

        val rowTop = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val clockHost = AppWidgetHostView(ctx).also { ClockWidget(ctx).onViewCreated(it) }
        clockHost.findViewById<DotMatrixClockView>(R.id.clock)?.previewTime = 10 to 9
        clockHost.findViewById<SecondsTickerView>(R.id.secondsTicker)?.previewSecond = 37
        rowTop.addView(clockHost, LinearLayout.LayoutParams(0, dp(220), 2f))
        rowTop.addView(View(ctx), LinearLayout.LayoutParams(dp(16), 1))

        val weatherView = LayoutInflater.from(ctx).inflate(R.layout.widget_weather, rowTop, false)
        weatherView.findViewById<WeatherDotsView>(R.id.weatherView).bind(sampleWeather())
        rowTop.addView(weatherView, LinearLayout.LayoutParams(0, dp(220), 1f))
        stage.addView(rowTop, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))

        val rowBottom = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val batCalHost = AppWidgetHostView(ctx).also { BatteryCalendarWidget(ctx).onViewCreated(it) }
        batCalHost.findViewById<BatteryDotsView>(R.id.battery).level = 84
        rowBottom.addView(batCalHost, LinearLayout.LayoutParams(0, dp(210), 2f))
        rowBottom.addView(View(ctx), LinearLayout.LayoutParams(dp(16), 1))

        val deskWidget = LayoutInflater.from(ctx).inflate(R.layout.widget_desk_briefing, rowBottom, false)
        deskWidget.findViewById<TextView>(R.id.deskWidgetHeadline).setText(R.string.greeting_morning)
        deskWidget.findViewById<TextView>(R.id.deskWidgetWeather).text = "21° LONDON"
        deskWidget.findViewById<TextView>(R.id.deskWidgetSub).text = "10:30 · Design Review • Alarm Thu 07:30"
        deskWidget.findViewById<TextView>(R.id.deskWidgetTimerBtn).text = "▶ 18:00"
        rowBottom.addView(deskWidget, LinearLayout.LayoutParams(0, dp(210), 1f))
        stage.addView(rowBottom, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        stage.addView(View(ctx), LinearLayout.LayoutParams(MATCH, 0, 1f))
        stage.addView(
            LayoutInflater.from(ctx).inflate(R.layout.search_container_hotseat, stage, false),
            LinearLayout.LayoutParams(dp(540), dp(54)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
        )
        paparazzi.snapshot(stage)
    }

    @Test
    fun tabletDeskHudCockpit() {
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_desk_hud, null)
        root.findViewById<View>(R.id.deskRoot).background = DotGridDrawable.forTier(ctx)
        root.findViewById<TextView>(R.id.deskDate).text = "Thursday, November 26"
        root.findViewById<TextView>(R.id.deskClockModePill).setText(R.string.format_24h)
        root.findViewById<DotMatrixClockView>(R.id.deskClock).previewTime = 10 to 9
        root.findViewById<SecondsTickerView>(R.id.deskSeconds).previewSecond = 37
        root.findViewById<TextView>(R.id.deskNextBanner).text = "11:00 · Architecture Sync"
        root.findViewById<BatteryDotsView>(R.id.deskBattery).level = 88
        root.findViewById<WeatherDotsView>(R.id.deskWeather).bind(sampleWeather())
        root.findViewById<DotRingGaugeView>(R.id.deskTimerRing).apply {
            progress01 = 0.72f
            highlightActive = true
            centerText = "18:00"
            subtitle = ctx.getString(R.string.timer_running)
        }
        root.findViewById<TextView>(R.id.deskTimerAction).text = "25M"
        root.findViewById<TextView>(R.id.deskMediaTitle).text = "Glyph Studio 14"
        root.findViewById<TextView>(R.id.deskMediaArtist).text = "NOTHING AUDIO"
        root.findViewById<TextView>(R.id.deskNotePreview).text =
            "Ship v14 Big-Screen Telemetry Studio · Check tablet Desk HUD + Smart Categories"
        paparazzi.snapshot(root)
    }

    @Test
    fun tabletEssentialSpace() {
        val root = LayoutInflater.from(ctx).inflate(R.layout.essential_space_overlay, null)
        root.background = DotGridDrawable.forTier(ctx)
        root.findViewById<TextView>(R.id.essentialGreeting).text = "THURSDAY, NOV 26"
        root.findViewById<TextView>(R.id.essentialBriefingHeadline).setText(R.string.greeting_morning)
        root.findViewById<TextView>(R.id.essentialWeatherPill).text = "21° LONDON"
        root.findViewById<TextView>(R.id.essentialBriefingSub).text = "11:00 · Architecture Sync • Alarm Thu 07:30"
        root.findViewById<TextView>(R.id.spaceNoteBody).text =
            "Big-screen Bento Essential Space with live Habit Pegboard & Now Playing deck."
        root.findViewById<TextView>(R.id.spaceVitalsText).text =
            "STORAGE 94.2 / 256 GB   •   RAM 6.8 / 12.0 GB   •   SCREEN 2H 18M"
        root.findViewById<TextView>(R.id.spaceHabitTitle).text = "DEEP WORK"
        root.findViewById<TextView>(R.id.spaceHabitStreak).text = "12D"
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dates = (0 until 12).map { offset ->
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -offset) }.let { fmt.format(it.time) }
        }.toSet()
        root.findViewById<HabitPegboardView>(R.id.spaceHabitGrid).completedDates = dates
        root.findViewById<TextView>(R.id.spaceMediaTitle)?.text = "Glyph Studio 14"
        root.findViewById<TextView>(R.id.spaceMediaArtist)?.text = "NOTHING AUDIO"
        paparazzi.snapshot(root)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
