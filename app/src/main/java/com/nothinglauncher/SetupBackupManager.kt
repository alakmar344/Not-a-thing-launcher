package com.nothinglauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nothinglauncher.icons.NothingIconLogic

/**
 * Backup/restore (`.nothing.json`) and curated one-tap v13 aesthetic + functional presets.
 */
object SetupBackupManager {

    const val REQ_EXPORT_BACKUP = 1301
    const val REQ_IMPORT_BACKUP = 1302

    enum class Preset(
        val id: String,
        val titleRes: Int,
        val subtitleRes: Int
    ) {
        TELEMETRY("telemetry", R.string.nt_preset_telemetry_v14, R.string.nt_preset_telemetry_sub),
        MONOLITH("monolith", R.string.nt_preset_monolith, R.string.nt_preset_monolith_sub),
        COMMAND("command", R.string.nt_preset_command, R.string.nt_preset_command_sub),
        STUDIO("studio", R.string.nt_preset_studio, R.string.nt_preset_studio_sub)
    }

    fun applyPreset(context: Context, preset: Preset) {
        val p = NothingPrefs.get(context)
        when (preset) {
            Preset.TELEMETRY -> {
                p.background = NothingPrefs.Background.DOTS
                p.accentPalette = AccentTheme.Palette.RED
                p.showSeconds = true
                p.hideHomeLabels = false
                p.showSearchPill = true
                p.drawerLayoutMode = NothingPrefs.DrawerLayoutMode.GRID
                p.showDrawerRecents = true
                p.tactileSound = true
                p.nightDimDots = false
                p.focusMode = false
                NothingIconLogic.applyStyle(context, NothingPrefs.IconStyle.MONO)
            }
            Preset.MONOLITH -> {
                p.background = NothingPrefs.Background.BLACK
                p.accentPalette = AccentTheme.Palette.MONO
                p.showSeconds = false
                p.hideHomeLabels = true
                p.showSearchPill = false
                p.drawerLayoutMode = NothingPrefs.DrawerLayoutMode.LIST
                p.showDrawerRecents = false
                p.tactileSound = false
                p.nightDimDots = true
                p.focusMode = true
                NothingIconLogic.applyStyle(context, NothingPrefs.IconStyle.DOTS)
            }
            Preset.COMMAND -> {
                p.background = NothingPrefs.Background.DOTS
                p.accentPalette = AccentTheme.Palette.PHOSPHOR
                p.showSeconds = true
                p.hideHomeLabels = false
                p.showSearchPill = true
                p.drawerLayoutMode = NothingPrefs.DrawerLayoutMode.LIST
                p.showDrawerRecents = true
                p.tactileSound = true
                p.nightDimDots = false
                p.focusMode = false
                NothingIconLogic.applyStyle(context, NothingPrefs.IconStyle.MONO)
            }
            Preset.STUDIO -> {
                p.background = NothingPrefs.Background.WALLPAPER
                p.accentPalette = AccentTheme.Palette.AMBER
                p.showSeconds = true
                p.hideHomeLabels = false
                p.showSearchPill = true
                p.drawerLayoutMode = NothingPrefs.DrawerLayoutMode.GRID
                p.showDrawerRecents = true
                p.tactileSound = true
                p.nightDimDots = false
                p.focusMode = false
                NothingIconLogic.applyStyle(context, NothingPrefs.IconStyle.COLOR)
            }
        }
    }

    fun launchExport(activity: Activity) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "not-a-thing-v14_1-setup.json")
        }
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ_EXPORT_BACKUP)
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.nt_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    fun launchImport(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
        }
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ_IMPORT_BACKUP)
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.nt_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    fun handleActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        onRestored: () -> Unit = {}
    ): Boolean {
        if (requestCode != REQ_EXPORT_BACKUP && requestCode != REQ_IMPORT_BACKUP) return false
        if (resultCode != Activity.RESULT_OK) return true
        val uri: Uri = data?.data ?: return true
        return when (requestCode) {
            REQ_EXPORT_BACKUP -> {
                val ok = writeBackupToUri(activity, uri)
                Toast.makeText(
                    activity,
                    if (ok) R.string.nt_backup_exported else R.string.nt_backup_failed,
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
            REQ_IMPORT_BACKUP -> {
                val ok = readBackupFromUri(activity, uri)
                Toast.makeText(
                    activity,
                    if (ok) R.string.nt_backup_imported else R.string.nt_backup_failed,
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) {
                    NothingIconLogic.applyStyle(activity, NothingPrefs.get(activity).iconStyle)
                    onRestored()
                }
                true
            }
            else -> false
        }
    }

    fun writeBackupToUri(context: Context, uri: Uri): Boolean {
        return try {
            val json = NothingPrefs.get(context).exportToJson()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } != null
        } catch (_: Exception) {
            false
        }
    }

    private const val MAX_BACKUP_BYTES = 512 * 1024

    fun readBackupFromUri(context: Context, uri: Uri): Boolean {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_BACKUP_BYTES) return false
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return false
            val text = String(bytes, Charsets.UTF_8)
            NothingPrefs.get(context).importFromJson(text)
        } catch (_: Exception) {
            false
        }
    }

    fun showPresetsSheet(activity: Activity, onApplied: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * d).toInt(), pad, pad)
        }
        val title = DotMatrixView(activity).apply {
            text = activity.getString(R.string.nt_presets_title)
            dotColor = activity.getColor(R.color.text_primary)
            dotScale = 0.40f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (20 * d).toInt()
            )
        }
        container.addView(title)
        val sheet = Sheets.create(activity, container)
        Preset.entries.forEach { preset ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_tile_dark)
                setPadding(pad, (12 * d).toInt(), pad, (12 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * d).toInt() }
                addView(TextView(activity).apply {
                    setText(preset.titleRes)
                    textSize = 14f
                    setTextColor(activity.getColor(R.color.text_primary))
                })
                addView(TextView(activity).apply {
                    setText(preset.subtitleRes)
                    textSize = 11.5f
                    setTextColor(activity.getColor(R.color.text_secondary))
                })
                Tactile.press(this)
                setOnClickListener {
                    Tactile.confirm(this)
                    applyPreset(activity, preset)
                    sheet.dismiss()
                    onApplied()
                }
            }
            container.addView(card)
        }
        Sheets.showSafely(activity, sheet)
    }
}
