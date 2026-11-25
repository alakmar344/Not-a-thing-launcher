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
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.DotGridDrawable
import com.nothinglauncher.Intents
import com.nothinglauncher.LauncherEntryActivity
import com.nothinglauncher.MediaSessionRepository
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.SettingsUi
import com.nothinglauncher.SetupBackupManager
import com.nothinglauncher.Sheets
import com.nothinglauncher.Tactile
import com.nothinglauncher.WeatherRepository
import com.nothinglauncher.databinding.ActivitySettingsBinding
import com.nothinglauncher.databinding.ItemSettingRowBinding
import com.nothinglauncher.drawer.HiddenVaultSheet
import com.nothinglauncher.icons.IconPackManager
import com.nothinglauncher.icons.NothingIconLogic
import com.nothinglauncher.perf.VisualTier

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
        binding.settingsRoot.background = DotGridDrawable.forTier(this)
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
        buildV13Sections()
        if (savedInstanceState == null && VisualTier.effects(this).entranceMotion &&
            VisualTier.motionAllowed(this)
        ) {
            SettingsUi.playEntrance(binding.settingsList)
        }
    }

    private val v13StatusRefreshers = mutableListOf<() -> Unit>()

    override fun onResume() {
        super.onResume()
        refreshStatus()
        v13StatusRefreshers.forEach { it() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (SetupBackupManager.handleActivityResult(this, requestCode, resultCode, data) { recreate() }) return
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun buildV13Sections() {
        val d = resources.displayMetrics.density
        var insertAt = (binding.settingsList.childCount - 2).coerceAtLeast(0)

        fun addSection(titleRes: Int): android.widget.LinearLayout {
            val header = android.widget.TextView(this, null, 0, R.style.Text_Label).apply {
                setText(titleRes)
                setPadding(
                    (6 * d).toInt(),
                    (22 * d).toInt(),
                    (6 * d).toInt(),
                    (10 * d).toInt()
                )
            }
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_tile_dark)
                clipToOutline = true
            }
            val list = binding.settingsList
            list.addView(header, insertAt++)
            list.addView(card, insertAt++)
            return card
        }

        fun addDivider(card: android.widget.LinearLayout) {
            card.addView(android.view.View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * d).toInt().coerceAtLeast(1)
                ).apply { marginStart = (18 * d).toInt() }
                setBackgroundColor(getColor(R.color.hairline))
            })
        }

        fun addChoice(
            card: android.widget.LinearLayout,
            titleRes: Int,
            subProvider: () -> String,
            onClick: () -> Unit
        ) {
            if (card.childCount > 0) addDivider(card)
            val row = ItemSettingRowBinding.inflate(layoutInflater, card, false)
            row.rowTitle.setText(titleRes)
            fun updateSub() {
                row.rowSubtitle.text = subProvider()
                row.rowSubtitle.visibility = android.view.View.VISIBLE
            }
            updateSub()
            v13StatusRefreshers += ::updateSub
            Tactile.press(row.root)
            row.root.setOnClickListener {
                Tactile.tick(row.root)
                onClick()
            }
            card.addView(row.root)
        }

        fun addSwitch(
            card: android.widget.LinearLayout,
            titleRes: Int,
            subRes: Int?,
            getter: () -> Boolean,
            setter: (Boolean) -> Unit
        ) {
            if (card.childCount > 0) addDivider(card)
            val row = ItemSettingRowBinding.inflate(layoutInflater, card, false)
            row.rowTitle.setText(titleRes)
            if (subRes != null) {
                row.rowSubtitle.setText(subRes)
                row.rowSubtitle.visibility = android.view.View.VISIBLE
            } else {
                row.rowSubtitle.visibility = android.view.View.GONE
            }
            val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
                isChecked = getter()
                isClickable = false
                isFocusable = false
            }
            row.rowTrailing.addView(sw)
            v13StatusRefreshers += { sw.isChecked = getter() }
            Tactile.press(row.root)
            row.root.setOnClickListener {
                val next = !getter()
                setter(next)
                sw.isChecked = next
                Tactile.tick(row.root)
                v13StatusRefreshers.forEach { it() }
            }
            card.addView(row.root)
        }

        fun pickChoice(titleRes: Int, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
            dialog?.dismiss()
            dialog = android.app.AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(labels.toTypedArray(), selected) { dlg, which ->
                    dlg.dismiss()
                    onPick(which)
                    v13StatusRefreshers.forEach { it() }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        fun gestureLabel(a: NothingPrefs.GestureAction): String = getString(
            when (a) {
                NothingPrefs.GestureAction.NONE -> R.string.nt_gesture_none
                NothingPrefs.GestureAction.NOTIFICATIONS -> R.string.nt_gesture_notifications
                NothingPrefs.GestureAction.SEARCH -> R.string.nt_gesture_search
                NothingPrefs.GestureAction.ESSENTIAL_SPACE -> R.string.nt_gesture_essential
                NothingPrefs.GestureAction.LOCK_SCREEN -> R.string.nt_gesture_lock
                NothingPrefs.GestureAction.CUSTOMIZE -> R.string.nt_gesture_customize
                NothingPrefs.GestureAction.VAULT -> R.string.nt_gesture_vault
            }
        )

        fun pickGesture(titleRes: Int, current: () -> NothingPrefs.GestureAction, assign: (NothingPrefs.GestureAction) -> Unit) {
            val all = NothingPrefs.GestureAction.entries
            val labels = all.map(::gestureLabel)
            pickChoice(titleRes, labels, all.indexOf(current())) { assign(all[it]) }
        }

        // 1. Personality & Physics
        val lookCard = addSection(R.string.nt_section_personalize)
        addChoice(lookCard, R.string.nt_setting_accent, { getString(prefs.accentPalette.labelRes) }) {
            val entries = AccentTheme.Palette.entries
            pickChoice(
                R.string.nt_setting_accent,
                entries.map { getString(it.labelRes) },
                entries.indexOf(prefs.accentPalette)
            ) { idx ->
                prefs.accentPalette = entries[idx]
            }
        }
        addChoice(lookCard, R.string.nt_setting_icon_style, {
            getString(
                when (prefs.iconStyle) {
                    NothingPrefs.IconStyle.MONO -> R.string.nt_icon_mono
                    NothingPrefs.IconStyle.COLOR -> R.string.nt_icon_color
                    NothingPrefs.IconStyle.DOTS -> R.string.nt_icon_dots
                }
            )
        }) {
            val styles = NothingPrefs.IconStyle.entries
            val labels = listOf(
                getString(R.string.nt_icon_mono),
                getString(R.string.nt_icon_color),
                getString(R.string.nt_icon_dots)
            )
            pickChoice(R.string.nt_setting_icon_style, labels, styles.indexOf(prefs.iconStyle)) { idx ->
                NothingIconLogic.applyStyle(this, styles[idx])
            }
        }
        addChoice(lookCard, R.string.nt_setting_icon_pack, {
            val pkg = prefs.iconPackPackage
            if (pkg.isBlank()) getString(R.string.nt_icon_pack_system)
            else IconPackManager.listInstalledPacks(this).firstOrNull { it.packageName == pkg }?.label ?: pkg
        }) {
            val packs = IconPackManager.listInstalledPacks(this)
            val labels = listOf(getString(R.string.nt_icon_pack_system)) + packs.map { it.label }
            val currentIdx = if (prefs.iconPackPackage.isBlank()) 0
            else (packs.indexOfFirst { it.packageName == prefs.iconPackPackage } + 1).coerceAtLeast(0)
            pickChoice(R.string.nt_setting_icon_pack, labels, currentIdx) { idx ->
                prefs.iconPackPackage = if (idx == 0) "" else packs[idx - 1].packageName
                NothingIconLogic.refreshAll(this)
            }
        }
        addSwitch(
            lookCard,
            R.string.nt_setting_night_dim,
            R.string.nt_setting_night_dim_sub,
            { prefs.nightDimDots },
            {
                prefs.nightDimDots = it
                binding.settingsRoot.background = DotGridDrawable.forTier(this)
            }
        )
        addSwitch(
            lookCard,
            R.string.nt_setting_tactile_sound,
            null,
            { prefs.tactileSound },
            { prefs.tactileSound = it }
        )

        // 2. Gestures & Home Cleanliness
        val gestureCard = addSection(R.string.nt_section_gestures)
        addSwitch(
            gestureCard,
            R.string.nt_setting_hide_labels,
            null,
            { prefs.hideHomeLabels },
            { prefs.hideHomeLabels = it }
        )
        addSwitch(
            gestureCard,
            R.string.nt_setting_search_pill,
            null,
            { prefs.showSearchPill },
            { prefs.showSearchPill = it }
        )
        addChoice(gestureCard, R.string.nt_setting_drawer_mode, {
            getString(
                if (prefs.drawerLayoutMode == NothingPrefs.DrawerLayoutMode.LIST)
                    R.string.drawer_mode_list else R.string.drawer_mode_grid
            )
        }) {
            val modes = listOf(NothingPrefs.DrawerLayoutMode.GRID, NothingPrefs.DrawerLayoutMode.LIST)
            val labels = listOf(getString(R.string.drawer_mode_grid), getString(R.string.drawer_mode_list))
            pickChoice(R.string.nt_setting_drawer_mode, labels, modes.indexOf(prefs.drawerLayoutMode).coerceAtLeast(0)) {
                prefs.drawerLayoutMode = modes[it]
            }
        }
        addSwitch(
            gestureCard,
            R.string.nt_setting_drawer_recents,
            null,
            { prefs.showDrawerRecents },
            { prefs.showDrawerRecents = it }
        )
        addChoice(gestureCard, R.string.nt_setting_gesture_down, { gestureLabel(prefs.swipeDownAction) }) {
            pickGesture(R.string.nt_setting_gesture_down, { prefs.swipeDownAction }) { prefs.swipeDownAction = it }
        }
        addChoice(gestureCard, R.string.nt_setting_gesture_dtap, { gestureLabel(prefs.doubleTapAction) }) {
            pickGesture(R.string.nt_setting_gesture_dtap, { prefs.doubleTapAction }) { prefs.doubleTapAction = it }
        }
        addChoice(gestureCard, R.string.nt_setting_gesture_pinch, { gestureLabel(prefs.pinchAction) }) {
            pickGesture(R.string.nt_setting_gesture_pinch, { prefs.pinchAction }) { prefs.pinchAction = it }
        }

        // 3. Essential Space & Privacy
        val essentialCard = addSection(R.string.nt_section_essential)
        addSwitch(
            essentialCard,
            R.string.nt_setting_focus_mode,
            R.string.nt_setting_focus_mode_sub,
            { prefs.focusMode },
            { prefs.focusMode = it }
        )
        addChoice(essentialCard, R.string.nt_setting_world_zone, { prefs.worldClockZoneId }) {
            val zones = listOf(
                "UTC",
                "America/New_York",
                "America/Los_Angeles",
                "Europe/London",
                "Europe/Berlin",
                "Asia/Dubai",
                "Asia/Kolkata",
                "Asia/Tokyo",
                "Australia/Sydney"
            )
            pickChoice(R.string.nt_setting_world_zone, zones, zones.indexOf(prefs.worldClockZoneId)) {
                prefs.worldClockZoneId = zones[it]
            }
        }
        addChoice(essentialCard, R.string.nt_setting_habit_title, { prefs.habitTitle }) {
            val pad = (20 * d).toInt()
            val input = android.widget.EditText(this).apply {
                setText(prefs.habitTitle)
                setSelection(text?.length ?: 0)
                setHint(R.string.habit_rename_hint)
                setSingleLine(true)
            }
            val box = android.widget.FrameLayout(this).apply {
                setPadding(pad, (8 * d).toInt(), pad, (8 * d).toInt())
                addView(input)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.habit_rename_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs.habitTitle = input.text?.toString()?.trim().orEmpty()
                    v13StatusRefreshers.forEach { it() }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        addChoice(essentialCard, R.string.nt_setting_open_vault, {
            val n = prefs.hiddenPackages.size
            if (n == 0) getString(R.string.nt_setting_open_vault_sub) else "$n hidden"
        }) {
            HiddenVaultSheet.showVaultDialog(this) { v13StatusRefreshers.forEach { it() } }
        }

        // 4. Presets, Backup & Hub
        val hubCard = addSection(R.string.nt_section_setup_backup)
        addChoice(hubCard, R.string.nt_setting_open_hub, { getString(R.string.nt_setting_open_hub_sub) }) {
            startActivity(Intent(this, LauncherEntryActivity::class.java))
        }
        addChoice(hubCard, R.string.nt_presets_title, { getString(R.string.nt_presets_short) }) {
            SetupBackupManager.showPresetsSheet(this) { recreate() }
        }
        addChoice(hubCard, R.string.nt_backup_export, { "JSON" }) {
            SetupBackupManager.launchExport(this)
        }
        addChoice(hubCard, R.string.nt_backup_import, { ".nothing.json" }) {
            SetupBackupManager.launchImport(this)
        }
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
            lateinit var qualityRow: com.nothinglauncher.databinding.ItemSettingRowBinding
            qualityRow = SettingsUi.choiceRow(
                card, getString(R.string.setting_quality), qualitySubtitle(),
                listOf(getString(R.string.quality_auto), getString(R.string.quality_lite), getString(R.string.quality_full)),
                prefs.visualQuality.ordinal
            ) {
                val choice = NothingPrefs.VisualQuality.entries[it]
                prefs.visualQuality = choice
                // Choosing Auto again is the way back from a low-memory downgrade.
                if (choice == NothingPrefs.VisualQuality.AUTO) prefs.memoryPressureSeen = false
                VisualTier.invalidate()
                qualityRow.rowSubtitle.text = qualitySubtitle()
                // This screen's own backdrop follows at once; home rebuilds when shown again.
                binding.settingsRoot.background = DotGridDrawable.forTier(this)
            }
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

    private fun qualitySubtitle(): String = when (prefs.visualQuality) {
        NothingPrefs.VisualQuality.LITE -> getString(R.string.setting_quality_sub_lite)
        NothingPrefs.VisualQuality.FULL -> getString(R.string.setting_quality_sub_full)
        NothingPrefs.VisualQuality.AUTO -> if (prefs.memoryPressureSeen) {
            getString(R.string.setting_quality_sub_pressure)
        } else {
            val info = VisualTier.deviceInfo(this)
            getString(
                R.string.setting_quality_sub_auto,
                String.format(java.util.Locale.getDefault(), "%.1f", info.totalRamGb),
                info.cores,
                getString(
                    when (VisualTier.autoTier(this)) {
                        VisualTier.Tier.LITE -> R.string.quality_tier_lite
                        VisualTier.Tier.BALANCED -> R.string.quality_tier_balanced
                        VisualTier.Tier.RICH -> R.string.quality_tier_rich
                    }
                )
            )
        }
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
