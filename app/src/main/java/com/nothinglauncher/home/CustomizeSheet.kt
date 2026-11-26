package com.nothinglauncher.home

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher3.Launcher
import com.android.launcher3.widget.picker.WidgetsFullSheet
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.DeskHudActivity
import com.nothinglauncher.DotMatrixView
import com.nothinglauncher.Intents
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.SettingsUi
import com.nothinglauncher.SetupBackupManager
import com.nothinglauncher.Sheets
import com.nothinglauncher.Tactile
import com.nothinglauncher.databinding.ItemSheetTileBinding
import com.nothinglauncher.databinding.SheetCustomizeBinding
import com.nothinglauncher.drawer.HiddenVaultSheet
import com.nothinglauncher.settings.NothingSettingsActivity

/** The Customize sheet: Wallpaper & style, Widgets, Settings, plus quick studio chips. */
object CustomizeSheet {

    private var currentDialog: Dialog? = null

    fun dismissActive() {
        currentDialog?.dismiss()
        currentDialog = null
    }

    private fun track(dialog: Dialog) {
        currentDialog = dialog
        dialog.setOnDismissListener { if (currentDialog === dialog) currentDialog = null }
    }

    fun show(launcher: Launcher) {
        val b = SheetCustomizeBinding.inflate(LayoutInflater.from(launcher))
        val dialog = Sheets.create(launcher, b.root)
        track(dialog)
        fun tile(t: ItemSheetTileBinding, icon: Int, label: Int, action: () -> Unit) {
            t.tileIcon.setImageResource(icon)
            t.tileLabel.setText(label)
            t.root.contentDescription = launcher.getString(label)
            Tactile.press(t.root)
            t.root.setOnClickListener {
                dialog.dismiss()
                action()
            }
        }
        tile(b.tileWallpaper, R.drawable.ic_wallpaper, R.string.nt_tile_wallpaper) { showStyle(launcher) }
        tile(b.tileWidgets, R.drawable.nt_ic_apps, R.string.nt_tile_widgets) {
            WidgetsFullSheet.show(launcher, true)
        }
        tile(b.tileSettings, R.drawable.ic_tune, R.string.nt_tile_settings) {
            Intents.start(launcher, Intent(launcher, NothingSettingsActivity::class.java))
        }

        fun chip(v: TextView, action: () -> Unit) {
            Tactile.press(v)
            v.setOnClickListener {
                Tactile.tick(v)
                dialog.dismiss()
                action()
            }
        }
        chip(b.chipEssential) { (launcher as? NothingLauncher)?.openEssentialSpace() }
        chip(b.chipDeskHud) { DeskHudActivity.launch(launcher) }
        chip(b.chipPresets) { SetupBackupManager.showPresetsSheet(launcher) { launcher.recreate() } }
        chip(b.chipVault) {
            HiddenVaultSheet.openWithAuth(launcher) {
                (launcher as? NothingLauncher)?.refreshHiddenApps()
            }
        }
        Sheets.showSafely(launcher, dialog)
    }

    /** Background style, widget surfaces studio, and the system wallpaper picker. */
    private fun showStyle(launcher: Launcher) {
        val prefs = NothingPrefs.get(launcher)
        val root = LayoutInflater.from(launcher).inflate(R.layout.sheet_style, null)
        val dialog = Sheets.create(launcher, root)
        track(dialog)
        val list = root.findViewById<LinearLayout>(R.id.styleList)
        SettingsUi.card(list).also { card ->
            SettingsUi.choiceRow(
                card,
                launcher.getString(R.string.setting_background),
                launcher.getString(R.string.setting_background_sub),
                listOf(
                    launcher.getString(R.string.bg_dots),
                    launcher.getString(R.string.bg_black),
                    launcher.getString(R.string.bg_wallpaper)
                ),
                prefs.background.ordinal
            ) {
                prefs.background = NothingPrefs.Background.entries[it]
                dialog.dismiss()
                launcher.recreate()
            }
            SettingsUi.actionRow(
                card,
                launcher.getString(R.string.nt_setting_widget_surfaces),
                launcher.getString(R.string.nt_setting_widget_surfaces_sub)
            ) {
                dialog.dismiss()
                showWidgetSurfacesSheet(launcher) { launcher.recreate() }
            }
            SettingsUi.actionRow(card, launcher.getString(R.string.nt_choose_wallpaper), null, trailingIcon = R.drawable.ic_wallpaper) {
                dialog.dismiss()
                Intents.openWallpaperPicker(launcher)
            }
        }
        Sheets.showSafely(launcher, dialog)
    }

    /** Per-widget surface studio: switch any element between Default, Obsidian Dark, Bone Paper, and Frosted Glass. */
    fun showWidgetSurfacesSheet(context: Context, onChanged: () -> Unit = {}) {
        val prefs = NothingPrefs.get(context)
        val d = context.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * d).toInt(), pad, pad)
        }
        val title = DotMatrixView(context).apply {
            text = context.getString(R.string.nt_setting_widget_surfaces).uppercase()
            dotColor = context.getColor(R.color.text_primary)
            dotScale = 0.40f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (18 * d).toInt()
            )
        }
        container.addView(title)
        val scroll = androidx.core.widget.NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (380 * d).toInt()
            ).apply { topMargin = (12 * d).toInt() }
        }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(inner)
        container.addView(scroll)

        val widgets = listOf(
            "clock" to R.string.element_clock,
            "battery" to R.string.battery,
            "calendar" to R.string.calendar_widget,
            "weather" to R.string.element_weather,
            "media" to R.string.element_media,
            "timer" to R.string.element_timer,
            "note" to R.string.element_note,
            "habit" to R.string.element_habit,
            "vitals" to R.string.element_vitals,
            "compass" to R.string.element_compass,
            "folder" to R.string.element_folder,
            "desk" to R.string.element_desk,
            "kinetic" to R.string.element_kinetic
        )
        val surfaces = AccentTheme.WidgetSurface.entries
        val surfaceLabels = surfaces.map { context.getString(it.labelRes) }
        val card = SettingsUi.card(inner)
        widgets.forEach { (key, labelRes) ->
            SettingsUi.choiceRow(
                card,
                context.getString(labelRes),
                null,
                surfaceLabels,
                surfaces.indexOf(prefs.widgetSurface(key)).coerceAtLeast(0)
            ) { idx ->
                prefs.setWidgetSurface(key, surfaces[idx])
                onChanged()
            }
        }
        val dialog = Sheets.create(context, container)
        track(dialog)
        Sheets.showSafely(context, dialog)
    }
}
