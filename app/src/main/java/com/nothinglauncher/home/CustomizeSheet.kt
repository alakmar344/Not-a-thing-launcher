package com.nothinglauncher.home

import android.app.Dialog
import android.content.Intent
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.android.launcher3.Launcher
import com.android.launcher3.widget.picker.WidgetsFullSheet
import com.nothinglauncher.Intents
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.SettingsUi
import com.nothinglauncher.Sheets
import com.nothinglauncher.databinding.ItemSheetTileBinding
import com.nothinglauncher.databinding.SheetCustomizeBinding
import com.nothinglauncher.settings.NothingSettingsActivity

/** The v10 "Customize" sheet: Wallpaper & style, Widgets, Settings. */
object CustomizeSheet {

    // Tracked so the Activity can dismiss it on teardown — a config change this Activity doesn't
    // list (e.g. system dark/light switching while the sheet is open) destroys NothingLauncher
    // without going through any of the dialog's own dismiss paths, which would otherwise leave
    // the sheet's window attached to a dead Activity token (logged as "WindowLeaked").
    private var currentDialog: Dialog? = null

    /** Dismisses whichever sheet from this object is currently showing, if any. Call from the
     * hosting Activity's onDestroy(). */
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
            com.nothinglauncher.Tactile.press(t.root)
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
        Sheets.showSafely(launcher, dialog)
    }

    /** Background style (dot grid, black, wallpaper) plus the system wallpaper picker. */
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
            SettingsUi.actionRow(card, launcher.getString(R.string.nt_choose_wallpaper), null, trailingIcon = R.drawable.ic_wallpaper) {
                dialog.dismiss()
                Intents.openWallpaperPicker(launcher)
            }
        }
        Sheets.showSafely(launcher, dialog)
    }
}
