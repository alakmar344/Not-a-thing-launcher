package com.nothinglauncher

import android.app.Notification
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.graphics.Scrim
import com.android.launcher3.model.data.AppInfo
import com.nothinglauncher.drawer.AppCategorizer
import com.nothinglauncher.settings.NothingSettingsActivity
import com.nothinglauncher.widgets.HabitMatrixWidget
import com.nothinglauncher.widgets.KineticMatrixWidget
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

    @Test
    @Config(sdk = [30])
    fun api30Android11ResolvesScrimAndTipColorsWithoutAttributeCrash() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val colorIds = listOf(
            com.android.launcher3.R.color.wallpaper_popup_scrim,
            com.android.launcher3.R.color.work_turn_on_stroke,
            com.android.launcher3.R.color.arrow_tip_view_bg,
            com.android.launcher3.R.color.arrow_tip_view_content,
            com.android.launcher3.R.color.button_bg,
            com.android.launcher3.R.color.button_text
        )
        val tv = TypedValue()
        colorIds.forEach { resId ->
            ctx.resources.getValue(resId, tv, true)
            assertFalse(
                "Resource ${ctx.resources.getResourceEntryName(resId)} must not be unresolved attribute on API 30",
                tv.type == TypedValue.TYPE_ATTRIBUTE
            )
            // Exact Context.getColor() call path that crashed on Vivo Y15 (Android 11 / Issue #32)
            ctx.getColor(resId)
        }

        val themed = ContextThemeWrapper(ctx, com.android.launcher3.R.style.AppTheme)
        val scrim = Scrim(View(themed))
        assertNotNull(scrim)
    }

    @Test
    fun kineticMatrixModesStepPhysicsLifeAndSnakeAndWidgetCycles() {
        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val view = KineticMatrixView(themed)

        val beforeX = view.beadX.copyOf()
        repeat(6) { view.stepPhysics(gx = 6.5f, gy = 4.2f) }
        assertTrue(view.beadX.indices.any { kotlin.math.abs(view.beadX[it] - beforeX[it]) > 0.05f })
        assertTrue(view.beadX.all { it in 0f..KineticMatrixView.COLS.toFloat() })

        view.mode = KineticMatrixView.Mode.LIFE
        view.lifeGrid.fill(false)
        // Blinker oscillator at row 3, cols 3..5 -> vertical at col 4, rows 2..4 after 1 generation
        listOf(3 to 3, 4 to 3, 5 to 3).forEach { (c, r) ->
            view.lifeGrid[r * KineticMatrixView.COLS + c] = true
        }
        view.stepLife()
        assertTrue(view.lifeGrid[2 * KineticMatrixView.COLS + 4])
        assertTrue(view.lifeGrid[3 * KineticMatrixView.COLS + 4])
        assertTrue(view.lifeGrid[4 * KineticMatrixView.COLS + 4])
        assertFalse(view.lifeGrid[3 * KineticMatrixView.COLS + 3])

        view.mode = KineticMatrixView.Mode.SNAKE
        var lastScore = -1
        view.onScoreChanged = { lastScore = it }
        view.resetSnake()
        val head = view.snakeBody.last()
        view.snakeFood = ((head.first + 1) % KineticMatrixView.COLS) to head.second
        view.stepSnake()
        assertEquals(1, view.snakeScore)
        assertEquals(1, lastScore)

        val host = AppWidgetHostView(themed)
        val root = KineticMatrixWidget(themed).createView(host)
        val pill = root.findViewById<TextView>(R.id.kineticModePill)
        val hint = root.findViewById<TextView>(R.id.kineticHint)
        assertEquals(context.getString(R.string.nt_kinetic_gravity), pill.text.toString())
        assertEquals(0, prefs.kineticMode)

        pill.performClick()
        assertEquals(1, prefs.kineticMode)
        assertEquals(context.getString(R.string.nt_kinetic_life), pill.text.toString())

        pill.performClick()
        assertEquals(2, prefs.kineticMode)
        assertEquals(context.getString(R.string.nt_kinetic_snake), pill.text.toString())
        assertTrue(hint.text.toString().contains("SCORE"))

        pill.performClick()
        assertEquals(0, prefs.kineticMode)
    }

    @Test
    fun notificationDigestAggregatesPrioritiesAndSoundscapeControlsPresent() {
        val sample = listOf(
            NotificationDigestRepository.RawNotification(
                key = "k1", packageName = "com.shop.app", appLabel = "Shop",
                title = "Flash Sale", text = "50% off", category = Notification.CATEGORY_PROMO,
                postTime = 1000L, ongoing = false, isGroupSummary = false
            ),
            NotificationDigestRepository.RawNotification(
                key = "k2", packageName = "com.whatsapp", appLabel = "WhatsApp",
                title = "Alex", text = "Meet at 5?", category = Notification.CATEGORY_MESSAGE,
                postTime = 2000L, ongoing = false, isGroupSummary = false
            ),
            NotificationDigestRepository.RawNotification(
                key = "k3", packageName = "com.whatsapp", appLabel = "WhatsApp",
                title = "Alex", text = "On my way", category = Notification.CATEGORY_MESSAGE,
                postTime = 3000L, ongoing = false, isGroupSummary = false
            ),
            NotificationDigestRepository.RawNotification(
                key = "k4", packageName = "com.whatsapp", appLabel = "WhatsApp",
                title = "Summary", text = "2 messages", category = Notification.CATEGORY_MESSAGE,
                postTime = 3000L, ongoing = false, isGroupSummary = true
            ),
            NotificationDigestRepository.RawNotification(
                key = "k5", packageName = "com.music.player", appLabel = "Music",
                title = "Now Playing", text = "Track", category = Notification.CATEGORY_TRANSPORT,
                postTime = 4000L, ongoing = true, isGroupSummary = false
            )
        )
        val snap = NotificationDigestRepository.aggregateRaw(permissionGranted = true, raw = sample)
        assertTrue(snap.permissionGranted)
        assertEquals(3, snap.totalCount)
        assertEquals(2, snap.priorityCount)
        assertEquals(2, snap.entries.size)
        assertEquals("com.whatsapp", snap.entries[0].packageName)
        assertEquals(2, snap.entries[0].countInPkg)
        assertTrue(snap.entries[0].isPriority)
        assertFalse(snap.entries[1].isPriority)

        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val phoneSpace = LayoutInflater.from(themed).inflate(R.layout.essential_space_overlay, null)
        assertNotNull(phoneSpace.findViewById<View>(R.id.spaceSoundscapeBtn))
        assertNotNull(phoneSpace.findViewById<View>(R.id.spaceDigestCard))
        assertNotNull(phoneSpace.findViewById<View>(R.id.spaceDigestCount))
        assertNotNull(phoneSpace.findViewById<View>(R.id.spaceDigestContainer))
    }

    @Test
    fun focusSoundEngineSynthesizesSamplesCyclesProfilesAndPersistsPrefs() {
        FocusSoundEngine.stop()
        assertEquals(FocusSoundEngine.Profile.OFF, FocusSoundEngine.current)

        val state = FocusSoundEngine.SynthState()
        assertEquals(0f to 0f, FocusSoundEngine.synthesizeSample(FocusSoundEngine.Profile.OFF, state))
        listOf(
            FocusSoundEngine.Profile.BROWN,
            FocusSoundEngine.Profile.RAIN,
            FocusSoundEngine.Profile.GAMMA_40HZ
        ).forEach { profile ->
            var energy = 0f
            repeat(128) {
                val (l, r) = FocusSoundEngine.synthesizeSample(profile, state)
                assertTrue(l.isFinite() && r.isFinite())
                assertTrue(kotlin.math.abs(l) < 2f && kotlin.math.abs(r) < 2f)
                energy += kotlin.math.abs(l) + kotlin.math.abs(r)
            }
            assertTrue("Expected non-silent output for $profile", energy > 0.01f)
        }

        assertEquals(FocusSoundEngine.Profile.BROWN, FocusSoundEngine.cycle())
        assertEquals(FocusSoundEngine.Profile.RAIN, FocusSoundEngine.cycle())
        assertEquals(FocusSoundEngine.Profile.GAMMA_40HZ, FocusSoundEngine.cycle())
        assertEquals(FocusSoundEngine.Profile.OFF, FocusSoundEngine.cycle())
        FocusSoundEngine.stop()

        prefs.kineticMode = 2
        prefs.soundscapePreset = 3
        val json = prefs.exportToJson()
        prefs.kineticMode = 0
        prefs.soundscapePreset = 1
        assertTrue(prefs.importFromJson(json))
        assertEquals(2, prefs.kineticMode)
        assertEquals(3, prefs.soundscapePreset)
    }

    @Test
    fun dreamServiceRegisteredAndStandByLayoutMeasuresCleanly() {
        val pm = context.packageManager
        val cn = ComponentName(context, NothingDreamService::class.java)
        val info = pm.getServiceInfo(cn, PackageManager.GET_META_DATA)
        assertTrue("DreamService must be exported", info.exported)
        assertEquals("android.permission.BIND_DREAM_SERVICE", info.permission)
        assertNotNull(info.metaData)

        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val dream = LayoutInflater.from(themed).inflate(R.layout.dream_nothing_standby, null)
        val wPx = (400 * themed.resources.displayMetrics.density).toInt()
        val hPx = (820 * themed.resources.displayMetrics.density).toInt()
        dream.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY)
        )
        dream.layout(0, 0, wPx, hPx)
        val openDesk = dream.findViewById<View>(R.id.dreamOpenDeskBtn)
        val clock = dream.findViewById<View>(R.id.dreamClock)
        assertTrue(clock.width > 0 && clock.height > 0)
        assertTrue(openDesk.width > 0 && openDesk.right <= wPx && openDesk.bottom <= hPx)
    }
}
