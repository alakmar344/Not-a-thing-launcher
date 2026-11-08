package com.nothinglauncher

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.nothinglauncher.widgets.BatteryCalendarWidget
import com.nothinglauncher.widgets.ClockWidget
import android.appwidget.AppWidgetHostView
import com.nothinglauncher.perf.VisualTier
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Renders the Not a Thing surfaces from the real layouts, styles and icon factory, arranged the
 * way Launcher3 shows them (Launcher3's own containers need a running launcher, so the stage is
 * assembled here). CI publishes these images with every release.
 */
abstract class ScreensTest(private val night: Boolean) {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT,
            softButtons = false
        ),
        theme = if (night) "NtTheme.Backdrop.Dark" else "NtTheme.Backdrop.Light",
        maxPercentDifference = 0.5
    )

    private val ctx get() = paparazzi.context
    private val density get() = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(ctx, id)

    /** Goldens show the full look (8 GB-class phones); [homeLite] shows the Lite tier. */
    @Before
    fun fullTier() = VisualTier.setForTests(VisualTier.Tier.RICH)

    @After
    fun resetTier() = VisualTier.setForTests(null)

    private fun backdrop(child: View): View = FrameLayout(ctx).apply {
        background = DotGridDrawable.forTier(ctx)
        addView(child, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** A sample app icon (glyph on a coloured adaptive background) through the v10 factory. */
    private fun icon(glyph: Int, bg: Int, style: NothingPrefs.IconStyle): ImageView {
        val fg = ContextCompat.getDrawable(ctx, glyph)!!.mutate().apply { setTint(Color.WHITE) }
        // Layoutlib has no system icon mask, so feed the factory the adaptive layers directly.
        val layers = IconFactory.Layers(ColorDrawable(bg), InsetDrawable(fg, 0.34f), null)
        return ImageView(ctx).apply {
            setImageDrawable(IconFactory.renderLayers(ctx.resources, layers, style, dp(56)))
        }
    }

    private fun appCell(label: String, glyph: Int, bg: Int, style: NothingPrefs.IconStyle, showLabel: Boolean = true) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(icon(glyph, bg, style), LinearLayout.LayoutParams(dp(56), dp(56)))
            if (showLabel) addView(TextView(ctx, null, 0, com.android.launcher3.R.style.BaseIcon_Workspace).apply {
                text = label
                setTextColor(color(R.color.text_primary))
                textSize = 13f
                setPadding(0, dp(6), 0, 0)
            })
        }

    private val apps = listOf(
        Triple("Photos", R.drawable.ic_wallpaper, 0xFF4285F4.toInt()),
        Triple("Browser", R.drawable.ic_globe, 0xFF34A853.toInt()),
        Triple("Clock", R.drawable.ic_alarm, 0xFF5F6368.toInt()),
        Triple("Settings", R.drawable.ic_tune, 0xFF1A73E8.toInt()),
        Triple("Music", R.drawable.ic_play, 0xFFEA4335.toInt()),
        Triple("Search", R.drawable.ic_search, 0xFFFBBC04.toInt()),
        Triple("Home", R.drawable.ic_home, 0xFF00897B.toInt()),
        Triple("Info", R.drawable.ic_info, 0xFF8E24AA.toInt())
    )

    private fun row(items: List<Triple<String, Int, Int>>, style: NothingPrefs.IconStyle, labels: Boolean = true) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            items.forEach { (l, g, b) ->
                addView(appCell(l, g, b, style, labels), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun widget(w: com.nothinglauncher.widgets.NothingWidget, heightDp: Int) =
        AppWidgetHostView(ctx).also { w.onViewCreated(it) } to LinearLayout.LayoutParams(MATCH, dp(heightDp))

    private fun home(style: NothingPrefs.IconStyle): View {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(24))
        }
        widget(ClockWidget(ctx), 196).let { (v, lp) -> column.addView(v, lp) }
        widget(BatteryCalendarWidget(ctx), 176).let { (v, lp) ->
            // The renderer has no battery; show a typical level like the v10 showcase.
            v.findViewById<BatteryDotsView>(R.id.battery).level = 78
            column.addView(v, lp.apply { topMargin = dp(12) })
        }
        column.addView(row(apps.take(4), style), LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) })
        column.addView(row(apps.drop(4), style), LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        column.addView(View(ctx), LinearLayout.LayoutParams(MATCH, 0, 1f))
        column.addView(row(apps.takeLast(4), style, labels = false), LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(
            LayoutInflater.from(ctx).inflate(R.layout.search_container_hotseat, column, false),
            LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(16) }
        )
        return backdrop(column)
    }

    @Test
    fun homeMono() = paparazzi.snapshot(home(NothingPrefs.IconStyle.MONO))

    @Test
    fun homeColor() = paparazzi.snapshot(home(NothingPrefs.IconStyle.COLOR))

    /** 3–4 GB phones: flat grid, no glow or light pool. */
    @Test
    fun homeLite() {
        VisualTier.setForTests(VisualTier.Tier.LITE)
        paparazzi.snapshot(home(NothingPrefs.IconStyle.MONO))
    }

    /** The LED clock's power-on scan, frozen just past half way. */
    @Test
    fun clockPowerOn() {
        val home = home(NothingPrefs.IconStyle.MONO)
        fun find(v: View): DotMatrixClockView? = v as? DotMatrixClockView
            ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        requireNotNull(find(home)) { "no clock on home" }.reveal = 0.58f
        paparazzi.snapshot(home)
    }

    @Test
    fun drawer() {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.nothing_bg))
            setPadding(0, dp(24), 0, 0)
        }
        // Header from the overridden all_apps_content.xml, search box in the same pill style.
        val header = LayoutInflater.from(ctx).inflate(R.layout.nt_drawer_header, column, false)
        header.findViewById<TextView>(R.id.drawerCount).text = "026"
        column.addView(header, LinearLayout.LayoutParams(MATCH, dp(64)))
        column.addView(TextView(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_search_pill)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            text = ctx.getString(com.android.launcher3.R.string.all_apps_search_bar_hint)
            setTextColor(color(R.color.text_secondary))
            textSize = 16f
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { setMargins(dp(20), dp(4), dp(20), dp(24)) })
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        grid.addView(row(apps.take(4), NothingPrefs.IconStyle.MONO))
        grid.addView(row(apps.drop(4), NothingPrefs.IconStyle.MONO), LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        column.addView(grid)
        paparazzi.snapshot(column)
    }

    @Test
    fun customizeSheet() {
        val sheet = LayoutInflater.from(ctx).inflate(R.layout.sheet_customize, null)
        listOf(
            Triple(R.id.tileWallpaper, R.drawable.ic_wallpaper, R.string.nt_tile_wallpaper),
            Triple(R.id.tileWidgets, R.drawable.nt_ic_apps, R.string.nt_tile_widgets),
            Triple(R.id.tileSettings, R.drawable.ic_tune, R.string.nt_tile_settings)
        ).forEach { (id, icon, label) ->
            val tile = sheet.findViewById<View>(id)
            tile.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
            tile.findViewById<TextView>(R.id.tileLabel).setText(label)
        }
        val stage = FrameLayout(ctx).apply {
            addView(home(NothingPrefs.IconStyle.MONO))
            addView(View(ctx).apply { setBackgroundColor(0x99000000.toInt()) })
            addView(sheet, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        paparazzi.snapshot(stage)
    }

    @Test
    fun settings() {
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_settings, null)
        root.findViewById<View>(R.id.settingsRoot).background = DotGridDrawable.forTier(ctx)
        val list = root.findViewById<LinearLayout>(R.id.settingsList)
        val accent = color(R.color.nothing_red)
        SettingsUi.section(list, ctx.getString(R.string.section_home), accent)
        SettingsUi.card(list).also { card ->
            SettingsUi.choiceRow(card, ctx.getString(R.string.nt_setting_grid), ctx.getString(R.string.nt_setting_grid_sub), listOf("3 × 3", "4 × 4", "5 × 5"), 1) {}
            SettingsUi.switchRow(card, ctx.getString(R.string.setting_seconds), ctx.getString(R.string.setting_seconds_sub), true) {}
        }
        SettingsUi.section(list, ctx.getString(R.string.section_look), accent)
        SettingsUi.card(list).also { card ->
            SettingsUi.choiceRow(card, ctx.getString(R.string.setting_icons), ctx.getString(R.string.setting_icons_sub), listOf(ctx.getString(R.string.icons_mono), ctx.getString(R.string.icons_color)), 0) {}
            SettingsUi.choiceRow(card, ctx.getString(R.string.setting_background), ctx.getString(R.string.setting_background_sub), listOf(ctx.getString(R.string.bg_dots), ctx.getString(R.string.bg_black), ctx.getString(R.string.bg_wallpaper)), 0) {}
            SettingsUi.choiceRow(card, ctx.getString(R.string.setting_quality), ctx.getString(R.string.setting_quality_sub_auto, "7.4", 8, ctx.getString(R.string.quality_tier_rich)), listOf(ctx.getString(R.string.quality_auto), ctx.getString(R.string.quality_lite), ctx.getString(R.string.quality_full)), 0) {}
        }
        paparazzi.snapshot(root)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}

class ScreensDarkTest : ScreensTest(night = true)

class ScreensLightTest : ScreensTest(night = false)
