package com.nothinglauncher.settings

import android.app.Dialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherFiles
import com.android.launcher3.LauncherPrefs
import com.nothinglauncher.DotGridDrawable
import com.nothinglauncher.Intents
import com.nothinglauncher.MediaSessionRepository
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.SettingsUi
import com.nothinglauncher.Sheets
import com.nothinglauncher.WeatherRepository
import com.nothinglauncher.databinding.ActivitySettingsBinding
import com.nothinglauncher.databinding.ItemSettingRowBinding

/**
 * The v10 settings screen, wired to stock Launcher3 where Launcher3 owns the behaviour (grid,
 * icon style, notification dots, new-app icons, rotation) and to [NothingPrefs] for the
 * Not a Thing layer (backdrop, LED clock, weather).
 */
class NothingSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { NothingPrefs.get(this) }
    private var dialog: Dialog? = null
    private val statusRows = mutableListOf<Pair<ItemSettingRowBinding, () -> CharSequence>>()

    /** Launcher3's own preference file (same keys as its stock settings screen). */
    private val launcherPrefs by lazy {
        getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.settingsRoot.background = DotGridDrawable(
            resources.displayMetrics.density,
            backgroundColor = ContextCompat.getColor(this, R.color.nothing_bg),
            dotColor = ContextCompat.getColor(this, R.color.dot_off)
        )
        binding.backButton.setOnClickListener { finish() }
        val density = resources.displayMetrics.density
        // Edge-to-edge is enforced from targetSdk 35 and can't be opted out of from 36, so the
        // side insets matter too: in landscape the 3-button nav bar and a display cutout sit on
        // the left/right edge, and without these the rows would run underneath them.
        val column = binding.settingsColumn
        val baseLeft = column.paddingLeft
        val baseRight = column.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            column.updatePadding(
                left = baseLeft + bars.left,
                top = bars.top,
                right = baseRight + bars.right,
                bottom = bars.bottom + (32 * density).toInt()
            )
            insets
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun build() {
        val list = binding.settingsList
        list.removeAllViews()
        statusRows.clear()
        val accent = ContextCompat.getColor(this, R.color.nothing_red)

        SettingsUi.section(list, getString(R.string.section_home), accent)
        SettingsUi.card(list).also { card ->
            val grids = listOf("3_by_3", "4_by_4", "5_by_5")
            val current = grids.indexOf(InvariantDeviceProfile.getCurrentGridName(this))
            // Phone grids only; tablets use their own (e.g. 6×5) grid, which is left alone.
            if (current >= 0) {
                SettingsUi.choiceRow(
                    card, getString(R.string.nt_setting_grid), getString(R.string.nt_setting_grid_sub),
                    listOf("3 × 3", "4 × 4", "5 × 5"), current
                ) { InvariantDeviceProfile.INSTANCE.get(this).setCurrentGrid(this, grids[it]) }
            }
            SettingsUi.switchRow(
                card, getString(R.string.setting_seconds), getString(R.string.setting_seconds_sub),
                prefs.showSeconds
            ) { prefs.showSeconds = it }
            SettingsUi.switchRow(
                card, getString(R.string.nt_setting_add_new), getString(R.string.nt_setting_add_new_sub),
                launcherPrefs.getBoolean(KEY_ADD_ICON, true)
            ) { launcherPrefs.edit().putBoolean(KEY_ADD_ICON, it).apply() }
            SettingsUi.switchRow(
                card, getString(R.string.nt_setting_rotation), getString(R.string.nt_setting_rotation_sub),
                LauncherPrefs.get(this).get(LauncherPrefs.ALLOW_ROTATION)
            ) { LauncherPrefs.get(this).put(LauncherPrefs.ALLOW_ROTATION.to(it)) }
        }

        SettingsUi.section(list, getString(R.string.section_look), accent)
        SettingsUi.card(list).also { card ->
            SettingsUi.choiceRow(
                card, getString(R.string.setting_icons), getString(R.string.setting_icons_sub),
                listOf(getString(R.string.icons_mono), getString(R.string.icons_color)),
                if (LauncherPrefs.get(this).get(LauncherPrefs.THEMED_ICONS)) 0 else 1
            ) {
                // Launcher3 re-renders every icon when this switch changes.
                LauncherPrefs.get(this).put(LauncherPrefs.THEMED_ICONS.to(it == 0))
            }
            SettingsUi.choiceRow(
                card, getString(R.string.setting_background), getString(R.string.setting_background_sub),
                listOf(getString(R.string.bg_dots), getString(R.string.bg_black), getString(R.string.bg_wallpaper)),
                prefs.background.ordinal
            ) { prefs.background = NothingPrefs.Background.entries[it] }
            SettingsUi.actionRow(card, getString(R.string.nt_choose_wallpaper), null, trailingIcon = R.drawable.ic_wallpaper) {
                Intents.openWallpaperPicker(this)
            }
        }

        SettingsUi.section(list, getString(R.string.setting_capabilities), accent)
        SettingsUi.card(list).also { card ->
            statusRows += SettingsUi.actionRow(
                card, getString(R.string.setting_notification_access), null, trailingIcon = R.drawable.ic_play
            ) { Intents.openNotificationAccess(this) } to {
                getString(
                    if (MediaSessionRepository.hasNotificationAccess(this)) R.string.setting_notification_on
                    else R.string.setting_notification_off
                )
            }
            statusRows += SettingsUi.actionRow(
                card, getString(R.string.setting_weather_city), null, trailingIcon = R.drawable.ic_globe
            ) { showWeatherCityEditor() } to {
                val city = prefs.weatherCity.trim()
                if (city.isEmpty()) getString(R.string.setting_weather_auto)
                else getString(R.string.setting_weather_manual, city)
            }
        }

        SettingsUi.section(list, getString(R.string.section_system), accent)
        SettingsUi.card(list).also { card ->
            statusRows += SettingsUi.actionRow(
                card, getString(R.string.setting_default), null, trailingIcon = R.drawable.ic_home
            ) { requestDefaultHome() } to {
                getString(if (isDefaultHome()) R.string.setting_default_on else R.string.setting_default_off)
            }
            SettingsUi.actionRow(
                card, getString(R.string.nt_setting_advanced), getString(R.string.nt_setting_advanced_sub)
            ) {
                Intents.start(this, Intent(this, com.android.launcher3.settings.SettingsActivity::class.java))
            }
            SettingsUi.infoRow(card, getString(R.string.setting_about), getString(R.string.about_body, versionName()))
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        statusRows.forEach { (row, status) ->
            row.rowSubtitle.text = status()
            row.rowSubtitle.visibility = android.view.View.VISIBLE
        }
    }

    private fun showWeatherCityEditor() {
        dialog?.dismiss()
        dialog = Sheets.showTextInput(
            this,
            getString(R.string.weather_city_title),
            getString(R.string.weather_city_hint),
            prefs.weatherCity,
            maxLength = 64
        ) { city ->
            WeatherRepository.setCity(this, prefs, city) { }
            refreshStatus()
        }
    }

    private fun isDefaultHome(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == packageName
    }

    @Suppress("DEPRECATION")
    private fun requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME)
                    return
                } catch (_: Exception) {
                }
            }
        }
        Intents.start(this, Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun versionName(): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName.orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }

    private companion object {
        const val REQUEST_HOME = 41
        const val KEY_ADD_ICON = "pref_add_icon_to_home"
    }
}
