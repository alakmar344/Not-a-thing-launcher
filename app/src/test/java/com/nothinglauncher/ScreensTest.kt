package com.nothinglauncher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.nothinglauncher.databinding.ActivitySettingsBinding
import com.nothinglauncher.databinding.DialogConfirmBinding
import com.nothinglauncher.databinding.FragmentAppDrawerBinding
import com.nothinglauncher.databinding.FragmentHomeBinding
import com.nothinglauncher.databinding.ItemAppBinding
import com.nothinglauncher.databinding.ItemWorkspacePageBinding
import com.nothinglauncher.databinding.SheetCustomizeBinding
import com.nothinglauncher.databinding.WidgetBatteryBinding
import com.nothinglauncher.databinding.WidgetCalendarBinding
import com.nothinglauncher.databinding.WidgetClockBinding
import com.nothinglauncher.databinding.WidgetCompassBinding
import com.nothinglauncher.databinding.WidgetPedometerBinding
import com.nothinglauncher.databinding.WidgetQuickTogglesBinding
import com.nothinglauncher.databinding.WidgetScreenTimeBinding
import com.nothinglauncher.databinding.WidgetStorageBinding
import com.nothinglauncher.databinding.WidgetWeatherBinding
import com.nothinglauncher.databinding.DialogWallpaperStyleBinding
import com.nothinglauncher.databinding.WidgetBatteryDotsBinding
import com.nothinglauncher.databinding.WidgetQuoteBinding
import com.nothinglauncher.databinding.WidgetWorldClockBinding
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot tests (Paparazzi / layoutlib) for every launcher surface, rendered with
 * deterministic sample data.
 */
class ScreensTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "Theme.NothingLauncher",
        renderingMode = SessionParams.RenderingMode.NORMAL,
        maxPercentDifference = 0.5
    )

    private val context: Context get() = paparazzi.context
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    // ---- Sample data -------------------------------------------------------------------

    private fun fakeIcon(bg: Int, glyph: Int, style: LauncherPrefs.IconStyle): Drawable {
        val fg = ContextCompat.getDrawable(context, glyph)!!.mutate().apply { setTint(Color.WHITE) }
        val layers = IconFactory.Layers(ColorDrawable(bg), InsetDrawable(fg, 0.36f), null)
        return IconFactory.renderLayers(context.resources, layers, style, context.resources.getDimensionPixelSize(R.dimen.icon_render_size))
    }

    private val sampleSpecs = listOf(
        Triple("Photos", 0xFF4285F4.toInt(), R.drawable.ic_wallpaper),
        Triple("Gmail", 0xFFEA4335.toInt(), R.drawable.ic_info),
        Triple("Maps", 0xFF34A853.toInt(), R.drawable.ic_globe),
        Triple("YouTube", 0xFFFF0000.toInt(), R.drawable.ic_shortcut),
        Triple("Calendar", 0xFF1A73E8.toInt(), R.drawable.ic_widgets),
        Triple("Clock", 0xFF202124.toInt(), R.drawable.ic_alarm),
        Triple("Play Store", 0xFF01875F.toInt(), R.drawable.ic_arrow_up_right),
        Triple("Spotify", 0xFF1DB954.toInt(), R.drawable.ic_tune),
        Triple("Phone", 0xFF0B8043.toInt(), R.drawable.ic_info),
        Triple("Messages", 0xFF1967D2.toInt(), R.drawable.ic_dock),
        Triple("Chrome", 0xFFFBBC04.toInt(), R.drawable.ic_globe),
        Triple("Camera", 0xFF5F6368.toInt(), R.drawable.ic_search),
        Triple("Files", 0xFF4285F4.toInt(), R.drawable.ic_home),
        Triple("Notes", 0xFFF9AB00.toInt(), R.drawable.ic_pin),
        Triple("Recorder", 0xFFD93025.toInt(), R.drawable.ic_bolt),
        Triple("Settings", 0xFF5F6368.toInt(), R.drawable.ic_tune),
        Triple("Weather", 0xFF1A73E8.toInt(), R.drawable.ic_globe),
        Triple("Wallet", 0xFF202124.toInt(), R.drawable.ic_widgets),
        Triple("Amazon", 0xFFFF9900.toInt(), R.drawable.ic_shortcut),
        Triple("Banking", 0xFF00704A.toInt(), R.drawable.ic_home),
        Triple("Discord", 0xFF5865F2.toInt(), R.drawable.ic_apps),
        Triple("Duolingo", 0xFF58CC02.toInt(), R.drawable.ic_info),
        Triple("Netflix", 0xFFE50914.toInt(), R.drawable.ic_arrow_up_right),
        Triple("Slack", 0xFF4A154B.toInt(), R.drawable.ic_apps),
        Triple("Uber", 0xFF000000.toInt(), R.drawable.ic_globe),
        Triple("WhatsApp", 0xFF25D366.toInt(), R.drawable.ic_dock)
    )

    private fun apps(style: LauncherPrefs.IconStyle = LauncherPrefs.IconStyle.MONO) =
        sampleSpecs.map { (label, color, glyph) ->
            AppInfo(label, "com.example.${label.lowercase().replace(" ", "")}", ".Main", fakeIcon(color, glyph, style))
        }

    private fun defaultBackground(): Drawable {
        val bg = ContextCompat.getColor(context, R.color.nothing_bg)
        val dot = ContextCompat.getColor(context, R.color.dot_off)
        return DotGridDrawable(context.resources.displayMetrics.density, backgroundColor = bg, dotColor = dot)
    }

    private fun elementLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        }

    private fun screen(content: View, background: Drawable = defaultBackground()): View {
        val root = FrameLayout(context)
        root.background = background
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    // ---- Home --------------------------------------------------------------------------

    private fun buildHome(columns: Int = 4, labels: Boolean = true, style: LauncherPrefs.IconStyle = LauncherPrefs.IconStyle.MONO): View {
        val b = FragmentHomeBinding.inflate(LayoutInflater.from(context))
        b.workspaceRecycler.updatePadding(top = dp(24))
        b.bottomBar.updatePadding(bottom = dp(16 + 12))

        val pageB = ItemWorkspacePageBinding.inflate(LayoutInflater.from(context))
        pageB.root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        pageB.pageContent.updatePadding(top = dp(12))
        pageB.cellLayout.countX = columns

        val clockB = WidgetClockBinding.inflate(LayoutInflater.from(context), pageB.cellLayout, false)
        clockB.clock.previewTime = 10 to 24
        clockB.secondsTicker.previewSecond = 37
        clockB.clockDate.text = "Thursday, 23 October"
        clockB.clockMeridiem.text = "24H"
        pageB.cellLayout.addView(clockB.root, CellLayout.LayoutParams(0, 0, columns, 2))

        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        val batB = WidgetBatteryBinding.inflate(LayoutInflater.from(context), row, false)
        batB.battery.level = 78

        val calB = WidgetCalendarBinding.inflate(LayoutInflater.from(context), row, false)
        calB.calMonth.text = "OCT"
        calB.calDay.text = "23"
        calB.calWeekday.text = "Thursday"
        calB.calAlarm.text = "FRI 07:30"

        val halfLp1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (6 * density).toInt() }
        val halfLp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (6 * density).toInt() }
        row.addView(batB.root, halfLp1)
        row.addView(calB.root, halfLp2)
        pageB.cellLayout.addView(row, CellLayout.LayoutParams(0, 2, columns, 1))

        val all = apps(style)
        all.take(columns * 2).forEachIndexed { index, app ->
            val col = index % columns
            val r = 3 + (index / columns)
            val appB = ItemAppBinding.inflate(LayoutInflater.from(context), pageB.cellLayout, false)
            appB.appIcon.setImageDrawable(app.icon)
            appB.appLabel.text = app.label
            appB.appLabel.isVisible = labels
            pageB.cellLayout.addView(appB.root, CellLayout.LayoutParams(col, r, 1, 1))
        }

        b.workspaceRecycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        b.workspaceRecycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1
            override fun onCreateViewHolder(p: ViewGroup, vt: Int) = object : RecyclerView.ViewHolder(pageB.root) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {}
        }

        b.pageIndicator.pageCount = 3
        b.pageIndicator.currentPage = 0
        b.dock.setApps(all.subList(8, 12))
        return screen(b.root)
    }

    @Test fun home() = paparazzi.snapshot(buildHome())

    @Test fun homeFiveColumnsNoLabelsColor() =
        paparazzi.snapshot(buildHome(columns = 5, labels = false, style = LauncherPrefs.IconStyle.COLOR))

    @Test fun homeEmpty() {
        val b = FragmentHomeBinding.inflate(LayoutInflater.from(context))
        b.workspaceRecycler.updatePadding(top = dp(24))
        b.bottomBar.updatePadding(bottom = dp(28))

        val pageB = ItemWorkspacePageBinding.inflate(LayoutInflater.from(context))
        pageB.pageContent.updatePadding(top = dp(12))

        val clockB = WidgetClockBinding.inflate(LayoutInflater.from(context), pageB.cellLayout, false)
        clockB.clock.previewTime = 21 to 7
        clockB.secondsTicker.previewSecond = 5
        clockB.clockDate.text = "Thursday, 23 October"
        pageB.cellLayout.addView(clockB.root, CellLayout.LayoutParams(0, 0, 4, 2))

        pageB.homeEmpty.isVisible = true

        b.workspaceRecycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        b.workspaceRecycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1
            override fun onCreateViewHolder(p: ViewGroup, vt: Int) = object : RecyclerView.ViewHolder(pageB.root) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {}
        }
        b.pageIndicator.pageCount = 2
        b.pageIndicator.currentPage = 0
        b.dock.isVisible = false
        paparazzi.snapshot(screen(b.root))
    }

    @Test fun homePage2WithWeatherAndToggles() {
        val b = FragmentHomeBinding.inflate(LayoutInflater.from(context))
        b.workspaceRecycler.updatePadding(top = dp(24))
        b.bottomBar.updatePadding(bottom = dp(16 + 12))

        val pageB = ItemWorkspacePageBinding.inflate(LayoutInflater.from(context))
        pageB.cellLayout.countX = 4

        val weatherB = WidgetWeatherBinding.inflate(LayoutInflater.from(context), pageB.cellLayout, false)
        pageB.cellLayout.addView(weatherB.root, CellLayout.LayoutParams(0, 0, 4, 1))

        val togB = WidgetQuickTogglesBinding.inflate(LayoutInflater.from(context), pageB.cellLayout, false)
        val accent = ContextCompat.getColor(context, R.color.nothing_red)
        togB.togglesDot.background?.mutate()?.setTint(accent)
        pageB.cellLayout.addView(togB.root, CellLayout.LayoutParams(0, 1, 4, 1))

        val all = apps()
        all.subList(12, 16).forEachIndexed { col, app ->
            val appB = ItemAppBinding.inflate(LayoutInflater.from(context), pageB.cellLayout, false)
            appB.appIcon.setImageDrawable(app.icon)
            appB.appLabel.text = app.label
            pageB.cellLayout.addView(appB.root, CellLayout.LayoutParams(col, 2, 1, 1))
        }

        b.workspaceRecycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        b.workspaceRecycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1
            override fun onCreateViewHolder(p: ViewGroup, vt: Int) = object : RecyclerView.ViewHolder(pageB.root) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {}
        }

        b.pageIndicator.pageCount = 3
        b.pageIndicator.setPageImmediately(1)
        b.dock.setApps(all.subList(8, 12))
        paparazzi.snapshot(screen(b.root))
    }

    @Test fun homePageWithScreenTimeAndStorage() {
        val b = FragmentHomeBinding.inflate(LayoutInflater.from(context))
        b.workspaceRecycler.updatePadding(top = dp(24))
        b.bottomBar.updatePadding(bottom = dp(16 + 12))

        val pageB = ItemWorkspacePageBinding.inflate(LayoutInflater.from(context))
        pageB.cellLayout.countX = 4

        val stB = WidgetScreenTimeBinding.inflate(LayoutInflater.from(context), pageB.cellLayout, false)
        pageB.cellLayout.addView(stB.root, CellLayout.LayoutParams(0, 0, 4, 1))

        val storB = WidgetStorageBinding.inflate(LayoutInflater.from(context), pageB.cellLayout, false)
        pageB.cellLayout.addView(storB.root, CellLayout.LayoutParams(0, 1, 4, 1))

        val wcB = WidgetWorldClockBinding.inflate(LayoutInflater.from(context), pageB.cellLayout, false)
        pageB.cellLayout.addView(wcB.root, CellLayout.LayoutParams(0, 2, 4, 1))

        val all = apps()
        all.take(8).forEachIndexed { index, app ->
            val col = index % 4
            val r = 3 + (index / 4)
            val appB = ItemAppBinding.inflate(LayoutInflater.from(context), pageB.cellLayout, false)
            appB.appIcon.setImageDrawable(app.icon)
            appB.appLabel.text = app.label
            pageB.cellLayout.addView(appB.root, CellLayout.LayoutParams(col, r, 1, 1))
        }

        b.workspaceRecycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        b.workspaceRecycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1
            override fun onCreateViewHolder(p: ViewGroup, vt: Int) = object : RecyclerView.ViewHolder(pageB.root) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {}
        }
        b.pageIndicator.pageCount = 3
        b.pageIndicator.setPageImmediately(2)
        b.dock.setApps(all.subList(8, 12))
        paparazzi.snapshot(screen(b.root))
    }

    // ---- Drawer ------------------------------------------------------------------------

    private fun buildDrawer(query: String? = null): View {
        val b = FragmentAppDrawerBinding.inflate(LayoutInflater.from(context))
        b.drawerColumn.updatePadding(top = dp(24))
        val all = apps().sortedBy { it.label.lowercase() }
        b.appCount.text = String.format("%03d", all.size)
        val adapter = AppAdapter({ _, _ -> }, { _, _ -> })
        adapter.iconSizePx = context.resources.getDimensionPixelSize(R.dimen.icon_size)
        adapter.accentColor = ContextCompat.getColor(context, R.color.nothing_red)
        val lm = GridLayoutManager(context, 4)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) == AppAdapter.TYPE_HEADER) 4 else 1
        }
        b.appsRecyclerView.layoutManager = lm
        b.appsRecyclerView.adapter = adapter
        b.appsRecyclerView.itemAnimator = null
        if (query == null) {
            val items = mutableListOf<DrawerItem>()
            items += DrawerItem.Header("recent", "Recent")
            listOf("Spotify", "Camera", "Maps", "Messages").forEach { n -> items += DrawerItem.App(all.first { it.label == n }, "recent") }
            items += DrawerItem.Header("all", "All apps", String.format("%03d", all.size))
            all.forEach { items += DrawerItem.App(it, "all") }
            adapter.submitList(items)
            b.alphabetScroller.letters = all.map { it.sectionLetter }.distinct()
        } else {
            b.searchBar.setText(query)
            b.clearSearch.isVisible = true
            b.alphabetScroller.isVisible = false
            b.emptyState.isVisible = true
            b.webSearchButton.text = context.getString(R.string.search_web_for, query)
            adapter.submitList(emptyList())
        }
        return screen(b.root, ColorDrawable(Color.BLACK))
    }

    @Test fun drawer() = paparazzi.snapshot(buildDrawer())

    @Test fun drawerNoMatch() = paparazzi.snapshot(buildDrawer("nothing phone"))

    // ---- Overlays ----------------------------------------------------------------------

    @Test fun appPopup() {
        val app = apps().first { it.label == "Spotify" }
        val popup = AppPopup(context)
        val content = popup.buildContent(
            app.label, "com.spotify.music", app.icon,
            listOf(
                AppPopup.ShortcutRow("Search", null) {},
                AppPopup.ShortcutRow("Liked songs", null) {}
            ),
            listOf(
                AppPopup.Action(R.drawable.ic_unpin, "Remove from home") {},
                AppPopup.Action(R.drawable.ic_dock, "Add to dock") {},
                AppPopup.Action(R.drawable.ic_info, "App info") {},
                AppPopup.Action(R.drawable.ic_hide, "Hide app") {},
                AppPopup.Action(R.drawable.ic_delete, "Uninstall", destructive = true) {}
            )
        )
        val home = buildHome()
        val scrim = View(context).apply { setBackgroundColor(0x66000000) }
        (home as FrameLayout).addView(scrim)
        home.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(120); topMargin = dp(300)
        })
        paparazzi.snapshot(home)
    }

    @Test fun customizeSheet() {
        val b = SheetCustomizeBinding.inflate(LayoutInflater.from(context))
        listOf(
            Triple(b.tileWidgets, R.drawable.ic_widgets, R.string.widgets),
            Triple(b.tileAddPage, R.drawable.ic_add, R.string.add_page),
            Triple(b.tileRemovePage, R.drawable.ic_delete, R.string.remove_page),
            Triple(b.tileWallpaper, R.drawable.ic_wallpaper, R.string.wallpaper_style),
            Triple(b.tileSettings, R.drawable.ic_tune, R.string.settings)
        ).forEach { (t, icon, label) -> t.tileIcon.setImageResource(icon); t.tileLabel.setText(label) }
        val home = buildHome() as FrameLayout
        home.addView(View(context).apply { setBackgroundColor(0x9E000000.toInt()) })
        home.addView(b.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM).apply {
            bottomMargin = dp(16)
        })
        paparazzi.snapshot(home)
    }

    @Test fun confirmDialog() {
        val b = DialogConfirmBinding.inflate(LayoutInflater.from(context))
        b.confirmTitle.setText(R.string.reset_confirm_title)
        b.confirmBody.setText(R.string.reset_confirm_body)
        b.confirmOk.setText(R.string.reset)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        root.addView(b.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        paparazzi.snapshot(root)
    }

    // ---- Settings ----------------------------------------------------------------------

    @Test fun settings() {
        val b = ActivitySettingsBinding.inflate(LayoutInflater.from(context))
        b.settingsRoot.background = defaultBackground()
        b.settingsColumn.updatePadding(top = dp(24))
        val list = b.settingsList
        val accent = ContextCompat.getColor(context, R.color.nothing_red)
        SettingsUi.section(list, "Home", accent)
        SettingsUi.card(list).also { card ->
            SettingsUi.switchRow(card, "App labels", "Show names under home screen icons", true) {}
            SettingsUi.choiceRow(card, "Grid columns", "Icons per row on home and in the drawer", listOf("4", "5"), 0) {}
            SettingsUi.switchRow(card, "Seconds ticker", "A live row of 60 dots under the clock", false) {}
        }
        SettingsUi.section(list, "Look", accent)
        SettingsUi.card(list).also { card ->
            SettingsUi.choiceRow(card, "Background", "Dot grid, pure black or your wallpaper", listOf("Dots", "Black", "Wallpaper"), 0) {}
        }
        paparazzi.snapshot(b.root)
    }

    @Test fun settingsLower() {
        val b = ActivitySettingsBinding.inflate(LayoutInflater.from(context))
        b.settingsRoot.background = defaultBackground()
        val list = b.settingsList
        val accent = ContextCompat.getColor(context, R.color.nothing_red)
        b.settingsTitle.isVisible = false
        b.settingsSubtitle.isVisible = false
        SettingsUi.section(list, "Drawer", accent)
        SettingsUi.card(list).also { card ->
            SettingsUi.switchRow(card, "Recent apps", "Show recently opened apps at the top", true) {}
            SettingsUi.actionRow(card, "Hidden apps", "2 hidden") {}
        }
        SettingsUi.section(list, "System", accent)
        SettingsUi.card(list).also { card ->
            SettingsUi.actionRow(card, "Default home app", "Not a Thing is your home screen", trailingIcon = R.drawable.ic_home) {}
            SettingsUi.actionRow(card, "Reset layout", "Restore the default home and dock apps", destructive = true, trailingIcon = R.drawable.ic_refresh) {}
            SettingsUi.infoRow(card, "About", "Version 8.7 · Type set in Space Mono & Space Grotesk (SIL OFL)")
        }
        paparazzi.snapshot(b.root)
    }

    // ---- Icons -------------------------------------------------------------------------

    @Test fun iconStyles() {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(16)) }
        listOf(LauncherPrefs.IconStyle.MONO, LauncherPrefs.IconStyle.COLOR).forEach { style ->
            val row = LinearLayout(context)
            apps(style).take(6).forEach { app ->
                row.addView(android.widget.ImageView(context).apply { setImageDrawable(app.icon) },
                    LinearLayout.LayoutParams(0, dp(56), 1f))
            }
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)))
        }
        val launcherIcon = android.widget.ImageView(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_letter_bubble)?.mutate()?.apply { setTint(0xFF1C1C1F.toInt()) }
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground))
        }
        column.addView(launcherIcon, LinearLayout.LayoutParams(dp(108), dp(108)))
        paparazzi.snapshot(screen(column, ColorDrawable(Color.BLACK)))
    }

    @Test fun customizeSheetStandalone() {
        val b = SheetCustomizeBinding.inflate(LayoutInflater.from(context))
        fun tile(t: com.nothinglauncher.databinding.ItemSheetTileBinding, icon: Int, label: Int, enabled: Boolean = true) {
            t.tileIcon.setImageResource(icon)
            t.tileLabel.setText(label)
            t.root.alpha = if (enabled) 1f else 0.4f
        }
        tile(b.tileWidgets, R.drawable.ic_widgets, R.string.widgets)
        tile(b.tileAddPage, R.drawable.ic_add, R.string.add_page)
        tile(b.tileRemovePage, R.drawable.ic_delete, R.string.remove_page, enabled = true)
        tile(b.tileWallpaper, R.drawable.ic_wallpaper, R.string.wallpaper_style)
        tile(b.tileSettings, R.drawable.ic_tune, R.string.settings)
        paparazzi.snapshot(screen(b.root))
    }

    @Test fun wallpaperStyleDialog() {
        val b = DialogWallpaperStyleBinding.inflate(LayoutInflater.from(context))
        b.btnBgDots.setBackgroundResource(R.drawable.bg_pill_white)
        b.btnBgDots.setTextColor(ContextCompat.getColor(context, R.color.pill_active_text))
        b.btnThemeSystem.setBackgroundResource(R.drawable.bg_pill_white)
        b.btnThemeSystem.setTextColor(ContextCompat.getColor(context, R.color.pill_active_text))
        paparazzi.snapshot(screen(b.root))
    }

    @Test fun widgetBatteryDots() {
        val b = WidgetBatteryDotsBinding.inflate(LayoutInflater.from(context))
        b.batteryDotsView.batteryLevel = 82
        b.batteryDotsView.isCharging = true
        val container = FrameLayout(context).apply {
            setPadding(dp(16), dp(24), dp(16), dp(16))
            addView(b.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        paparazzi.snapshot(screen(container))
    }

    @Test fun widgetQuote() {
        val b = WidgetQuoteBinding.inflate(LayoutInflater.from(context))
        b.quoteDotsView.quoteText = "NOTHING BUT TIME"
        val container = FrameLayout(context).apply {
            setPadding(dp(16), dp(24), dp(16), dp(16))
            addView(b.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        paparazzi.snapshot(screen(container))
    }
}
