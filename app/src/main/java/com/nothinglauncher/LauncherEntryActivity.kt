package com.nothinglauncher

import android.Manifest
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import com.nothinglauncher.settings.NothingSettingsActivity

/**
 * Guided setup hub & quick control room exposed as the app-drawer launcher entry (`CATEGORY_LAUNCHER`).
 * Lets users set Not a Thing as default Home, pick a v13 preset, grant optional live-widget permissions,
 * and export/import `.nothing.json` workspace backups.
 */
class LauncherEntryActivity : FragmentActivity() {

    private var appliedPresetIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_entry)

        val column = findViewById<View>(R.id.entryColumn)
        BigScreenUi.applyMaxWidth(column, 780)
        val baseTop = column.paddingTop
        val baseBottom = column.paddingBottom
        val baseLeft = column.paddingLeft
        val baseRight = column.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(column) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            column.updatePadding(
                left = baseLeft + bars.left,
                top = baseTop + bars.top,
                right = baseRight + bars.right,
                bottom = baseBottom + bars.bottom
            )
            insets
        }

        findViewById<View>(R.id.entrySetHomeBtn).apply {
            Tactile.press(this)
            setOnClickListener { promptDefaultHome() }
        }
        findViewById<View>(R.id.entryOpenSettingsBtn).apply {
            Tactile.press(this)
            setOnClickListener {
                startActivity(Intent(this@LauncherEntryActivity, NothingSettingsActivity::class.java))
            }
        }
        findViewById<View>(R.id.entryBackupBtn).apply {
            Tactile.press(this)
            setOnClickListener { SetupBackupManager.launchExport(this@LauncherEntryActivity) }
        }
        findViewById<View>(R.id.entryDeskBtn)?.apply {
            Tactile.press(this)
            setOnClickListener { DeskHudActivity.launch(this@LauncherEntryActivity) }
        }
        findViewById<View>(R.id.entryGoHomeBtn).apply {
            Tactile.press(this)
            setOnClickListener {
                NothingPrefs.get(this@LauncherEntryActivity).onboardingDone = true
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            }
        }

        buildPresets()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (SetupBackupManager.handleActivityResult(this, requestCode, resultCode, data)) return
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun refreshStatus() {
        val hero = findViewById<DotMatrixView>(R.id.entryHero)
        hero.dotColor = AccentTheme.accentColor(this)

        val isDefault = isDefaultHome()
        findViewById<TextView>(R.id.entryHomeStatus).apply {
            setText(if (isDefault) R.string.nt_entry_home_active else R.string.nt_entry_home_inactive)
            setTextColor(
                if (isDefault) AccentTheme.accentColor(this@LauncherEntryActivity)
                else getColor(R.color.text_secondary)
            )
        }
        findViewById<View>(R.id.entrySetHomeBtn).visibility =
            if (isDefault) View.GONE else View.VISIBLE

        rebuildCapabilities()
    }

    private fun isDefaultHome(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleHeld(RoleManager.ROLE_HOME)) return true
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return res?.activityInfo?.packageName == packageName
    }

    private fun promptDefaultHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1310)
                return
            }
        }
        Intents.start(this, Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun buildPresets() {
        val d = resources.displayMetrics.density
        val container = findViewById<LinearLayout>(R.id.entryPresetsContainer)
        container.removeAllViews()
        val large = BigScreenUi.isLargeScreen(this)
        val presets = SetupBackupManager.Preset.entries

        fun makePresetCard(idx: Int, preset: SetupBackupManager.Preset): LinearLayout {
            val isApplied = idx == appliedPresetIndex
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(if (isApplied) R.drawable.bg_tile_active else R.drawable.bg_tile_dark)
                val p = (14 * d).toInt()
                setPadding(p, p, p, p)
                addView(TextView(this@LauncherEntryActivity).apply {
                    setText(preset.titleRes)
                    textSize = 14f
                    setTextColor(getColor(if (isApplied) R.color.pill_active_text else R.color.text_primary))
                })
                addView(TextView(this@LauncherEntryActivity).apply {
                    setText(preset.subtitleRes)
                    textSize = 11.5f
                    setTextColor(getColor(if (isApplied) R.color.pill_active_text else R.color.text_secondary))
                    if (isApplied) alpha = 0.78f
                })
                Tactile.press(this)
                setOnClickListener {
                    Tactile.confirm(this)
                    appliedPresetIndex = idx
                    SetupBackupManager.applyPreset(this@LauncherEntryActivity, preset)
                    Toast.makeText(
                        this@LauncherEntryActivity,
                        getString(preset.titleRes) + " applied",
                        Toast.LENGTH_SHORT
                    ).show()
                    buildPresets()
                    refreshStatus()
                }
            }
        }

        if (large) {
            for (rowStart in presets.indices step 2) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = if (rowStart == 0) 0 else (8 * d).toInt() }
                }
                val first = makePresetCard(rowStart, presets[rowStart]).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(first)
                if (rowStart + 1 < presets.size) {
                    row.addView(android.widget.Space(this).apply {
                        layoutParams = LinearLayout.LayoutParams((8 * d).toInt(), 1)
                    })
                    val second = makePresetCard(rowStart + 1, presets[rowStart + 1]).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(second)
                }
                container.addView(row)
            }
        } else {
            presets.forEachIndexed { idx, preset ->
                val card = makePresetCard(idx, preset).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = if (idx == 0) 0 else (8 * d).toInt() }
                }
                container.addView(card)
            }
        }
    }

    private fun rebuildCapabilities() {
        val d = resources.displayMetrics.density
        val container = findViewById<LinearLayout>(R.id.entryCapsContainer)
        container.removeAllViews()

        data class Cap(val titleRes: Int, val subRes: Int, val granted: Boolean, val onGrant: () -> Unit)

        val caps = listOf(
            Cap(
                R.string.nt_cap_weather,
                R.string.nt_cap_weather_sub,
                hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
            ) { requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1320) },
            Cap(
                R.string.nt_cap_media,
                R.string.nt_cap_media_sub,
                MediaSessionRepository.hasNotificationAccess(this)
            ) { Intents.openNotificationAccess(this) },
            Cap(
                R.string.nt_cap_calendar,
                R.string.nt_cap_calendar_sub,
                hasPerm(Manifest.permission.READ_CALENDAR)
            ) { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1321) },
            Cap(
                R.string.nt_cap_voice,
                R.string.nt_cap_voice_sub,
                hasPerm(Manifest.permission.RECORD_AUDIO)
            ) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1322) },
            Cap(
                R.string.nt_cap_contacts,
                R.string.nt_cap_contacts_sub,
                hasPerm(Manifest.permission.READ_CONTACTS)
            ) { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1323) },
            Cap(
                R.string.nt_cap_usage,
                R.string.nt_cap_usage_sub,
                hasUsageStats()
            ) { Intents.openUsageAccess(this) },
            Cap(
                R.string.nt_cap_lock,
                R.string.nt_cap_lock_sub,
                NothingLockAccessibilityService.isEnabled(this)
            ) { Intents.openAccessibilitySettings(this) }
        )

        caps.forEachIndexed { idx, cap ->
            if (idx > 0) {
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * d).toInt().coerceAtLeast(1)
                    )
                    setBackgroundColor(getColor(R.color.hairline))
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val pv = (12 * d).toInt()
                setPadding(0, pv, 0, pv)
                addView(LinearLayout(this@LauncherEntryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@LauncherEntryActivity).apply {
                        setText(cap.titleRes)
                        textSize = 13.5f
                        setTextColor(getColor(R.color.text_primary))
                    })
                    addView(TextView(this@LauncherEntryActivity).apply {
                        setText(cap.subRes)
                        textSize = 11f
                        setTextColor(getColor(R.color.text_secondary))
                    })
                })
                addView(TextView(this@LauncherEntryActivity).apply {
                    setText(if (cap.granted) R.string.nt_status_active else R.string.nt_status_enable)
                    textSize = 11f
                    setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
                    setBackgroundResource(if (cap.granted) R.drawable.bg_tile_active else R.drawable.bg_pill)
                    setTextColor(
                        if (cap.granted) AccentTheme.accentColor(this@LauncherEntryActivity)
                        else getColor(R.color.text_primary)
                    )
                })
                setOnClickListener { if (!cap.granted) cap.onGrant() }
            }
            container.addView(row)
        }
    }

    private fun hasPerm(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasUsageStats(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as? AppOpsManager ?: return false
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
