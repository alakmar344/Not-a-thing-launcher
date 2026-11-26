package com.nothinglauncher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.model.data.AppInfo
import com.nothinglauncher.drawer.AppCategorizer
import com.nothinglauncher.settings.NothingSettingsActivity
import com.nothinglauncher.widgets.HabitMatrixWidget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V14FeaturesTest {

    private lateinit var context: Context
    private lateinit var prefs: NothingPrefs
    private val activeControllers = mutableListOf<ActivityController<*>>()

    private fun <T : android.app.Activity> launchActivity(cls: Class<T>): T =
        Robolectric.buildActivity(cls).also { activeControllers += it }.setup().get()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = NothingPrefs.get(context)
        prefs.deskMode = 0
        prefs.habitDates = emptySet()
        prefs.drawerLayoutMode = NothingPrefs.DrawerLayoutMode.GRID
        prefs.focusMode = false
        prefs.accentPalette = AccentTheme.Palette.RED
    }

    @After
    fun tearDown() {
        activeControllers.asReversed().forEach { controller ->
            runCatching { controller.pause().stop().destroy() }
        }
        activeControllers.clear()
    }

    @Test
    fun deskHudSwitchesFourModesAndPersistsAcrossRebirth() {
        val activity = launchActivity(DeskHudActivity::class.java)

        val cockpitStage = activity.findViewById<View>(R.id.deskCockpitStage)
        val glyphStage = activity.findViewById<View>(R.id.deskGlyphStage)
        val weatherCard = activity.findViewById<View>(R.id.deskWeatherCard)
        val clockCard = activity.findViewById<View>(R.id.deskClockCard)
        val timerCard = activity.findViewById<View>(R.id.deskTimerCard)

        assertTrue(cockpitStage.isVisible)
        assertFalse(glyphStage.isVisible)
        assertEquals(0, prefs.deskMode)

        activity.findViewById<View>(R.id.deskModeClock).performClick()
        assertEquals(1, prefs.deskMode)
        assertTrue(cockpitStage.isVisible)
        assertTrue(clockCard.isVisible)
        assertFalse(weatherCard.isVisible)

        activity.findViewById<View>(R.id.deskModeFocus).performClick()
        assertEquals(2, prefs.deskMode)
        assertFalse(clockCard.isVisible)
        assertTrue(timerCard.isVisible)

        activity.findViewById<View>(R.id.deskModeGlyph).performClick()
        assertEquals(3, prefs.deskMode)
        assertFalse(cockpitStage.isVisible)
        assertTrue(glyphStage.isVisible)

        // Simulate process/activity rebirth by finishing first controller before relaunching
        activeControllers.removeLastOrNull()?.pause()?.stop()?.destroy()
        val relaunched = launchActivity(DeskHudActivity::class.java)
        assertFalse(relaunched.findViewById<View>(R.id.deskCockpitStage).isVisible)
        assertTrue(relaunched.findViewById<View>(R.id.deskGlyphStage).isVisible)

        relaunched.findViewById<View>(R.id.deskModeCockpit).performClick()
        assertEquals(0, prefs.deskMode)
        assertTrue(relaunched.findViewById<View>(R.id.deskCockpitStage).isVisible)
        assertFalse(relaunched.findViewById<View>(R.id.deskGlyphStage).isVisible)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w840dp-h640dp")
    fun deskHudTabletDualColumnHabitToggleAndKeyboardShortcuts() {
        val activity = launchActivity(DeskHudActivity::class.java)
        val leftCol = activity.findViewById<View>(R.id.deskLeftCol)
        val rightCol = activity.findViewById<View>(R.id.deskRightCol)
        assertNotNull(leftCol)
        assertNotNull(rightCol)
        assertTrue(leftCol.isVisible)
        assertTrue(rightCol.isVisible)

        val habitBtn = activity.findViewById<TextView>(R.id.deskHabitBtn)
        assertFalse(HabitMatrixWidget.isDoneToday(prefs))
        habitBtn.performClick()
        assertTrue(HabitMatrixWidget.isDoneToday(prefs))
        assertTrue(habitBtn.text.contains("1D"))
        habitBtn.performClick()
        assertFalse(HabitMatrixWidget.isDoneToday(prefs))

        assertTrue(activity.onKeyUp(KeyEvent.KEYCODE_4, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_4)))
        assertEquals(3, prefs.deskMode)
        assertTrue(activity.findViewById<View>(R.id.deskGlyphStage).isVisible)

        assertTrue(activity.onKeyUp(KeyEvent.KEYCODE_1, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1)))
        assertEquals(0, prefs.deskMode)
        assertTrue(activity.findViewById<View>(R.id.deskCockpitStage).isVisible)
    }

    @Test
    fun glyphMatrixCanvasRendersDotsAndRespondsToTouchRipple() {
        val view = GlyphMatrixCanvasView(context).apply {
            accentColor = 0xFFD71921.toInt()
            isAudioActive = true
            timerRunning = true
            timerProgress01 = 0.65f
            triggerRipple(0.4f, 0.6f)
        }
        val size = 480
        view.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, size, size)
        var circleDraws = 0
        val recordingCanvas = object : Canvas(Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)) {
            override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: android.graphics.Paint) {
                super.drawCircle(cx, cy, radius, paint)
                if (radius > 0f) circleDraws++
            }
        }
        view.draw(recordingCanvas)

        assertTrue("Expected GlyphMatrixCanvasView to draw LED dots", circleDraws > 50)
    }

    @Test
    fun weatherDotsViewRendersRainProbabilitiesAndWind() {
        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val weatherView = WeatherDotsView(themed)
        weatherView.bind(
            WeatherRepository.Weather(
                city = "London",
                temperature = 20,
                condition = "Showers",
                high = 22,
                low = 14,
                isDay = true,
                updatedAt = 0L,
                hourlyTemps = listOf(18, 19, 20, 21, 22, 20),
                hourlyRainProbs = listOf(10, 25, 60, 85, 40, 15),
                windKmh = 21
            )
        )
        assertEquals(6, weatherView.hourlyRainProbs.size)
        assertEquals(21, weatherView.windKmh)
        assertTrue(weatherView.highLow.contains("21KM/H"))

        weatherView.measure(
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY)
        )
        weatherView.layout(0, 0, 640, 280)
        var circleDraws = 0
        val recordingCanvas = object : Canvas(Bitmap.createBitmap(640, 280, Bitmap.Config.ARGB_8888)) {
            override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: android.graphics.Paint) {
                super.drawCircle(cx, cy, radius, paint)
                if (radius > 0f) circleDraws++
            }
        }
        weatherView.draw(recordingCanvas)
        assertTrue("Expected WeatherDotsView to draw temp + sparkline dots", circleDraws > 30)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w840dp-h640dp")
    fun essentialSpaceTabletLayoutContainsHabitAndMediaBentoCards() {
        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val root = LayoutInflater.from(themed).inflate(R.layout.essential_space_overlay, null)
        assertNotNull(root.findViewById<View>(R.id.spaceHabitCard))
        assertNotNull(root.findViewById<View>(R.id.spaceHabitGrid))
        assertNotNull(root.findViewById<View>(R.id.spaceHabitToggle))
        assertNotNull(root.findViewById<View>(R.id.spaceMediaCard))
        assertNotNull(root.findViewById<View>(R.id.spaceMediaBars))
        assertNotNull(root.findViewById<View>(R.id.spaceMediaPlayPause))
    }

    @Test
    fun nothingPrefsExportsAndImportsDeskModeAndWidgetSurfaces() {
        prefs.deskMode = 3
        prefs.setWidgetSurface("clock", AccentTheme.WidgetSurface.PAPER)
        prefs.setWidgetSurface("weather", AccentTheme.WidgetSurface.GLASS)
        val json = prefs.exportToJson()

        prefs.deskMode = 0
        prefs.setWidgetSurface("clock", AccentTheme.WidgetSurface.DEFAULT)
        prefs.setWidgetSurface("weather", AccentTheme.WidgetSurface.DEFAULT)

        prefs.importFromJson(json)
        assertEquals(3, prefs.deskMode)
        assertEquals(AccentTheme.WidgetSurface.PAPER, prefs.widgetSurface("clock"))
        assertEquals(AccentTheme.WidgetSurface.GLASS, prefs.widgetSurface("weather"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w840dp-h640dp")
    fun settingsTwoColumnShowsLiveStudioClockPreview() {
        val activity = launchActivity(NothingSettingsActivity::class.java)
        val rightCol = activity.findViewById<LinearLayout>(R.id.settingsListRight)
        assertTrue(rightCol.isVisible)

        fun <T : View> findFirst(root: View, cls: Class<T>): T? {
            if (cls.isInstance(root)) return cls.cast(root)
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    findFirst(root.getChildAt(i), cls)?.let { return it }
                }
            }
            return null
        }

        val liveClock = findFirst(rightCol, DotMatrixClockView::class.java)
        val liveTicker = findFirst(rightCol, SecondsTickerView::class.java)
        assertNotNull(liveClock)
        assertNotNull(liveTicker)
    }

    @Test
    fun appCategorizerBucketsPackagesAndDrawerHeaderCyclesThreeModes() {
        fun fakeApp(pkg: String) = AppInfo().apply {
            componentName = ComponentName(pkg, "$pkg.MainActivity")
            title = pkg
        }
        assertEquals(AppCategorizer.Bucket.MEDIA, AppCategorizer.bucketFor(context, fakeApp("com.spotify.music")))
        assertEquals(AppCategorizer.Bucket.WEB_NAV, AppCategorizer.bucketFor(context, fakeApp("com.android.chrome")))
        assertEquals(AppCategorizer.Bucket.COMMUNICATION, AppCategorizer.bucketFor(context, fakeApp("com.google.android.gm")))
        assertEquals(AppCategorizer.Bucket.PRODUCTIVITY, AppCategorizer.bucketFor(context, fakeApp("com.google.android.keep")))
        assertEquals(AppCategorizer.Bucket.UTILITIES, AppCategorizer.bucketFor(context, fakeApp("org.example.flashlight")))

        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val header = LayoutInflater.from(themed).inflate(R.layout.nt_drawer_header, null)
        val chip = header.findViewById<TextView>(R.id.drawerModeChip)
        assertEquals("GRID", chip.text.toString())

        chip.performClick()
        assertEquals(NothingPrefs.DrawerLayoutMode.LIST, prefs.drawerLayoutMode)
        assertEquals("LIST", chip.text.toString())

        chip.performClick()
        assertEquals(NothingPrefs.DrawerLayoutMode.CATEGORIES, prefs.drawerLayoutMode)
        assertEquals("CATS", chip.text.toString())

        chip.performClick()
        assertEquals(NothingPrefs.DrawerLayoutMode.GRID, prefs.drawerLayoutMode)
        assertEquals("GRID", chip.text.toString())
    }

    @Test
    fun fixedWidgetAndDrawerHeaderLayoutsMeasureWithoutClipping() {
        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val d = themed.resources.displayMetrics.density
        val wPx = (220 * d).toInt()
        val hPx = (176 * d).toInt()

        val clockRoot = LayoutInflater.from(themed).inflate(R.layout.widget_clock, null)
        clockRoot.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY)
        )
        clockRoot.layout(0, 0, wPx, hPx)
        val clockDate = clockRoot.findViewById<TextView>(R.id.clockDate)
        assertTrue("clockDate must remain visible inside ClockWidget", clockDate.height > 0 && clockDate.bottom <= hPx)

        val calRoot = LayoutInflater.from(themed).inflate(R.layout.widget_calendar, null)
        calRoot.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY)
        )
        calRoot.layout(0, 0, wPx, hPx)
        assertEquals("widget_calendar must match parent width cleanly", wPx, calRoot.measuredWidth)

        val batRoot = LayoutInflater.from(themed).inflate(R.layout.widget_battery, null)
        batRoot.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY)
        )
        batRoot.layout(0, 0, wPx, hPx)
        assertEquals("widget_battery must match parent width cleanly", wPx, batRoot.measuredWidth)

        val headerW = (360 * d).toInt()
        val header = LayoutInflater.from(themed).inflate(R.layout.nt_drawer_header, null)
        header.measure(
            View.MeasureSpec.makeMeasureSpec(headerW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        header.layout(0, 0, header.measuredWidth, header.measuredHeight)
        val settingsBtn = header.findViewById<View>(R.id.drawerSettings)
        assertTrue("drawerSettings button must fit inside 360dp header", settingsBtn.right <= headerW && settingsBtn.width > 0)
    }
}
