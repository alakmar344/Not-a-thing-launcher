package com.nothinglauncher.settings

import android.app.Dialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherFiles
import com.android.launcher3.LauncherPrefs
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.BigScreenUi
import com.nothinglauncher.DeskHudActivity
import com.nothinglauncher.DotGridDrawable
import com.nothinglauncher.DotMatrixClockView
import com.nothinglauncher.DotMatrixView
import com.nothinglauncher.Intents
import com.nothinglauncher.LauncherEntryActivity
import com.nothinglauncher.MediaSessionRepository
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.SecondsTickerView
import com.nothinglauncher.SettingsUi
import com.nothinglauncher.SetupBackupManager
import com.nothinglauncher.Sheets
import com.nothinglauncher.Tactile
import com.nothinglauncher.WeatherRepository
import com.nothinglauncher.databinding.ActivitySettingsBinding
import com.nothinglauncher.databinding.ItemSettingRowBinding
import com.nothinglauncher.drawer.HiddenVaultSheet
import com.nothinglauncher.home.CustomizeSheet
import com.nothinglauncher.icons.IconPackManager
import com.nothinglauncher.icons.NothingIconLogic
import com.nothinglauncher.perf.VisualTier

/**
 * Unified Not a Thing settings screen:
 * - Phone: single clean vertical column with NothingSwitch & SettingsUi cards.
 * - Tablets / unfolded foldables (w680dp+): centered 2-column Bento studio with a live
 *   telemetry & Desk HUD preview card.
 */
class NothingSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { NothingPrefs.get(this) }
    private var dialog: Dialog? = null
    private val statusRefreshers = mutableListOf<() -> Unit>()

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
        val column = binding.settingsColumn
        BigScreenUi.applyMaxWidth(column, 1040)
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

        buildUnifiedSettings()
        if (savedInstanceState == null && VisualTier.effects(this).entranceMotion &&
            VisualTier.motionAllowed(this)
        ) {
            SettingsUi.playEntrance(binding.settingsList)
            if (binding.settingsListRight.isVisible) SettingsUi.playEntrance(binding.settingsListRight)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (SetupBackupManager.handleActivityResult(this, requestCode, resultCode, data) { recreate() }) return
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun refreshAll() {
        statusRefreshers.forEach { it() }
    }

    private fun buildUnifiedSettings() {
        val leftList = binding.settingsList
        val rightList = binding.settingsListRight
        leftList.removeAllViews()
        rightList.removeAllViews()
        statusRefreshers.clear()

        val twoColumns = BigScreenUi.isLargeScreen(this)
        binding.settingsColGap.isVisible = twoColumns
        rightList.isVisible = twoColumns
        val secondaryCol = if (twoColumns) rightList else leftList

        fun accent() = AccentTheme.accentColor(this)

        fun pickChoice(titleRes: Int, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
            dialog?.dismiss()
            dialog = android.app.AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(labels.toTypedArray(), selected) { dlg, which ->
                    dlg.dismiss()
                    onPick(which)
                    refreshAll()
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
                NothingPrefs.GestureAction.DESK_HUD -> R.string.nt_gesture_desk
                NothingPrefs.GestureAction.LOCK_SCREEN -> R.string.nt_gesture_lock
                NothingPrefs.GestureAction.CUSTOMIZE -> R.string.nt_gesture_customize
                NothingPrefs.GestureAction.VAULT -> R.string.nt_gesture_vault
            }
        )

        fun pickGesture(titleRes: Int, current: () -> NothingPrefs.GestureAction, assign: (NothingPrefs.GestureAction) -> Unit) {
            val all = NothingPrefs.GestureAction.entries
            pickChoice(titleRes, all.map(::gestureLabel), all.indexOf(current()).coerceAtLeast(0)) { assign(all[it]) }
        }

        fun dynamicRow(
            card: LinearLayout,
            title: String,
            trailingIcon: Int = R.drawable.ic_chevron_right,
            subProvider: () -> String,
            onClick: () -> Unit
        ): ItemSettingRowBinding {
            val b = SettingsUi.actionRow(card, title, subProvider(), trailingIcon = trailingIcon) {
                Tactile.tick(card)
                onClick()
            }
            statusRefreshers += {
                b.rowSubtitle.text = subProvider()
                b.rowSubtitle.isVisible = true
            }
            return b
        }

        // --- Live Bento Studio Hero (Right Column top on Tablets / Foldables) ---
        if (twoColumns) {
            addStudioHeroCard(rightList)
        }

        // 1. HOME
        SettingsUi.section(leftList, getString(R.string.section_home), accent())
        SettingsUi.card(leftList).also { card ->
            val grids = listOf("3_by_3", "4_by_4", "5_by_5")
            val currentGridName = InvariantDeviceProfile.getCurrentGridName(this)
            val current = grids.indexOf(currentGridName)
            if (current >= 0) {
                SettingsUi.choiceRow(
                    card, getString(R.string.nt_setting_grid), getString(R.string.nt_setting_grid_sub),
                    listOf("3 × 3", "4 × 4", "5 × 5"), current
                ) { InvariantDeviceProfile.INSTANCE.get(this).setCurrentGrid(this, grids[it]) }
            } else {
                SettingsUi.infoRow(
                    card,
                    getString(R.string.nt_setting_big_bento),
                    "${currentGridName.replace("_by_", " × ")} · ${getString(R.string.nt_setting_big_bento_sub)}"
                )
            }
            SettingsUi.switchRow(
                card, getString(R.string.setting_seconds), getString(R.string.setting_seconds_sub),
                prefs.showSeconds
            ) {
                prefs.showSeconds = it
                refreshAll()
            }
            SettingsUi.switchRow(
                card, getString(R.string.nt_setting_add_new), getString(R.string.nt_setting_add_new_sub),
                launcherPrefs.getBoolean(KEY_ADD_ICON, true)
            ) { launcherPrefs.edit().putBoolean(KEY_ADD_ICON, it).apply() }
            SettingsUi.switchRow(
                card, getString(R.string.nt_setting_rotation), getString(R.string.nt_setting_rotation_sub),
                LauncherPrefs.get(this).get(LauncherPrefs.ALLOW_ROTATION)
            ) { LauncherPrefs.get(this).put(LauncherPrefs.ALLOW_ROTATION.to(it)) }
        }

        // 2. LOOK & PHYSICS (Unified — single Icon aesthetic control, zero duplication)
        SettingsUi.section(leftList, getString(R.string.nt_section_personalize), accent())
        SettingsUi.card(leftList).also { card ->
            dynamicRow(card, getString(R.string.nt_setting_accent), subProvider = {
                getString(prefs.accentPalette.labelRes)
            }) {
                val entries = AccentTheme.Palette.entries
                pickChoice(
                    R.string.nt_setting_accent,
                    entries.map { getString(it.labelRes) },
                    entries.indexOf(prefs.accentPalette)
                ) { idx ->
                    prefs.accentPalette = entries[idx]
                    binding.settingsRoot.background = DotGridDrawable.forTier(this)
                    recreate()
                }
            }

            val iconStyles = NothingPrefs.IconStyle.entries
            SettingsUi.choiceRow(
                card,
                getString(R.string.nt_setting_icon_style),
                getString(R.string.setting_icons_sub),
                listOf(
                    getString(R.string.icons_mono),
                    getString(R.string.icons_color),
                    getString(R.string.icons_dots)
                ),
                iconStyles.indexOf(prefs.iconStyle).coerceAtLeast(0)
            ) { idx ->
                NothingIconLogic.applyStyle(this, iconStyles[idx])
            }

            dynamicRow(card, getString(R.string.nt_setting_icon_pack), subProvider = {
                val pkg = prefs.iconPackPackage
                if (pkg.isBlank()) getString(R.string.nt_icon_pack_system)
                else IconPackManager.listInstalledPacks(this).firstOrNull { it.packageName == pkg }?.label ?: pkg
            }) {
                val packs = IconPackManager.listInstalledPacks(this)
                val labels = listOf(getString(R.string.nt_icon_pack_system)) + packs.map { it.label }
                val idx = if (prefs.iconPackPackage.isBlank()) 0
                else (packs.indexOfFirst { it.packageName == prefs.iconPackPackage } + 1).coerceAtLeast(0)
                pickChoice(R.string.nt_setting_icon_pack, labels, idx) { picked ->
                    prefs.iconPackPackage = if (picked == 0) "" else packs[picked - 1].packageName
                    NothingIconLogic.refreshAll(this)
                }
            }

            SettingsUi.choiceRow(
                card, getString(R.string.setting_background), getString(R.string.setting_background_sub),
                listOf(getString(R.string.bg_dots), getString(R.string.bg_black), getString(R.string.bg_wallpaper)),
                prefs.background.ordinal
            ) { prefs.background = NothingPrefs.Background.entries[it] }

            lateinit var qualityRow: ItemSettingRowBinding
            qualityRow = SettingsUi.choiceRow(
                card, getString(R.string.setting_quality), qualitySubtitle(),
                listOf(getString(R.string.quality_auto), getString(R.string.quality_lite), getString(R.string.quality_full)),
                prefs.visualQuality.ordinal
            ) {
                val choice = NothingPrefs.VisualQuality.entries[it]
                prefs.visualQuality = choice
                if (choice == NothingPrefs.VisualQuality.AUTO) prefs.memoryPressureSeen = false
                VisualTier.invalidate()
                qualityRow.rowSubtitle.text = qualitySubtitle()
                binding.settingsRoot.background = DotGridDrawable.forTier(this)
            }

            SettingsUi.actionRow(
                card,
                getString(R.string.nt_setting_widget_surfaces),
                getString(R.string.nt_setting_widget_surfaces_sub)
            ) {
                CustomizeSheet.showWidgetSurfacesSheet(this)
            }

            SettingsUi.switchRow(
                card, getString(R.string.nt_setting_night_dim), getString(R.string.nt_setting_night_dim_sub),
                prefs.nightDimDots
            ) {
                prefs.nightDimDots = it
                binding.settingsRoot.background = DotGridDrawable.forTier(this)
            }

            SettingsUi.switchRow(
                card, getString(R.string.nt_setting_tactile_sound), null,
                prefs.tactileSound
            ) { prefs.tactileSound = it }

            SettingsUi.actionRow(card, getString(R.string.nt_choose_wallpaper), null, trailingIcon = R.drawable.ic_wallpaper) {
                Intents.openWallpaperPicker(this)
            }
        }

        // 3. GESTURES & HOME CLEANLINESS
        SettingsUi.section(leftList, getString(R.string.nt_section_gestures), accent())
        SettingsUi.card(leftList).also { card ->
            SettingsUi.switchRow(card, getString(R.string.nt_setting_hide_labels), null, prefs.hideHomeLabels) {
                prefs.hideHomeLabels = it
            }
            SettingsUi.switchRow(card, getString(R.string.nt_setting_search_pill), null, prefs.showSearchPill) {
                prefs.showSearchPill = it
            }
            val modes = listOf(
                NothingPrefs.DrawerLayoutMode.GRID,
                NothingPrefs.DrawerLayoutMode.LIST,
                NothingPrefs.DrawerLayoutMode.CATEGORIES
            )
            SettingsUi.choiceRow(
                card, getString(R.string.nt_setting_drawer_mode), null,
                listOf(
                    getString(R.string.drawer_mode_grid),
                    getString(R.string.drawer_mode_list),
                    getString(R.string.drawer_mode_cats)
                ),
                modes.indexOf(prefs.drawerLayoutMode).coerceAtLeast(0)
            ) { prefs.drawerLayoutMode = modes[it] }

            SettingsUi.switchRow(card, getString(R.string.nt_setting_drawer_recents), null, prefs.showDrawerRecents) {
                prefs.showDrawerRecents = it
            }
            dynamicRow(card, getString(R.string.nt_setting_gesture_down), subProvider = { gestureLabel(prefs.swipeDownAction) }) {
                pickGesture(R.string.nt_setting_gesture_down, { prefs.swipeDownAction }) { prefs.swipeDownAction = it }
            }
            dynamicRow(card, getString(R.string.nt_setting_gesture_dtap), subProvider = { gestureLabel(prefs.doubleTapAction) }) {
                pickGesture(R.string.nt_setting_gesture_dtap, { prefs.doubleTapAction }) { prefs.doubleTapAction = it }
            }
            dynamicRow(card, getString(R.string.nt_setting_gesture_pinch), subProvider = { gestureLabel(prefs.pinchAction) }) {
                pickGesture(R.string.nt_setting_gesture_pinch, { prefs.pinchAction }) { prefs.pinchAction = it }
            }
        }

        // 4. ESSENTIAL SPACE, DESK HUD & PRIVACY
        SettingsUi.section(secondaryCol, getString(R.string.nt_section_essential), accent())
        SettingsUi.card(secondaryCol).also { card ->
            SettingsUi.actionRow(card, getString(R.string.nt_setting_desk_hud), getString(R.string.nt_setting_desk_hud_sub)) {
                DeskHudActivity.launch(this)
            }
            SettingsUi.switchRow(card, getString(R.string.nt_setting_focus_mode), getString(R.string.nt_setting_focus_mode_sub), prefs.focusMode) {
                prefs.focusMode = it
                binding.settingsRoot.background = DotGridDrawable.forTier(this)
            }
            SettingsUi.switchRow(card, getString(R.string.nt_desk_keep_awake), getString(R.string.nt_desk_subtitle), prefs.deskKeepAwake) {
                prefs.deskKeepAwake = it
            }
            dynamicRow(card, getString(R.string.nt_setting_world_zone), subProvider = { prefs.worldClockZoneId }) {
                val zones = listOf(
                    "UTC", "America/New_York", "America/Los_Angeles", "Europe/London",
                    "Europe/Berlin", "Asia/Dubai", "Asia/Kolkata", "Asia/Tokyo", "Australia/Sydney"
                )
                pickChoice(R.string.nt_setting_world_zone, zones, zones.indexOf(prefs.worldClockZoneId)) {
                    prefs.worldClockZoneId = zones[it]
                }
            }
            dynamicRow(card, getString(R.string.nt_setting_habit_title), subProvider = { prefs.habitTitle }) {
                Sheets.showTextInput(
                    this,
                    getString(R.string.habit_rename_title),
                    getString(R.string.habit_rename_hint),
                    prefs.habitTitle,
                    maxLength = 20
                ) { updated ->
                    if (updated.isNotBlank()) prefs.habitTitle = updated.uppercase()
                    refreshAll()
                }
            }
            dynamicRow(card, getString(R.string.nt_setting_open_vault), subProvider = {
                val n = prefs.hiddenPackages.size
                if (n == 0) getString(R.string.nt_setting_open_vault_sub) else "$n hidden"
            }) {
                HiddenVaultSheet.showVaultDialog(this) { refreshAll() }
            }
        }

        // 5. PERMISSIONS & CAPABILITIES
        SettingsUi.section(secondaryCol, getString(R.string.setting_capabilities), accent())
        SettingsUi.card(secondaryCol).also { card ->
            dynamicRow(card, getString(R.string.setting_notification_access), trailingIcon = R.drawable.ic_play, subProvider = {
                getString(
                    if (MediaSessionRepository.hasNotificationAccess(this)) R.string.setting_notification_on
                    else R.string.setting_notification_off
                )
            }) { Intents.openNotificationAccess(this) }

            dynamicRow(card, getString(R.string.setting_weather_city), trailingIcon = R.drawable.ic_globe, subProvider = {
                val city = prefs.weatherCity.trim()
                if (city.isEmpty()) getString(R.string.setting_weather_auto)
                else getString(R.string.setting_weather_manual, city)
            }) { showWeatherCityEditor() }
        }

        // 6. PRESETS, BACKUP & HUB
        SettingsUi.section(secondaryCol, getString(R.string.nt_section_setup_backup), accent())
        SettingsUi.card(secondaryCol).also { card ->
            SettingsUi.actionRow(card, getString(R.string.nt_setting_open_hub), getString(R.string.nt_setting_open_hub_sub)) {
                startActivity(Intent(this, LauncherEntryActivity::class.java))
            }
            SettingsUi.actionRow(card, getString(R.string.nt_presets_title), getString(R.string.nt_presets_short)) {
                SetupBackupManager.showPresetsSheet(this) { recreate() }
            }
            SettingsUi.actionRow(card, getString(R.string.nt_backup_export), "JSON", trailingIcon = R.drawable.ic_backup) {
                SetupBackupManager.launchExport(this)
            }
            SettingsUi.actionRow(card, getString(R.string.nt_backup_import), ".nothing.json", trailingIcon = R.drawable.ic_backup) {
                SetupBackupManager.launchImport(this)
            }
        }

        // 7. SYSTEM
        SettingsUi.section(secondaryCol, getString(R.string.section_system), accent())
        SettingsUi.card(secondaryCol).also { card ->
            dynamicRow(card, getString(R.string.setting_default), trailingIcon = R.drawable.ic_home, subProvider = {
                getString(if (isDefaultHome()) R.string.setting_default_on else R.string.setting_default_off)
            }) { requestDefaultHome() }

            SettingsUi.actionRow(card, getString(R.string.nt_setting_advanced), getString(R.string.nt_setting_advanced_sub)) {
                Intents.start(this, Intent(this, com.android.launcher3.settings.SettingsActivity::class.java))
            }
            SettingsUi.infoRow(card, getString(R.string.setting_about), getString(R.string.about_body, versionName().ifEmpty { "14" }))
        }
        refreshAll()
    }

    private fun addStudioHeroCard(parent: LinearLayout) {
        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (20 * d).toInt() }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val titleMatrix = DotMatrixView(this).apply {
            text = "STUDIO 14"
            dotColor = getColor(R.color.dot_on)
            accentIndices = setOf(7, 8)
            dotScale = 0.40f
            layoutParams = LinearLayout.LayoutParams(0, (20 * d).toInt(), 1f)
        }
        val palettePill = TextView(this).apply {
            textSize = 9.5f
            isAllCaps = true
            letterSpacing = 0.08f
            setTextColor(getColor(R.color.text_primary))
            setBackgroundResource(R.drawable.bg_pill_outline)
            gravity = android.view.Gravity.CENTER
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
        }
        headerRow.addView(titleMatrix)
        headerRow.addView(palettePill)
        card.addView(headerRow)

        val previewTile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_tile_dark)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * d).toInt() }
        }
        val liveClock = DotMatrixClockView(this).apply {
            previewTime = 10 to 9
            dotScale = 0.41f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (68 * d).toInt())
        }
        val liveSeconds = SecondsTickerView(this).apply {
            previewSecond = 37
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (7 * d).toInt()
            ).apply { topMargin = (6 * d).toInt() }
        }
        previewTile.addView(liveClock)
        previewTile.addView(liveSeconds)
        card.addView(previewTile)

        card.addView(TextView(this).apply {
            setText(R.string.nt_setting_big_bento_sub)
            textSize = 12.5f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, (10 * d).toInt(), 0, (12 * d).toInt())
        })

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val enterDesk = TextView(this).apply {
            setText(R.string.nt_desk_launch)
            textSize = 10.5f
            setTextColor(getColor(R.color.pill_active_text))
            setBackgroundResource(R.drawable.bg_pill_white)
            gravity = android.view.Gravity.CENTER
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            Tactile.press(this)
            setOnClickListener {
                Tactile.confirm(it)
                DeskHudActivity.launch(this@NothingSettingsActivity)
            }
        }
        val surfacesBtn = TextView(this).apply {
            setText(R.string.nt_setting_widget_surfaces)
            textSize = 10.5f
            isAllCaps = true
            setTextColor(getColor(R.color.text_primary))
            setBackgroundResource(R.drawable.bg_pill_outline)
            gravity = android.view.Gravity.CENTER
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * d).toInt() }
            Tactile.press(this)
            setOnClickListener {
                Tactile.tick(it)
                CustomizeSheet.showWidgetSurfacesSheet(this@NothingSettingsActivity)
            }
        }
        actionsRow.addView(enterDesk)
        actionsRow.addView(surfacesBtn)
        card.addView(actionsRow)

        fun syncPreview() {
            val ac = AccentTheme.accentColor(this@NothingSettingsActivity)
            titleMatrix.accentColor = ac
            liveClock.accentColor = ac
            liveClock.glow = VisualTier.effects(this@NothingSettingsActivity).dotGlow
            liveSeconds.accentColor = ac
            liveSeconds.isVisible = prefs.showSeconds
            palettePill.text = getString(prefs.accentPalette.labelRes)
        }
        syncPreview()
        statusRefreshers += ::syncPreview
        parent.addView(card)
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
            refreshAll()
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
