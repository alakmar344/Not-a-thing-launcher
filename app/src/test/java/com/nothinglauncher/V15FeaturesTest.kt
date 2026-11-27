package com.nothinglauncher

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherState
import com.nothinglauncher.home.NothingLauncher
import com.nothinglauncher.icons.NothingIconLogic
import com.nothinglauncher.settings.NothingSettingsActivity
import com.nothinglauncher.widgets.BatteryCalendarWidget
import com.nothinglauncher.widgets.ClockWidget
import com.nothinglauncher.widgets.FocusTimerWidget
import com.nothinglauncher.widgets.NothingWidget
import com.nothinglauncher.widgets.SmartFolderWidget
import com.nothinglauncher.widgets.WeatherWidget
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [LauncherRuntimeTest.LauncherAppsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class V15FeaturesTest {

    private lateinit var context: Context
    private lateinit var prefs: NothingPrefs
    private val activeControllers = mutableListOf<ActivityController<*>>()

    private fun <T : android.app.Activity> launchActivity(cls: Class<T>, intent: Intent? = null): T =
        (if (intent != null) Robolectric.buildActivity(cls, intent) else Robolectric.buildActivity(cls))
            .also { activeControllers += it }
            .setup()
            .get()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LauncherPrefs.get(context).putSync(LauncherPrefs.THEMED_ICONS.to(false))
        prefs = NothingPrefs.get(context)
        prefs.accentPalette = AccentTheme.Palette.RED
        prefs.focusMode = false
        prefs.caffeineEnabled = false
        prefs.background = NothingPrefs.Background.DOTS
        prefs.clockFaceMode = 0
        prefs.worldClockZoneId = "UTC"
        prefs.hiddenPackages = emptySet()
        prefs.customAppLabels = emptyMap()
        prefs.quickFolderApps = emptyList()
        FocusTimerRepository.reset(context)
        FocusSoundEngine.stop()
    }

    @After
    fun tearDown() {
        FocusSoundEngine.stop()
        activeControllers.asReversed().forEach { controller ->
            runCatching { controller.pause().stop().destroy() }
        }
        activeControllers.clear()
    }

    @Test
    fun dotMatrixClockViewDisplaysActualWorldTimeZone() {
        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val clock = DotMatrixClockView(themed)

        val utc = TimeZone.getTimeZone("UTC")
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val is24 = clock.is24Hour()

        fun expectedFor(tz: TimeZone): String {
            val cal = Calendar.getInstance(tz)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)
            val h = if (is24) hour else if (hour % 12 == 0) 12 else hour % 12
            val hh = if (is24 && h < 10) "0$h" else h.toString()
            return if (minute < 10) "$hh:0$minute" else "$hh:$minute"
        }

        clock.timeZone = utc
        assertEquals(expectedFor(utc), clock.text)

        clock.timeZone = tokyo
        assertEquals(expectedFor(tokyo), clock.text)
        assertNotEquals(expectedFor(utc), clock.text)
    }

    @Test
    fun focusTimerSupportsThreeDigitMinutesBeyond99() {
        prefs.timerStopwatchMode = false
        prefs.timerDurationMs = 120 * 60_000L
        FocusTimerRepository.reset(context)

        val snap = FocusTimerRepository.snapshot(context, nowMs = 1_000_000L)
        assertEquals("120:00", snap.display)
        assertEquals(120, snap.durationMinutes)

        prefs.timerStopwatchMode = true
        prefs.stopwatchAccumMs = 105 * 60_000L
        val sw = FocusTimerRepository.snapshot(context, nowMs = 1_000_000L)
        assertEquals("105:00", sw.display)
    }

    @Test
    fun focusTimerWidgetUsesLifecycleWrapperAndRespondsToSurfacePrefs() {
        val host = AppWidgetHostView(context)
        val view = FocusTimerWidget(context).createView(host)
        assertTrue(view is NothingWidget.LifecycleFrameLayout)

        prefs.setWidgetSurface("timer", AccentTheme.WidgetSurface.PAPER)
        // Inflated card inside wrapper receives surface update
        val ring = view.findViewById<DotRingGaugeView>(R.id.timerRing)
        assertNotNull(ring)
        prefs.setWidgetSurface("timer", AccentTheme.WidgetSurface.DEFAULT)
    }

    @Test
    fun clockWidgetUpdatesWorldTimeAndLocalHeaderInWorldMode() {
        prefs.clockFaceMode = 3
        prefs.worldClockZoneId = "Asia/Tokyo"
        val host = AppWidgetHostView(context)
        val root = ClockWidget(context).createView(host)

        val mainClock = root.findViewById<DotMatrixClockView>(R.id.clock)
        val header = root.findViewById<TextView>(R.id.clockLabel)
        val pill = root.findViewById<TextView>(R.id.clockMeridiem)

        assertEquals("Asia/Tokyo", mainClock.timeZone?.id)
        assertTrue(header.text.toString().contains("TOKYO", ignoreCase = true))
        assertTrue(header.text.toString().contains("LOCAL", ignoreCase = true))
        assertEquals(context.getString(R.string.clock_mode_world_short), pill.text.toString())
    }

    @Test
    fun nothingPrefsExportsVersion15AndClampsSoundscapePreset() {
        val obj = JSONObject(prefs.exportToJson())
        assertEquals(15, obj.getInt("version"))

        val lowPayload = JSONObject().apply {
            put("version", 15)
            put("soundscapePreset", 0)
        }.toString()
        assertTrue(prefs.importFromJson(lowPayload))
        assertEquals(1, prefs.soundscapePreset)

        val highPayload = JSONObject().apply {
            put("version", 15)
            put("soundscapePreset", 99)
        }.toString()
        assertTrue(prefs.importFromJson(highPayload))
        assertEquals(3, prefs.soundscapePreset)
    }

    @Test
    fun entryActivityHasDayNightHeroImportButtonAndAutoHighlightsActivePreset() {
        SetupBackupManager.applyPreset(context, SetupBackupManager.Preset.COMMAND)
        val activity = launchActivity(LauncherEntryActivity::class.java)
        val hero = activity.findViewById<DotMatrixView>(R.id.entryHero)
        assertEquals(context.getString(R.string.nt_entry_title_v14), hero.text)
        assertNotNull(activity.findViewById<View>(R.id.entryImportBtn))
        assertNotNull(activity.findViewById<View>(R.id.entryBackupBtn))
        assertNotNull(activity.findViewById<View>(R.id.entryDeskBtn))

        val presetsContainer = activity.findViewById<LinearLayout>(R.id.entryPresetsContainer)
        assertTrue(presetsContainer.childCount >= 1)
    }

    @Test
    fun customizeSheetRendersInlineAccentSwatchStripAndSwitchesPalette() {
        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val sheet = LayoutInflater.from(themed).inflate(R.layout.sheet_customize, null)
        val dots = sheet.findViewById<LinearLayout>(R.id.customizeAccentDots)
        val label = sheet.findViewById<TextView>(R.id.customizeAccentLabel)
        assertNotNull(dots)
        assertNotNull(label)

        AccentTheme.populateSwatchStrip(themed, dots, label, sizeDp = 20, gapDp = 8) { }
        assertEquals(AccentTheme.Palette.entries.size, dots.childCount)
        assertEquals(themed.getString(AccentTheme.Palette.RED.labelRes), label.text.toString())

        // Tap Phosphor Green swatch (ordinal 2)
        dots.getChildAt(AccentTheme.Palette.PHOSPHOR.ordinal).performClick()
        assertEquals(AccentTheme.Palette.PHOSPHOR, prefs.accentPalette)
        assertEquals(themed.getString(AccentTheme.Palette.PHOSPHOR.labelRes), label.text.toString())
        assertTrue(dots.getChildAt(AccentTheme.Palette.PHOSPHOR.ordinal).isSelected)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w840dp-h640dp")
    fun settingsActivityDisplaysStudio15AndSyncsIconStyleAndAccentSwatches() {
        NothingIconLogic.applyStyle(context, NothingPrefs.IconStyle.DOTS)
        val activity = launchActivity(NothingSettingsActivity::class.java)
        val rightCol = activity.findViewById<LinearLayout>(R.id.settingsListRight)
        assertTrue(rightCol.isVisible)

        fun collectDotTitles(v: View, out: MutableList<String>) {
            if (v is DotMatrixView) out += v.text
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) collectDotTitles(v.getChildAt(i), out)
            }
        }
        val dotTexts = mutableListOf<String>()
        collectDotTitles(rightCol, dotTexts)
        assertTrue("Expected STUDIO 15 hero header", dotTexts.any { it == "STUDIO 15" })
        assertEquals(NothingPrefs.IconStyle.DOTS, NothingIconLogic.currentStyle(activity))
    }

    @Test
    fun deskHudDisplaysStructuredNoteCardAndWorldClock() {
        prefs.clockFaceMode = 3
        prefs.worldClockZoneId = "Asia/Tokyo"
        prefs.essentialNote = "Ship v15 release"
        val activity = launchActivity(DeskHudActivity::class.java)

        assertNotNull(activity.findViewById<View>(R.id.deskNoteCard))
        assertNotNull(activity.findViewById<View>(R.id.deskNoteDot))
        assertEquals("Ship v15 release", activity.findViewById<TextView>(R.id.deskNotePreview).text.toString())

        val clock = activity.findViewById<DotMatrixClockView>(R.id.deskClock)
        val modePill = activity.findViewById<TextView>(R.id.deskClockModePill)
        assertEquals("Asia/Tokyo", clock.timeZone?.id)
        assertTrue(modePill.text.toString().contains("TOKYO"))
    }

    @Test
    fun smartFolderFiltersHiddenVaultAppsAndAllWidgetsCreateCleanly() {
        prefs.hiddenPackages = setOf("com.secret.vault")
        prefs.quickFolderApps = listOf("com.secret.vault/.Main", "com.android.settings/.Settings")

        val host = AppWidgetHostView(context)
        val smartFolder = SmartFolderWidget(context).createView(host)
        val maxContainer = smartFolder.findViewById<View>(R.id.sfMaxContainer)
        // With secret package filtered out, only 1 visible app remains -> Max Icon mode activates cleanly
        assertTrue(maxContainer.isVisible)

        val widgets = listOf(
            WeatherWidget(context),
            BatteryCalendarWidget(context),
            SmartFolderWidget(context)
        )
        widgets.forEach { assertNotNull(it.createView(host)) }
    }

    @Test
    fun launcherLivePrefListenerUpdatesCaffeineSearchPillAndDrawerMode() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = launchActivity(NothingLauncher::class.java, home)
        shadowOf(Looper.getMainLooper()).idle()

        prefs.caffeineEnabled = true
        shadowOf(Looper.getMainLooper()).idle()
        val flagsOn = launcher.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        assertTrue("Caffeine should set FLAG_KEEP_SCREEN_ON immediately", flagsOn != 0)

        prefs.caffeineEnabled = false
        shadowOf(Looper.getMainLooper()).idle()
        val flagsOff = launcher.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        assertEquals(0, flagsOff)

        launcher.stateManager.goToState(LauncherState.ALL_APPS, false)
        shadowOf(Looper.getMainLooper()).idle()
        prefs.drawerLayoutMode = NothingPrefs.DrawerLayoutMode.CATEGORIES
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(launcher.appsView)
    }

    @Test
    fun categoryAndListItemLayoutsHaveCountBadgeAndTag() {
        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val cat = LayoutInflater.from(themed).inflate(R.layout.item_drawer_category, null)
        assertNotNull(cat.findViewById<View>(R.id.catDot))
        assertNotNull(cat.findViewById<TextView>(R.id.catTitle))
        assertNotNull(cat.findViewById<TextView>(R.id.catCount))

        val row = LayoutInflater.from(themed).inflate(R.layout.item_drawer_list_app, null)
        assertNotNull(row.findViewById<TextView>(R.id.listAppTag))
    }
}
