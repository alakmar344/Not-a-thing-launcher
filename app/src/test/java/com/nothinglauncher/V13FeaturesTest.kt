package com.nothinglauncher

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.nothinglauncher.icons.NothingIconLogic
import com.nothinglauncher.search.CommandEngine
import com.nothinglauncher.settings.NothingSettingsActivity
import com.nothinglauncher.widgets.BatteryCalendarWidget
import com.nothinglauncher.widgets.ClockWidget
import com.nothinglauncher.widgets.CompassStepWidget
import com.nothinglauncher.widgets.DeskBriefingWidget
import com.nothinglauncher.widgets.EssentialNoteWidget
import com.nothinglauncher.widgets.FocusTimerWidget
import com.nothinglauncher.widgets.HabitMatrixWidget
import com.nothinglauncher.widgets.MediaWidget
import com.nothinglauncher.widgets.NothingWidget
import com.nothinglauncher.widgets.QuickTogglesWidget
import com.nothinglauncher.widgets.SmartFolderWidget
import com.nothinglauncher.widgets.VitalsWidget
import com.nothinglauncher.widgets.WeatherWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V13FeaturesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val p = NothingPrefs.get(context)
        p.focusMode = false
        p.accentPalette = AccentTheme.Palette.RED
        p.hiddenPackages = emptySet()
        p.customAppLabels = emptyMap()
        FocusTimerRepository.reset(context)
    }

    @Test
    fun commandEngineMathAndConversionsWorkOffline() {
        val calc = CommandEngine.evaluate(context, "(12 + 8) * 3").first { it.badge == "CALC" }
        assertEquals("60", calc.title)

        val pct = CommandEngine.evaluate(context, "15% of 240").first { it.badge == "CALC" }
        assertEquals("36", pct.title)

        val temp = CommandEngine.evaluate(context, "25 c to f").first { it.badge == "UNIT" }
        assertTrue(temp.title.startsWith("77"))

        val dist = CommandEngine.evaluate(context, "10 km to mi").first { it.badge == "UNIT" }
        assertTrue(dist.title.startsWith("6.21"))

        val tz = CommandEngine.evaluate(context, "time tokyo").first { it.badge == "TIME" }
        assertTrue(tz.subtitle.contains("Tokyo", ignoreCase = true))

        // Division by zero does not crash
        val divZero = CommandEngine.evaluate(context, "10 / 0").none { it.badge == "CALC" }
        assertTrue(divZero)
    }

    @Test
    fun commandEngineAcronymAndFuzzyScoring() {
        assertTrue(CommandEngine.scoreApp("yt", "YouTube") >= 70)
        assertTrue(CommandEngine.scoreApp("ps", "Play Store") >= 70)
        assertTrue(CommandEngine.scoreApp("spotfy", "Spotify") > 0)
        assertEquals(0, CommandEngine.scoreApp("", "Settings"))
        assertEquals(0, CommandEngine.scoreApp("zzzzzz", "Camera"))
    }

    @Test
    fun wordClockFormatsEditorialHours() {
        fun cal(h: Int, m: Int) = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
        }
        assertEquals("TWELVE" to "O CLOCK", WordClockFormatter.format(cal(0, 0)))
        assertEquals("QUARTER PAST" to "TWO", WordClockFormatter.format(cal(14, 15)))
        assertEquals("HALF PAST" to "TWO", WordClockFormatter.format(cal(14, 30)))
        assertEquals("QUARTER TO" to "THREE", WordClockFormatter.format(cal(14, 45)))
        assertEquals("TWELVE" to "O CLOCK", WordClockFormatter.format(cal(23, 59)))
    }

    @Test
    fun mediaArtHalftoneQuantizesBayerLevels() {
        assertNull(MediaArtHalftone.quantize(null))

        val brightRed = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(240, 20, 20))
        }
        val redMatrix = MediaArtHalftone.quantize(brightRed)!!
        assertEquals(100, redMatrix.size)
        assertTrue(redMatrix.any { it.toInt() == 2 })

        val dark = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val darkMatrix = MediaArtHalftone.quantize(dark)!!
        assertTrue(darkMatrix.all { it.toInt() == 0 })
    }

    @Test
    fun focusTimerAndStopwatchTrackDeterministicTimestamps() {
        val p = NothingPrefs.get(context)
        p.timerStopwatchMode = false
        p.timerDurationMs = 10 * 60_000L
        FocusTimerRepository.reset(context)

        val t0 = 1_000_000L
        assertEquals("10:00", FocusTimerRepository.snapshot(context, t0).display)

        FocusTimerRepository.toggleStartPause(context, t0)
        val runningSnap = FocusTimerRepository.snapshot(context, t0 + 90_000L)
        assertTrue(runningSnap.running)
        assertEquals("08:30", runningSnap.display)

        FocusTimerRepository.toggleMode(context)
        FocusTimerRepository.toggleStartPause(context, t0)
        val swSnap = FocusTimerRepository.snapshot(context, t0 + 65_000L)
        assertTrue(swSnap.isStopwatch)
        assertEquals("01:05", swSnap.display)
    }

    @Test
    fun accentThemeAndFocusModeColorResolution() {
        val p = NothingPrefs.get(context)
        p.accentPalette = AccentTheme.Palette.PHOSPHOR
        p.focusMode = false
        assertEquals(AccentTheme.Palette.PHOSPHOR.colorHex, AccentTheme.accentColor(context))

        p.focusMode = true
        assertFalse(AccentTheme.accentColor(context) == AccentTheme.Palette.PHOSPHOR.colorHex)
    }

    @Test
    fun prefsJsonExportImportAndPresetsRoundTrip() {
        val p = NothingPrefs.get(context)
        SetupBackupManager.applyPreset(context, SetupBackupManager.Preset.COMMAND)
        p.worldClockZoneId = "Asia/Tokyo"
        p.habitTitle = "DEEP WORK"
        p.habitDates = setOf("2026-11-25")
        p.hiddenPackages = setOf("com.example.secret")
        p.customAppLabels = mapOf("com.example/.Main" to "VaultApp")

        val json = p.exportToJson()
        SetupBackupManager.applyPreset(context, SetupBackupManager.Preset.MONOLITH)
        assertEquals(AccentTheme.Palette.MONO, p.accentPalette)

        assertTrue(p.importFromJson(json))
        assertEquals(AccentTheme.Palette.PHOSPHOR, p.accentPalette)
        assertEquals("Asia/Tokyo", p.worldClockZoneId)
        assertEquals("DEEP WORK", p.habitTitle)
        assertTrue(p.habitDates.contains("2026-11-25"))
        assertTrue(p.hiddenPackages.contains("com.example.secret"))
        assertEquals("VaultApp", p.customAppLabels["com.example/.Main"])
    }

    @Test
    fun iconFactoryRendersDotGlyphStyleAndClearCache() {
        val src = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.WHITE, Color.LTGRAY)
        )
        val rendered = IconFactory.render(
            context.resources,
            src,
            NothingPrefs.IconStyle.DOTS,
            96,
            cacheKey = "test_dots"
        )
        assertEquals(96, rendered.intrinsicWidth)
        assertEquals(96, rendered.intrinsicHeight)
        IconFactory.clearCache()
    }

    @Test
    fun allTwelveCustomWidgetsPreservedAndCreateViews() {
        val arr = context.resources.getStringArray(R.array.custom_widget_providers).toList()
        assertEquals(ClockWidget::class.java.name, arr[0])
        assertEquals(BatteryCalendarWidget::class.java.name, arr[1])
        assertEquals(WeatherWidget::class.java.name, arr[2])
        assertEquals(MediaWidget::class.java.name, arr[3])
        assertEquals(12, arr.size)

        val widgets = listOf(
            ClockWidget(context),
            BatteryCalendarWidget(context),
            WeatherWidget(context),
            MediaWidget(context),
            QuickTogglesWidget(context),
            FocusTimerWidget(context),
            EssentialNoteWidget(context),
            HabitMatrixWidget(context),
            VitalsWidget(context),
            CompassStepWidget(context),
            SmartFolderWidget(context),
            DeskBriefingWidget(context)
        )
        val host = AppWidgetHostView(context)
        widgets.forEach { widget ->
            val v: View = widget.createView(host)
            assertNotNull(v)
        }
    }

    @Test
    fun settingsAndEntryActivitiesInflateAndVitalsRead() {
        val vitals = HardwareControlRepository.readVitals(context)
        assertTrue(vitals.totalStorageGb > 0f || vitals.freeStorageGb >= 0f)

        val settings = Robolectric.buildActivity(NothingSettingsActivity::class.java).setup().get()
        assertNotNull(settings.findViewById(R.id.settingsList))

        val entry = Robolectric.buildActivity(LauncherEntryActivity::class.java).setup().get()
        assertNotNull(entry.findViewById(R.id.entryHero))
    }
}
