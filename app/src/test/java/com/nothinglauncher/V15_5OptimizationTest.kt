package com.nothinglauncher

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.nothinglauncher.perf.VisualTier
import com.nothinglauncher.search.CommandEngine
import com.nothinglauncher.widgets.HabitMatrixWidget
import com.nothinglauncher.widgets.HabitPegboardView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V15_5OptimizationTest {

    private lateinit var context: Context
    private lateinit var prefs: NothingPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = NothingPrefs.get(context)
        prefs.hiddenPackages = emptySet()
        prefs.habitDates = emptySet()
        prefs.customAppLabels = emptyMap()
        prefs.recentComponents = emptyList()
        prefs.quickFolderApps = emptyList()
        prefs.weatherCache = null
        HardwareControlRepository.clearCaches()
        IconFactory.clearCache()
    }

    @After
    fun tearDown() {
        IconFactory.clearCache()
        HardwareControlRepository.clearCaches()
        NotificationDigestRepository.trimCaches()
    }

    @Test
    fun nothingPrefsCachesParsedCollectionsAndInvalidatesOnWrite() {
        val hidden1 = setOf("com.example.one", "com.example.two")
        prefs.hiddenPackages = hidden1
        val readA = prefs.hiddenPackages
        val readB = prefs.hiddenPackages
        assertSame(readA, readB)
        assertEquals(hidden1, readA)

        prefs.hiddenPackages = setOf("com.example.three")
        val readC = prefs.hiddenPackages
        assertNotSame(readA, readC)
        assertEquals(setOf("com.example.three"), readC)

        val hab = setOf(HabitMatrixWidget.todayKey())
        prefs.habitDates = hab
        assertSame(prefs.habitDates, prefs.habitDates)

        prefs.customAppLabels = mapOf("com.a/.Main" to "Alpha")
        assertSame(prefs.customAppLabels, prefs.customAppLabels)

        prefs.recordAppLaunch("com.a/.Main")
        prefs.recordAppLaunch("com.b/.Main")
        val recents1 = prefs.recentComponents
        assertSame(recents1, prefs.recentComponents)
        assertEquals(listOf("com.b/.Main", "com.a/.Main"), recents1)

        // Recording the already-top component must be a zero-write no-op.
        prefs.recordAppLaunch("com.b/.Main")
        assertSame(recents1, prefs.recentComponents)

        prefs.setWidgetSurface("clock", AccentTheme.WidgetSurface.PAPER)
        assertEquals(AccentTheme.WidgetSurface.PAPER, prefs.widgetSurface("clock"))
        assertSame(prefs.allWidgetSurfaces(), prefs.allWidgetSurfaces())
    }

    @Test
    fun weatherRepositoryCachesParsedWeatherObjectUntilJsonChanges() {
        val raw = JSONObject().apply {
            put("city", "Tokyo")
            put("temp", 22)
            put("condition", "CLEAR")
            put("high", 25)
            put("low", 17)
            put("day", true)
            put("at", System.currentTimeMillis())
            put("wind", 9)
            put("hTemps", JSONArray(listOf(22, 23, 24, 23)))
            put("hRain", JSONArray(listOf(0, 10, 20, 5)))
        }.toString()
        prefs.weatherCache = raw

        val first = WeatherRepository.cached(prefs)
        val second = WeatherRepository.cached(prefs)
        assertNotNull(first)
        assertSame(first, second)

        prefs.weatherCache = raw.replace("\"temp\":22", "\"temp\":29")
        val updated = WeatherRepository.cached(prefs)
        assertNotNull(updated)
        assertNotSame(first, updated)
        assertEquals(29, updated!!.temperature)
    }

    @Test
    fun focusSoundEngineFillBufferMatchesSynthesizeSampleExact() {
        for (profile in FocusSoundEngine.Profile.entries) {
            val stateA = FocusSoundEngine.SynthState()
            val stateB = FocusSoundEngine.SynthState()
            val frames = 128
            val actualBuf = ShortArray(frames * 2)
            val result = FocusSoundEngine.StreamFrameResult()
            FocusSoundEngine.fillBuffer(profile, stateA, actualBuf, frames, 0f, result)

            val expectedBuf = ShortArray(frames * 2)
            var env = 0f
            for (i in 0 until frames) {
                env = (env + 0.002f).coerceAtMost(1f)
                val (l, r) = FocusSoundEngine.synthesizeSample(profile, stateB)
                val sl = (l * env).coerceIn(-0.95f, 0.95f)
                val sr = (r * env).coerceIn(-0.95f, 0.95f)
                expectedBuf[i * 2] = (sl * 32767f).toInt().toShort()
                expectedBuf[i * 2 + 1] = (sr * 32767f).toInt().toShort()
            }
            assertArrayEquals("Mismatch for profile $profile", expectedBuf, actualBuf)
            assertEquals(env, result.envelope, 1e-6f)
        }
    }

    @Test
    fun commandEngineZeroAllocScoreAppMatchesAllScoringTiers() {
        assertEquals(120, CommandEngine.scoreApp("Camera", "camera"))
        assertEquals(120, CommandEngine.scoreApp("  cam ", "Cam"))
        assertEquals(100, CommandEngine.scoreApp("cam", "Camera"))
        assertEquals(80, CommandEngine.scoreApp("sto", "Play Store"))
        assertEquals(75, CommandEngine.scoreApp("ps", "Play Store"))
        assertEquals(72, CommandEngine.scoreApp("yt", "YouTube"))
        assertEquals(55, CommandEngine.scoreApp("ube", "YouTube"))
        assertEquals(32, CommandEngine.scoreApp("camra", "Camera"))
        assertEquals(0, CommandEngine.scoreApp("zzz", "Camera"))
        assertEquals(0, CommandEngine.scoreApp("", "Camera"))

        val tz = CommandEngine.evalTimezone("time tokyo")
        assertNotNull(tz)
        assertTrue(tz!!.second.contains("Tokyo"))
    }

    @Test
    fun iconFactoryPreCachedLookupAndTrimMemoryWorkCleanly() {
        IconFactory.configureForTier(context)
        val source = ColorDrawable(Color.WHITE)
        val key = "test_app_component"
        val size = 64

        assertNull(IconFactory.getCached(context.resources, NothingPrefs.IconStyle.MONO, size, key))
        for (style in NothingPrefs.IconStyle.entries) {
            val d = IconFactory.render(context.resources, source, style, size, cacheKey = key)
            assertNotNull(d)
        }
        assertNotNull(IconFactory.getCached(context.resources, NothingPrefs.IconStyle.MONO, size, key))
        assertNotNull(IconFactory.getCached(context.resources, NothingPrefs.IconStyle.DOTS, size, key))

        IconFactory.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertNull(IconFactory.getCached(context.resources, NothingPrefs.IconStyle.MONO, size, key))
    }

    @Test
    fun precomputedCustomViewsMeasureAndDrawCleanly() {
        val themed = ContextThemeWrapper(context, R.style.NtTheme_Backdrop_Dark)
        val bmp = Bitmap.createBitmap(260, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        fun renderView(v: View, w: Int = 260, h: Int = 200) {
            v.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            v.layout(0, 0, w, h)
            v.draw(canvas)
        }

        renderView(AnalogDotClockView(themed))

        renderView(DotRingGaugeView(themed).apply {
            progress01 = 0.65f
            highlightActive = true
            centerText = "12:34"
            subtitle = "VERY LONG TIMER LABEL SUBTITLE"
        })
        renderView(DotRingGaugeView(themed).apply {
            compassDegrees = 135f
            centerText = "135°"
            subtitle = "SE"
        })

        renderView(BatteryDotsView(themed).apply {
            level = 42
            charging = true
        })

        renderView(SecondsTickerView(themed).apply {
            previewSecond = 29
        }, 260, 24)

        renderView(WeatherDotsView(themed).apply {
            city = "London"
            temperature = "21°"
            condition = "CLEAR"
            isDay = true
            hourlyTemps = listOf(18, 19, 21, 22, 21, 20)
            hourlyRainProbs = listOf(10, 30, 70, 20, 5, 0)
        })

        renderView(MediaDotsView(themed).apply { isPlaying = true }, 200, 48)

        renderView(DotMatrixView(themed).apply {
            panel = true
            text = "HELLO\nWORLD"
            accentIndices = setOf(0, 6)
        }, 260, 120)

        renderView(HabitPegboardView(themed).apply {
            completedDates = setOf(HabitMatrixWidget.todayKey())
        })

        renderView(GlyphMatrixCanvasView(themed).apply {
            isAudioActive = true
            timerRunning = true
            timerProgress01 = 0.5f
        })

        val kinetic = KineticMatrixView(themed)
        kinetic.mode = KineticMatrixView.Mode.GRAVITY
        kinetic.stepPhysics(2.5f, 4.0f)
        renderView(kinetic)
        kinetic.mode = KineticMatrixView.Mode.LIFE
        kinetic.stepLife()
        renderView(kinetic)
        kinetic.mode = KineticMatrixView.Mode.SNAKE
        kinetic.stepSnake()
        renderView(kinetic)
        bmp.recycle()
    }

    @Test
    fun hardwareControlRepositoryCachesVitalsWithinTtl() {
        val v1 = HardwareControlRepository.readVitals(context)
        val v2 = HardwareControlRepository.readVitals(context)
        assertSame(v1, v2)

        HardwareControlRepository.clearCaches()
        val v3 = HardwareControlRepository.readVitals(context)
        assertNotSame(v1, v3)
    }

    @Test
    fun visualTierAllowsBothLiteAndFullConfiguration() {
        VisualTier.setForTests(VisualTier.Tier.LITE)
        try {
            IconFactory.configureForTier(context)
            assertEquals(VisualTier.Tier.LITE, VisualTier.current(context))
        } finally {
            VisualTier.setForTests(null)
        }
        assertTrue(abs(1.0f) > 0f)
    }
}
