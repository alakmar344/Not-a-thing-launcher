package com.nothinglauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.nothinglauncher.icons.NothingIconLogic
import com.nothinglauncher.security.BackupCrypto
import com.nothinglauncher.security.DeviceAuthGate
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * Backup/restore (`.nothing.json`) and curated one-tap v13 aesthetic + functional presets.
 */
object SetupBackupManager {

    const val REQ_EXPORT_BACKUP = 1301
    const val REQ_IMPORT_BACKUP = 1302

    private const val EXPORT_FILENAME = "not-a-thing-v15.6-setup.json"
    private const val OP_EXPORT = "backup_export"
    private const val OP_IMPORT = "backup_import"

    private data class PendingExportSpec(
        val includeVault: Boolean,
        val passphrase: CharArray?
    )

    internal data class BackupInspection(
        val schemaVersion: Int,
        val hasEncryptedVault: Boolean,
        val hasLegacyPlaintextHidden: Boolean
    )

    @Volatile private var activeExportToken: String? = null
    @Volatile private var activeImportToken: String? = null

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
        com.nothinglauncher.security.DeviceAuthGate.authenticate(
            context = activity,
            title = activity.getString(R.string.nt_export_lock_title),
            subtitle = activity.getString(R.string.nt_export_lock_sub),
            onSuccess = { showExportOptionsDialog(activity) },
            onUnsecured = { showExportOptionsDialog(activity) }
        )
    }

    fun launchImport(activity: Activity) {
        com.nothinglauncher.security.DeviceAuthGate.authenticate(
            context = activity,
            title = activity.getString(R.string.nt_import_lock_title),
            subtitle = activity.getString(R.string.nt_import_lock_sub),
            onSuccess = { startLockedImportPicker(activity) },
            onUnsecured = { startLockedImportPicker(activity) }
        )
    }

    fun handleActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        onRestored: () -> Unit = {}
    ): Boolean {
        if (requestCode != REQ_EXPORT_BACKUP && requestCode != REQ_IMPORT_BACKUP) return false
        if (resultCode != Activity.RESULT_OK) {
            when (requestCode) {
                REQ_EXPORT_BACKUP -> clearPendingExport()
                REQ_IMPORT_BACKUP -> clearPendingImport()
            }
            return true
        }
        val uri: Uri = data?.data ?: run {
            when (requestCode) {
                REQ_EXPORT_BACKUP -> clearPendingExport()
                REQ_IMPORT_BACKUP -> clearPendingImport()
            }
            return true
        }
        return when (requestCode) {
            REQ_EXPORT_BACKUP -> {
                val ok = handleLockedExportResult(activity, uri)
                Toast.makeText(
                    activity,
                    if (ok) R.string.nt_backup_exported else R.string.nt_backup_failed,
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
            REQ_IMPORT_BACKUP -> {
                handleLockedImportResult(activity, uri, onRestored)
                true
            }
            else -> false
        }
    }

    private fun clearPendingExport() {
        val tok = activeExportToken.also { activeExportToken = null }
        val payload = DeviceAuthGate.revokeActionGrant(tok) as? PendingExportSpec
        payload?.passphrase?.let { Arrays.fill(it, '\u0000') }
    }

    private fun clearPendingImport() {
        val tok = activeImportToken.also { activeImportToken = null }
        DeviceAuthGate.revokeActionGrant(tok)
    }

    private fun showExportOptionsDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density
        val pad = (18 * d).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (10 * d).toInt(), pad, (6 * d).toInt())
        }

        val group = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val rbSetupOnly = RadioButton(activity).apply {
            id = View.generateViewId()
            setText(R.string.nt_export_mode_setup)
            isChecked = true
        }
        val rbEncrypted = RadioButton(activity).apply {
            id = View.generateViewId()
            setText(R.string.nt_export_mode_encrypted)
        }
        group.addView(rbSetupOnly)
        group.addView(rbEncrypted)
        container.addView(group)

        container.addView(TextView(activity).apply {
            setText(R.string.nt_export_hidden_note)
            textSize = 12f
            setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        })

        val passBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, (6 * d).toInt(), 0, 0)
        }
        val etPass = EditText(activity).apply {
            setHint(R.string.nt_export_passphrase_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etConfirm = EditText(activity).apply {
            setHint(R.string.nt_export_passphrase_confirm)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val errorView = TextView(activity).apply {
            setTextColor(0xFFFF4D4F.toInt())
            textSize = 12f
            visibility = View.GONE
        }
        passBox.addView(etPass)
        passBox.addView(etConfirm)
        passBox.addView(errorView)
        container.addView(passBox)

        group.setOnCheckedChangeListener { _, checkedId ->
            passBox.visibility = if (checkedId == rbEncrypted.id) View.VISIBLE else View.GONE
            errorView.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.nt_export_dialog_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                etPass.text?.clear()
                etConfirm.text?.clear()
                clearPendingExport()
            }
            .setPositiveButton(R.string.nt_export_continue, null)
            .create()
        DeviceAuthGate.applySecureWindow(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val includeVault = group.checkedRadioButtonId == rbEncrypted.id
                val spec = if (includeVault) {
                    val p1 = etPass.text
                    val p2 = etConfirm.text
                    if (p1.length < BackupCrypto.MIN_PASSPHRASE_LEN) {
                        errorView.setText(R.string.nt_export_passphrase_short)
                        errorView.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    if (!TextUtils.equals(p1, p2)) {
                        errorView.setText(R.string.nt_export_passphrase_mismatch)
                        errorView.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    val chars = CharArray(p1.length) { p1[it] }
                    etPass.text?.clear()
                    etConfirm.text?.clear()
                    PendingExportSpec(includeVault = true, passphrase = chars)
                } else {
                    PendingExportSpec(includeVault = false, passphrase = null)
                }

                clearPendingExport()
                activeExportToken = DeviceAuthGate.mintActionGrant(OP_EXPORT, spec)
                dialog.dismiss()

                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, EXPORT_FILENAME)
                }
                try {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(intent, REQ_EXPORT_BACKUP)
                } catch (_: Exception) {
                    clearPendingExport()
                    Toast.makeText(activity, R.string.nt_cannot_open, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun startLockedImportPicker(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        clearPendingImport()
        activeImportToken = DeviceAuthGate.mintActionGrant(OP_IMPORT, null)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
        }
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ_IMPORT_BACKUP)
        } catch (_: Exception) {
            clearPendingImport()
            Toast.makeText(activity, R.string.nt_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleLockedExportResult(activity: Activity, uri: Uri): Boolean {
        val tok = activeExportToken.also { activeExportToken = null }
        val (granted, payload) = DeviceAuthGate.consumeActionGrant(tok, OP_EXPORT)
        val spec = payload as? PendingExportSpec
        if (!granted || spec == null) {
            spec?.passphrase?.let { Arrays.fill(it, '\u0000') }
            return false
        }
        val pass = spec.passphrase
        return try {
            writeBackupToUri(activity, uri, if (spec.includeVault) pass else null)
        } finally {
            if (pass != null) Arrays.fill(pass, '\u0000')
        }
    }

    private fun handleLockedImportResult(activity: Activity, uri: Uri, onRestored: () -> Unit) {
        val tok = activeImportToken.also { activeImportToken = null }
        val (granted, _) = DeviceAuthGate.consumeActionGrant(tok, OP_IMPORT)
        if (!granted) {
            Toast.makeText(activity, R.string.nt_backup_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val raw = readBoundedBackupText(activity, uri)
        val inspection = raw?.let { inspectBackup(it) }
        if (raw == null || inspection == null) {
            Toast.makeText(activity, R.string.nt_backup_failed, Toast.LENGTH_SHORT).show()
            return
        }
        showAcceptDataDialog(activity, raw, inspection, onRestored)
    }

    internal fun inspectBackup(raw: String): BackupInspection? {
        if (raw.length > MAX_BACKUP_BYTES) return null
        return try {
            val o = JSONObject(raw)
            val version = o.optInt("version", -1)
            if (version !in 1..1000) return null
            val hasEncVault = o.optJSONObject("encryptedVault") != null
            val legacyCount = o.optJSONArray("hiddenPackages")?.length() ?: 0
            BackupInspection(
                schemaVersion = version,
                hasEncryptedVault = hasEncVault,
                hasLegacyPlaintextHidden = !hasEncVault && legacyCount > 0
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun showAcceptDataDialog(
        activity: Activity,
        raw: String,
        inspection: BackupInspection,
        onRestored: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density
        val pad = (18 * d).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (10 * d).toInt(), pad, (6 * d).toInt())
        }
        val sb = StringBuilder(activity.getString(R.string.nt_import_summary_setup, inspection.schemaVersion))
        when {
            inspection.hasEncryptedVault -> sb.append(activity.getString(R.string.nt_import_summary_vault_enc))
            inspection.hasLegacyPlaintextHidden -> sb.append(activity.getString(R.string.nt_import_summary_legacy_ignored))
            else -> sb.append(activity.getString(R.string.nt_import_summary_vault_kept))
        }
        container.addView(TextView(activity).apply {
            text = sb.toString()
            textSize = 13f
        })

        var etPass: EditText? = null
        var errorView: TextView? = null
        if (inspection.hasEncryptedVault) {
            etPass = EditText(activity).apply {
                setHint(R.string.nt_import_passphrase_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }.also { container.addView(it) }
            errorView = TextView(activity).apply {
                setTextColor(0xFFFF4D4F.toInt())
                textSize = 12f
                visibility = View.GONE
            }.also { container.addView(it) }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.nt_import_confirm_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                etPass?.text?.clear()
            }
            .setPositiveButton(R.string.nt_import_confirm_accept, null)
            .create()
        DeviceAuthGate.applySecureWindow(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val prefs = NothingPrefs.get(activity)
                val status = if (inspection.hasEncryptedVault) {
                    val editable = etPass?.text
                    val pass = if (editable != null) CharArray(editable.length) { editable[it] } else CharArray(0)
                    try {
                        prefs.importBackup(raw, pass, allowLegacyPlaintextVault = false)
                    } finally {
                        Arrays.fill(pass, '\u0000')
                    }
                } else {
                    prefs.importBackup(raw, null, allowLegacyPlaintextVault = false)
                }

                when (status) {
                    NothingPrefs.ImportStatus.SUCCESS -> {
                        etPass?.text?.clear()
                        dialog.dismiss()
                        NothingIconLogic.applyStyle(activity, prefs.iconStyle)
                        Toast.makeText(activity, R.string.nt_backup_imported, Toast.LENGTH_SHORT).show()
                        onRestored()
                    }
                    NothingPrefs.ImportStatus.PASSPHRASE_REQUIRED,
                    NothingPrefs.ImportStatus.WRONG_PASSPHRASE -> {
                        errorView?.setText(R.string.nt_import_wrong_passphrase)
                        errorView?.visibility = View.VISIBLE
                    }
                    NothingPrefs.ImportStatus.INVALID_FORMAT -> {
                        etPass?.text?.clear()
                        dialog.dismiss()
                        Toast.makeText(activity, R.string.nt_backup_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    @JvmOverloads
    fun writeBackupToUri(context: Context, uri: Uri, passphrase: CharArray? = null): Boolean {
        return try {
            val json = NothingPrefs.get(context).exportToJson(passphrase)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } != null
        } catch (_: Exception) {
            false
        }
    }

    private const val MAX_BACKUP_BYTES = 512 * 1024

    internal fun readBoundedBackupText(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx) && c.getLong(idx) > MAX_BACKUP_BYTES) return null
                }
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(8192)
                val out = ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_BACKUP_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return null
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @JvmOverloads
    fun readBackupFromUri(
        context: Context,
        uri: Uri,
        passphrase: CharArray? = null,
        allowLegacyPlaintextVault: Boolean = false
    ): Boolean {
        val text = readBoundedBackupText(context, uri) ?: return false
        return NothingPrefs.get(context).importFromJson(text, passphrase, allowLegacyPlaintextVault)
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
