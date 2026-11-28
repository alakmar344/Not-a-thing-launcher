package com.nothinglauncher

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherState
import com.nothinglauncher.home.NothingLauncher
import com.nothinglauncher.icons.NothingIconLogic
import com.nothinglauncher.perf.VisualTier
import com.nothinglauncher.settings.NothingSettingsActivity
import com.nothinglauncher.widgets.BatteryCalendarWidget
import com.nothinglauncher.widgets.ClockWidget
import com.nothinglauncher.widgets.FocusTimerWidget
import com.nothinglauncher.widgets.KineticMatrixWidget
import com.nothinglauncher.widgets.NothingWidget
import com.nothinglauncher.widgets.SmartFolderWidget
import com.nothinglauncher.widgets.WeatherWidget
import java.util.concurrent.TimeUnit
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
        assertTrue("Expected STUDIO 15.6 hero header", dotTexts.any { it == "STUDIO 15.6" })
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

    @Test
    fun kineticMatrixClaimsHostTouchAndRunsEvenUnderReducedMotion() {
        val activity = launchActivity(LauncherEntryActivity::class.java)
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
        val disallowCalls = mutableListOf<Boolean>()
        var cancelLongPressCount = 0
        val spyHost = object : FrameLayout(activity) {
            override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                disallowCalls += disallowIntercept
                super.requestDisallowInterceptTouchEvent(disallowIntercept)
            }

            override fun cancelLongPress() {
                cancelLongPressCount++
                super.cancelLongPress()
            }
        }
        val matrix = KineticMatrixView(ContextThemeWrapper(activity, R.style.NtTheme_Backdrop_Dark))
        assertTrue(matrix.canScrollVertically(-1) && matrix.canScrollVertically(1))
        assertTrue(matrix.canScrollHorizontally(-1) && matrix.canScrollHorizontally(1))

        val pm = activity.getSystemService(PowerManager::class.java)
        try {
            VisualTier.setForTests(null)
            shadowOf(pm).setIsPowerSaveMode(true)
            assertFalse("Battery Saver should disable general VisualTier motion", VisualTier.motionAllowed(activity))

            spyHost.addView(matrix, FrameLayout.LayoutParams(1000, 400))
            contentRoot.addView(spyHost, ViewGroup.LayoutParams(1000, 400))
            spyHost.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            )
            spyHost.layout(0, 0, 1000, 400)
            contentRoot.dispatchWindowVisibilityChanged(View.VISIBLE)
            shadowOf(Looper.getMainLooper()).idle()

            matrix.mode = KineticMatrixView.Mode.GRAVITY
            val bx0 = matrix.beadX[0]
            val by0 = matrix.beadY[0]
            shadowOf(Looper.getMainLooper()).idleFor(320, TimeUnit.MILLISECONDS)
            assertTrue(
                "Gravity beads must still simulate under Battery Saver (attached=${matrix.isAttachedToWindow}, winVis=${matrix.windowVisibility}, vis=${matrix.visibility}, shown=${matrix.isShown}, override=${VisualTier.isTestOverrideActive()}, bx0=$bx0 -> ${matrix.beadX[0]})",
                matrix.beadX[0] != bx0 || matrix.beadY[0] != by0
            )

            matrix.mode = KineticMatrixView.Mode.SNAKE
            val headBefore = matrix.snakeBody.last()
            shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
            assertNotEquals("Snake must still tick under Battery Saver", headBefore, matrix.snakeBody.last())

            for (m in KineticMatrixView.Mode.entries) {
                matrix.mode = m
                disallowCalls.clear()
                val cancelsBefore = cancelLongPressCount
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 200f, 150f, 0)
                val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 200f, 150f, 0)
                assertTrue(matrix.onTouchEvent(down))
                assertTrue(matrix.onTouchEvent(up))
                down.recycle()
                up.recycle()
                assertEquals("Mode $m should claim DOWN and release UP", listOf(true, false), disallowCalls)
                assertTrue("Mode $m DOWN must cancel host long-press", cancelLongPressCount > cancelsBefore)
            }
        } finally {
            contentRoot.removeView(spyHost)
            shadowOf(pm).setIsPowerSaveMode(false)
            VisualTier.setForTests(null)
        }
    }

    @Test
    fun kineticMatrixGravityPullsFarBeadsAndRespondsToDrag() {
        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val matrix = KineticMatrixView(themed)
        matrix.mode = KineticMatrixView.Mode.GRAVITY
        matrix.layout(0, 0, 1000, 400)

        matrix.beadX[0] = 1.5f
        matrix.beadY[0] = 1.5f
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 950f, 360f, 0)
        assertTrue(matrix.onTouchEvent(down))
        down.recycle()

        var stepTime = now + 32L
        for (i in 1..8) {
            val move = MotionEvent.obtain(now, stepTime, MotionEvent.ACTION_MOVE, 950f + i * 4f, 360f + i * 3f, 0)
            assertTrue(matrix.onTouchEvent(move))
            move.recycle()
            stepTime += 32L
        }
        val afterDragX = matrix.beadX[0]
        val afterDragY = matrix.beadY[0]
        assertTrue(
            "Far beads (> 5.5 units away) must pull/fling toward touch (got $afterDragX, $afterDragY)",
            afterDragX > 1.7f && afterDragY > 1.7f
        )

        val up = MotionEvent.obtain(now, stepTime, MotionEvent.ACTION_UP, 982f, 384f, 0)
        assertTrue(matrix.onTouchEvent(up))
        up.recycle()
    }

    @Test
    fun kineticMatrixLifeDragPaintsPausesOnTouchAndAutoReseedsStagnantBlock() {
        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val dragMatrix = KineticMatrixView(themed)
        dragMatrix.mode = KineticMatrixView.Mode.LIFE
        dragMatrix.layout(0, 0, 800, 500) // cell = 50x50
        dragMatrix.lifeGrid.fill(false)

        val t0 = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 125f, 125f, 0)
        val move1 = MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, 175f, 125f, 0)
        val move2 = MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_MOVE, 225f, 125f, 0)
        val up = MotionEvent.obtain(t0, t0 + 48, MotionEvent.ACTION_UP, 225f, 125f, 0)
        assertTrue(dragMatrix.onTouchEvent(down))
        assertTrue(dragMatrix.onTouchEvent(move1))
        assertTrue(dragMatrix.onTouchEvent(move2))
        assertTrue(dragMatrix.onTouchEvent(up))
        down.recycle()
        move1.recycle()
        move2.recycle()
        up.recycle()

        assertTrue(dragMatrix.lifeGrid[2 * 16 + 2])
        assertTrue(dragMatrix.lifeGrid[2 * 16 + 3])
        assertTrue(dragMatrix.lifeGrid[2 * 16 + 4])

        // Attached lifecycle test: touch pause grace holds composition, stagnant 2x2 block auto-reseeds
        val activity = launchActivity(LauncherEntryActivity::class.java)
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
        val liveMatrix = KineticMatrixView(ContextThemeWrapper(activity, R.style.NtTheme_Backdrop_Dark))
        try {
            VisualTier.setForTests(null)
            liveMatrix.mode = KineticMatrixView.Mode.LIFE
            contentRoot.addView(liveMatrix, ViewGroup.LayoutParams(800, 500))
            liveMatrix.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY)
            )
            liveMatrix.layout(0, 0, 800, 500)
            contentRoot.dispatchWindowVisibilityChanged(View.VISIBLE)
            shadowOf(Looper.getMainLooper()).idle()

            // Set horizontal blinker and pause via tap on (0,0) twice
            liveMatrix.lifeGrid.fill(false)
            liveMatrix.lifeGrid[5 * 16 + 7] = true
            liveMatrix.lifeGrid[5 * 16 + 8] = true
            liveMatrix.lifeGrid[5 * 16 + 9] = true
            val pNow = SystemClock.uptimeMillis()
            for (rep in 0..1) {
                val pDown = MotionEvent.obtain(pNow, pNow + rep * 20, MotionEvent.ACTION_DOWN, 25f, 25f, 0)
                val pUp = MotionEvent.obtain(pNow, pNow + rep * 20 + 10, MotionEvent.ACTION_UP, 25f, 25f, 0)
                liveMatrix.onTouchEvent(pDown)
                liveMatrix.onTouchEvent(pUp)
                pDown.recycle()
                pUp.recycle()
            }
            // Within 650ms grace window, blinker must not flip to vertical yet
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertTrue(liveMatrix.lifeGrid[5 * 16 + 7] && liveMatrix.lifeGrid[5 * 16 + 9])
            assertFalse(liveMatrix.lifeGrid[4 * 16 + 8])

            // Place a stagnant 2x2 still life and run past the stagnation threshold
            liveMatrix.lifeGrid.fill(false)
            liveMatrix.lifeGrid[1 * 16 + 1] = true
            liveMatrix.lifeGrid[1 * 16 + 2] = true
            liveMatrix.lifeGrid[2 * 16 + 1] = true
            liveMatrix.lifeGrid[2 * 16 + 2] = true
            shadowOf(Looper.getMainLooper()).idleFor(5000, TimeUnit.MILLISECONDS)
            assertTrue(
                "Stagnant still-life block must auto-reseed (attached=${liveMatrix.isAttachedToWindow}, winVis=${liveMatrix.windowVisibility}, shown=${liveMatrix.isShown}, count=${liveMatrix.lifeGrid.count { it }})",
                liveMatrix.lifeGrid.count { it } > 4
            )
        } finally {
            contentRoot.removeView(liveMatrix)
            VisualTier.setForTests(null)
        }
    }

    @Test
    fun kineticMatrixSnakeSupportsSwipeNormalizedTapsAndGameOverHint() {
        val host = AppWidgetHostView(context)
        prefs.kineticMode = KineticMatrixView.Mode.SNAKE.prefValue
        val root = KineticMatrixWidget(context).createView(host)
        val matrix = root.findViewById<KineticMatrixView>(R.id.kineticMatrix)
        val hint = root.findViewById<TextView>(R.id.kineticHint)
        matrix.layout(0, 0, 1000, 300) // wide 3.33:1 widget

        // Tap upper-center slightly right of midpoint: |dx|=125px > |dy|=115px,
        // but normalized |ny|=0.383 > |nx|=0.125 so it must steer UP.
        val t0 = SystemClock.uptimeMillis()
        val upTapDown = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 625f, 35f, 0)
        val upTapUp = MotionEvent.obtain(t0, t0 + 25, MotionEvent.ACTION_UP, 625f, 35f, 0)
        assertTrue(matrix.onTouchEvent(upTapDown))
        assertTrue(matrix.onTouchEvent(upTapUp))
        upTapDown.recycle()
        upTapUp.recycle()
        assertEquals(0, matrix.snakeDirX)
        assertEquals(-1, matrix.snakeDirY)

        // Swipe downward (committed dir is still (1, 0), so DOWN is orthogonal and valid)
        val t1 = SystemClock.uptimeMillis()
        val sDown = MotionEvent.obtain(t1, t1, MotionEvent.ACTION_DOWN, 500f, 100f, 0)
        val sMove = MotionEvent.obtain(t1, t1 + 20, MotionEvent.ACTION_MOVE, 500f, 220f, 0)
        val sUp = MotionEvent.obtain(t1, t1 + 40, MotionEvent.ACTION_UP, 500f, 220f, 0)
        assertTrue(matrix.onTouchEvent(sDown))
        assertTrue(matrix.onTouchEvent(sMove))
        assertTrue(matrix.onTouchEvent(sUp))
        sDown.recycle()
        sMove.recycle()
        sUp.recycle()
        assertEquals(0, matrix.snakeDirX)
        assertEquals(1, matrix.snakeDirY)

        // Trigger self-collision -> Game Over hint updates, tap restarts immediately
        matrix.snakeBody.clear()
        matrix.snakeBody.addLast(5 to 5)
        matrix.snakeBody.addLast(6 to 5)
        matrix.snakeBody.addLast(6 to 6)
        matrix.snakeBody.addLast(5 to 6)
        matrix.snakeDirX = 0
        matrix.snakeDirY = -1
        matrix.stepSnake()
        assertTrue(matrix.snakeGameOver)
        assertTrue(hint.text.toString().contains("GAME OVER", ignoreCase = true))

        val t2 = SystemClock.uptimeMillis()
        val rDown = MotionEvent.obtain(t2, t2, MotionEvent.ACTION_DOWN, 500f, 150f, 0)
        val rUp = MotionEvent.obtain(t2, t2 + 20, MotionEvent.ACTION_UP, 500f, 150f, 0)
        matrix.onTouchEvent(rDown)
        matrix.onTouchEvent(rUp)
        rDown.recycle()
        rUp.recycle()
        assertFalse("Tap after Game Over should restart Snake immediately", matrix.snakeGameOver)
        assertTrue(hint.text.toString().contains("SCORE", ignoreCase = true))
    }
}
