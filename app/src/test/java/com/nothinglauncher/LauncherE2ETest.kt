package com.nothinglauncher

import android.appwidget.AppWidgetHostView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.nothinglauncher.widgets.BatteryCalendarWidget
import com.nothinglauncher.widgets.ClockWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end emulation test suite for the Not a Thing launcher components,
 * testing them under Google LayoutLib without requiring a virtual machine or hypervisor.
 */
class LauncherE2ETest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            nightMode = NightMode.NIGHT,
            softButtons = false
        ),
        theme = "NtTheme.Backdrop.Dark"
    )

    private val ctx get() = paparazzi.context
    private val density get() = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    @Test
    fun testDotFontGlyphsAndWidth() {
        val sampleDigits = "0123456789"
        for (ch in sampleDigits) {
            val glyph = DotFont.glyph(ch)
            assertNotNull("Glyph for $ch should not be null", glyph)
            assertEquals("Glyph for $ch should have 7 rows", DotFont.ROWS, glyph.size)
        }

        val sampleLetters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        for (ch in sampleLetters) {
            val glyph = DotFont.glyph(ch)
            assertNotNull("Glyph for $ch should not be null", glyph)
            assertEquals("Glyph for $ch should have 7 rows", DotFont.ROWS, glyph.size)
        }

        val symbols = listOf(':', '.', '-', '+', '/', '%', '°', '!', '?', '#', '&')
        for (ch in symbols) {
            val glyph = DotFont.glyph(ch)
            assertNotNull("Glyph for symbol $ch should exist", glyph)
        }

        val raster = DotFont.rasterizeCached("NOT A THING 11.0", spacing = 2)
        assertTrue("Raster columns should be greater than 0", raster.columns > 0)
        assertEquals(DotFont.ROWS, raster.lit.size)
    }

    @Test
    fun testIconFactoryMonoAndColorRendering() {
        IconFactory.clearCache()

        val fg = ColorDrawable(Color.WHITE)
        val bg = ColorDrawable(0xFFEA4335.toInt())
        val layers = IconFactory.Layers(bg, InsetDrawable(fg, 0.3f), null)

        val monoDrawable = IconFactory.renderLayers(ctx.resources, layers, NothingPrefs.IconStyle.MONO, dp(56))
        assertNotNull(monoDrawable)

        val colorDrawable = IconFactory.renderLayers(ctx.resources, layers, NothingPrefs.IconStyle.COLOR, dp(56))
        assertNotNull(colorDrawable)

        // Cache testing
        val cacheKey = "test_pkg_icon"
        val cached1 = IconFactory.render(ctx.resources, layers.foreground!!, NothingPrefs.IconStyle.MONO, dp(56), cacheKey)
        val cached2 = IconFactory.render(ctx.resources, layers.foreground!!, NothingPrefs.IconStyle.MONO, dp(56), cacheKey)
        assertNotNull(cached1)
        assertNotNull(cached2)

        IconFactory.clearCache()
    }

    @Test
    fun testNothingPrefsStateAndMutations() {
        val prefs = NothingPrefs.get(ctx)

        // Test background setting
        val originalBg = prefs.background
        prefs.background = NothingPrefs.Background.BLACK
        assertEquals(NothingPrefs.Background.BLACK, prefs.background)
        prefs.background = NothingPrefs.Background.DOTS
        assertEquals(NothingPrefs.Background.DOTS, prefs.background)
        prefs.background = originalBg

        // Test showSeconds setting
        val originalSeconds = prefs.showSeconds
        prefs.showSeconds = true
        assertTrue(prefs.showSeconds)
        prefs.showSeconds = false
        assertFalse(prefs.showSeconds)
        prefs.showSeconds = originalSeconds

        // Test weatherCity
        prefs.weatherCity = "London"
        assertEquals("London", prefs.weatherCity)
        prefs.weatherCity = ""
        assertEquals("", prefs.weatherCity)

        // Test defaultTilesPlaced
        val originalPlaced = prefs.defaultTilesPlaced
        prefs.defaultTilesPlaced = true
        assertTrue(prefs.defaultTilesPlaced)
        prefs.defaultTilesPlaced = originalPlaced
    }

    @Test
    fun testClockWidgetCreationAndBinding() {
        val clockWidget = ClockWidget(ctx)
        assertEquals(4, clockWidget.spanX)
        assertEquals(2, clockWidget.spanY)

        val host = AppWidgetHostView(ctx).also { clockWidget.onViewCreated(it) }

        val clockView = host.findViewById<DotMatrixClockView>(R.id.clock)
        assertNotNull("Clock view should exist", clockView)

        val dateView = host.findViewById<TextView>(R.id.clockDate)
        assertNotNull("Date view should exist", dateView)
        assertTrue("Date text should not be empty", dateView.text.isNotEmpty())

        val meridiem = host.findViewById<TextView>(R.id.clockMeridiem)
        assertNotNull("Meridiem view should exist", meridiem)
    }

    @Test
    fun testBatteryCalendarWidgetCreationAndBinding() {
        val widget = BatteryCalendarWidget(ctx)
        assertEquals(4, widget.spanX)
        assertEquals(2, widget.spanY)

        val host = AppWidgetHostView(ctx).also { widget.onViewCreated(it) }

        val batteryView = host.findViewById<BatteryDotsView>(R.id.battery)
        assertNotNull("Battery view should exist", batteryView)
        batteryView.level = 85
        assertEquals(85, batteryView.level)

        val calMonth = host.findViewById<TextView>(R.id.calMonth)
        assertNotNull("Calendar month should exist", calMonth)
        assertTrue("Calendar month text should not be empty", calMonth.text.isNotEmpty())

        val calDay = host.findViewById<DotMatrixView>(R.id.calDay)
        assertNotNull("Calendar day view should exist", calDay)
        assertTrue("Calendar day should have text", calDay.text.isNotEmpty())
    }

    @Test
    fun testCustomizeSheetHierarchy() {
        val sheet = LayoutInflater.from(ctx).inflate(R.layout.sheet_customize, null)
        assertNotNull(sheet)

        val tileWallpaper = sheet.findViewById<View>(R.id.tileWallpaper)
        assertNotNull("Tile Wallpaper should exist", tileWallpaper)
        assertNotNull(tileWallpaper.findViewById<ImageView>(R.id.tileIcon))
        assertNotNull(tileWallpaper.findViewById<TextView>(R.id.tileLabel))

        val tileWidgets = sheet.findViewById<View>(R.id.tileWidgets)
        assertNotNull("Tile Widgets should exist", tileWidgets)
        assertNotNull(tileWidgets.findViewById<ImageView>(R.id.tileIcon))
        assertNotNull(tileWidgets.findViewById<TextView>(R.id.tileLabel))

        val tileSettings = sheet.findViewById<View>(R.id.tileSettings)
        assertNotNull("Tile Settings should exist", tileSettings)
        assertNotNull(tileSettings.findViewById<ImageView>(R.id.tileIcon))
        assertNotNull(tileSettings.findViewById<TextView>(R.id.tileLabel))
    }

    @Test
    fun testDrawerHeaderInflation() {
        val header = LayoutInflater.from(ctx).inflate(R.layout.nt_drawer_header, null)
        assertNotNull(header)

        val countView = header.findViewById<TextView>(R.id.drawerCount)
        assertNotNull("Drawer count text view should exist", countView)
        countView.text = "042"
        assertEquals("042", countView.text.toString())

        val btn = header.findViewById<View>(R.id.drawerSettings)
        assertNotNull("Drawer settings button should exist", btn)
    }

    @Test
    fun testDotGridDrawable() {
        val bg = ContextCompat.getColor(ctx, R.color.nothing_bg)
        val dotOff = ContextCompat.getColor(ctx, R.color.dot_off)
        val drawable = DotGridDrawable(density, bg, dotOff)

        drawable.setBounds(0, 0, 1080, 2400)
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.draw(canvas)
        assertNotNull(bmp)
    }
}
