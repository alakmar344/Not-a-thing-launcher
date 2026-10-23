package com.nothinglauncher

import android.app.Dialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.nothinglauncher.databinding.ActivitySettingsBinding
import com.nothinglauncher.databinding.ItemSettingRowBinding

/** Launcher settings, built in the same dot-matrix / monochrome language as home. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: LauncherPrefs
    private var defaultRow: ItemSettingRowBinding? = null
    private var hiddenRow: ItemSettingRowBinding? = null
    private var dialog: Dialog? = null

    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshDynamicRows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = LauncherPrefs(this)

        binding.settingsRoot.background = DotGridDrawable(resources.displayMetrics.density)
        binding.backButton.setOnClickListener { finish() }

        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.settingsColumn.updatePadding(top = bars.top, bottom = bars.bottom + (32 * density).toInt())
            insets
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshDynamicRows()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun build() {
        val list = binding.settingsList
        list.removeAllViews()
        val accent = prefs.accentColor()

        SettingsUi.section(list, getString(R.string.section_home), accent)
        SettingsUi.card(list).also { card ->
            SettingsUi.switchRow(card, getString(R.string.setting_labels), getString(R.string.setting_labels_sub), prefs.showHomeLabels) {
                prefs.showHomeLabels = it
            }
            SettingsUi.choiceRow(card, getString(R.string.setting_columns), getString(R.string.setting_columns_sub), listOf("4", "5"), prefs.gridColumns - 4) {
                prefs.gridColumns = it + 4
            }
            SettingsUi.switchRow(card, getString(R.string.setting_seconds), getString(R.string.setting_seconds_sub), prefs.showSeconds) {
                prefs.showSeconds = it
            }
        }

        SettingsUi.section(list, getString(R.string.section_look), accent)
        SettingsUi.card(list).also { card ->
            SettingsUi.choiceRow(
                card, getString(R.string.setting_icons), getString(R.string.setting_icons_sub),
                listOf(getString(R.string.icons_mono), getString(R.string.icons_color)),
                prefs.iconStyle.ordinal
            ) { prefs.iconStyle = LauncherPrefs.IconStyle.values()[it] }
            SettingsUi.choiceRow(
                card, getString(R.string.setting_background), getString(R.string.setting_background_sub),
                listOf(getString(R.string.bg_dots), getString(R.string.bg_black), getString(R.string.bg_wallpaper)),
                prefs.background.ordinal
            ) { prefs.background = LauncherPrefs.Background.values()[it] }
            SettingsUi.choiceRow(
                card, getString(R.string.setting_accent), getString(R.string.setting_accent_sub),
                listOf(getString(R.string.accent_red), getString(R.string.accent_white)),
                prefs.accent.ordinal
            ) {
                prefs.accent = LauncherPrefs.Accent.values()[it]
                build() // re-tint section dots
            }
        }

        SettingsUi.section(list, getString(R.string.section_drawer), accent)
        SettingsUi.card(list).also { card ->
            SettingsUi.switchRow(card, getString(R.string.setting_recents), getString(R.string.setting_recents_sub), prefs.showRecents) {
                prefs.showRecents = it
            }
            SettingsUi.switchRow(card, getString(R.string.setting_keyboard), getString(R.string.setting_keyboard_sub), prefs.autoKeyboard) {
                prefs.autoKeyboard = it
            }
            hiddenRow = SettingsUi.actionRow(card, getString(R.string.setting_hidden), null) {
                dialog?.dismiss()
                dialog = Sheets.showHiddenApps(this, prefs) { refreshDynamicRows() }
                dialog?.setOnDismissListener { refreshDynamicRows() }
            }
        }

        SettingsUi.section(list, getString(R.string.section_gestures), accent)
        SettingsUi.card(list).also { card ->
            SettingsUi.choiceRow(
                card, getString(R.string.setting_swipe_down), getString(R.string.setting_swipe_down_sub),
                listOf(getString(R.string.swipe_notifications), getString(R.string.swipe_search)),
                prefs.swipeDown.ordinal
            ) { prefs.swipeDown = LauncherPrefs.SwipeDown.values()[it] }
        }

        SettingsUi.section(list, getString(R.string.section_system), accent)
        SettingsUi.card(list).also { card ->
            defaultRow = SettingsUi.actionRow(card, getString(R.string.setting_default), null, trailingIcon = R.drawable.ic_home) {
                requestDefaultHome()
            }
            SettingsUi.actionRow(card, getString(R.string.wallpaper), null, trailingIcon = R.drawable.ic_wallpaper) {
                if (prefs.background != LauncherPrefs.Background.WALLPAPER) {
                    prefs.background = LauncherPrefs.Background.WALLPAPER
                    build()
                }
                AppLauncher.openWallpaperPicker(this)
            }
            SettingsUi.actionRow(card, getString(R.string.setting_reset), getString(R.string.setting_reset_sub), destructive = true, trailingIcon = R.drawable.ic_refresh) {
                dialog?.dismiss()
                dialog = Sheets.showConfirm(this, R.string.reset_confirm_title, R.string.reset_confirm_body, R.string.reset) {
                    prefs.resetLayout()
                }
            }
            val version = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) { "" }
            SettingsUi.infoRow(card, getString(R.string.setting_about), getString(R.string.about_body, version))
        }
        refreshDynamicRows()
    }

    private fun refreshDynamicRows() {
        defaultRow?.let { row ->
            row.rowSubtitle.text = getString(if (isDefaultHome()) R.string.setting_default_on else R.string.setting_default_off)
            row.rowSubtitle.visibility = android.view.View.VISIBLE
        }
        hiddenRow?.let { row ->
            val n = prefs.hiddenApps.size
            row.rowSubtitle.text = if (n == 0) getString(R.string.setting_hidden_none) else getString(R.string.setting_hidden_count, n)
            row.rowSubtitle.visibility = android.view.View.VISIBLE
        }
    }

    private fun isDefaultHome(): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    } catch (e: Exception) {
        false
    }

    private fun requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    return
                } catch (_: Exception) {
                }
            }
        }
        if (!AppLauncher.start(this, Intent(Settings.ACTION_HOME_SETTINGS), showError = false)) {
            AppLauncher.start(this, Intent(Settings.ACTION_SETTINGS))
        }
    }
}
