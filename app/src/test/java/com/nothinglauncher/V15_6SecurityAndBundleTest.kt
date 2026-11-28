package com.nothinglauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.nothinglauncher.drawer.VaultAuthActivity
import com.nothinglauncher.search.CommandEngine
import com.nothinglauncher.security.BackupCrypto
import com.nothinglauncher.security.DeviceAuthGate
import com.nothinglauncher.settings.NothingSettingsActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V15_6SecurityAndBundleTest {

    private lateinit var context: Context
    private lateinit var prefs: NothingPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = NothingPrefs.get(context)
        prefs.hiddenPackages = emptySet()
        prefs.recentComponents = emptyList()
        prefs.quickFolderApps = emptyList()
        prefs.maxAppComponent = ""
        prefs.customAppLabels = emptyMap()
    }

    @Test
    fun backupCryptoEncryptsDecryptsAndFailsClosedOnWrongPassphraseOrTampering() {
        val payload = BackupCrypto.VaultPayload(
            hiddenPackages = setOf("com.bank.secret", "com.chat.private"),
            hiddenLabels = mapOf("com.bank.secret/.Login" to "Bank Alias")
        )
        val env = BackupCrypto.encryptVault(payload, "StrongPass#2026".toCharArray())
        assertFalse(env.toString().contains("com.bank.secret"))
        assertFalse(env.toString().contains("Bank Alias"))

        val decrypted = BackupCrypto.decryptVault(env, "StrongPass#2026".toCharArray())
        assertNotNull(decrypted)
        assertEquals(payload.hiddenPackages, decrypted?.hiddenPackages)
        assertEquals(payload.hiddenLabels, decrypted?.hiddenLabels)

        assertNull(BackupCrypto.decryptVault(env, "WrongPass#2026".toCharArray()))
        assertNull(BackupCrypto.decryptVault(env, CharArray(0)))

        val tampered = JSONObject(env.toString()).apply {
            val orig = getString("ct")
            val flipped = (if (orig.first() == 'A') "B" else "A") + orig.drop(1)
            put("ct", flipped)
        }
        assertNull(BackupCrypto.decryptVault(tampered, "StrongPass#2026".toCharArray()))
    }

    @Test
    fun defaultExportStripsAllHiddenTracesAndWrongPassphraseImportIsAtomic() {
        prefs.accentPalette = AccentTheme.Palette.RED
        prefs.habitTitle = "ORIGINAL"
        prefs.hiddenPackages = setOf("com.secret.wallet")
        prefs.customAppLabels = mapOf(
            "com.secret.wallet/.Main" to "WalletX",
            "com.visible.app/.Home" to "VisibleX"
        )
        prefs.maxAppComponent = "com.secret.wallet/.Main"
        prefs.quickFolderApps = listOf("com.secret.wallet/.Main", "com.visible.app/.Home")

        val plain = prefs.exportToJson()
        assertFalse(plain.contains("com.secret.wallet"))
        assertFalse(plain.contains("WalletX"))
        assertFalse(plain.contains("\"hiddenPackages\""))
        assertTrue(plain.contains("com.visible.app"))

        val enc = prefs.exportToJson("VaultLock9!".toCharArray())
        assertFalse(enc.contains("com.secret.wallet"))
        assertFalse(enc.contains("WalletX"))

        // Change local state and try importing with wrong / missing passphrase -> nothing mutates!
        prefs.accentPalette = AccentTheme.Palette.MONO
        prefs.habitTitle = "CHANGED"
        prefs.hiddenPackages = setOf("com.local.existing")

        assertEquals(NothingPrefs.ImportStatus.PASSPHRASE_REQUIRED, prefs.importBackup(enc, null))
        assertEquals(NothingPrefs.ImportStatus.WRONG_PASSPHRASE, prefs.importBackup(enc, "bad".toCharArray()))
        assertEquals(AccentTheme.Palette.MONO, prefs.accentPalette)
        assertEquals("CHANGED", prefs.habitTitle)
        assertEquals(setOf("com.local.existing"), prefs.hiddenPackages)

        // Setup-only JSON import preserves local hidden vault untouched & rejects negative versions.
        assertEquals(
            NothingPrefs.ImportStatus.INVALID_FORMAT,
            prefs.importBackup(JSONObject().put("version", -1).toString())
        )
        val wipeAttempt = JSONObject().put("version", 15).put("hiddenPackages", JSONArray()).toString()
        assertEquals(NothingPrefs.ImportStatus.SUCCESS, prefs.importBackup(wipeAttempt))
        assertEquals(setOf("com.local.existing"), prefs.hiddenPackages)

        // Correct passphrase restores encrypted vault + hidden labels cleanly.
        assertEquals(
            NothingPrefs.ImportStatus.SUCCESS,
            prefs.importBackup(enc, "VaultLock9!".toCharArray())
        )
        assertEquals(AccentTheme.Palette.RED, prefs.accentPalette)
        assertEquals(setOf("com.secret.wallet"), prefs.hiddenPackages)
        assertEquals("WalletX", prefs.customAppLabels["com.secret.wallet/.Main"])
    }

    @Test
    fun backupActivityResultsFailClosedWithoutDeviceAuthGrant() {
        val activity = Robolectric.buildActivity(NothingSettingsActivity::class.java).setup().get()
        val targetFile = File(context.cacheDir, "forged_backup.json")
        targetFile.writeText("{}", Charsets.UTF_8)
        val uri = Uri.fromFile(targetFile)
        val resultIntent = Intent().setData(uri)

        // Forged/unauthenticated export result must NOT write a backup file.
        targetFile.delete()
        assertTrue(
            SetupBackupManager.handleActivityResult(
                activity,
                SetupBackupManager.REQ_EXPORT_BACKUP,
                Activity.RESULT_OK,
                resultIntent
            )
        )
        assertFalse(targetFile.exists())

        // Forged/unauthenticated import result must NOT alter preferences.
        prefs.accentPalette = AccentTheme.Palette.PHOSPHOR
        val maliciousJson = JSONObject().apply {
            put("version", 15)
            put("accentPalette", AccentTheme.Palette.MONO.name)
        }.toString()
        targetFile.writeText(maliciousJson, Charsets.UTF_8)
        assertTrue(
            SetupBackupManager.handleActivityResult(
                activity,
                SetupBackupManager.REQ_IMPORT_BACKUP,
                Activity.RESULT_OK,
                resultIntent
            )
        )
        assertEquals(AccentTheme.Palette.PHOSPHOR, prefs.accentPalette)

        // Action grants are single-use and operation-bound.
        val token = DeviceAuthGate.mintActionGrant("backup_import", null)
        assertFalse(DeviceAuthGate.consumeActionGrant(token, "backup_export").first)
        // Wrong-operation check rejected? Now mint fresh token and check consumption once.
        val token2 = DeviceAuthGate.mintActionGrant("backup_import", "ok")
        assertTrue(DeviceAuthGate.consumeActionGrant(token2, "backup_import").first)
        assertFalse(DeviceAuthGate.consumeActionGrant(token2, "backup_import").first)
    }

    @Test
    fun hiddenAppsPurgeRecentsIgnoreLaunchesAndAreFilteredInManifestAndSettings() {
        prefs.recentComponents = listOf("com.secret.bank/.Main", "com.android.settings/.Settings")
        prefs.quickFolderApps = listOf("com.secret.bank/.Main", "com.android.settings/.Settings")
        prefs.maxAppComponent = "com.secret.bank/.Main"

        // Hiding a package must automatically purge its traces from recents/folder/maxApp.
        prefs.hiddenPackages = setOf("com.secret.bank")
        assertEquals(listOf("com.android.settings/.Settings"), prefs.recentComponents)
        assertEquals(listOf("com.android.settings/.Settings"), prefs.quickFolderApps)
        assertEquals("", prefs.maxAppComponent)

        // Launching a hidden package must NOT add it to recents.
        prefs.recordAppLaunch("com.secret.bank/.Main")
        assertEquals(listOf("com.android.settings/.Settings"), prefs.recentComponents)

        // Manifest hardening: allowBackup must be disabled, SecondaryDisplayLauncher removed.
        val appInfo = context.applicationInfo
        assertEquals(0, appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)

        val secondaryIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_SECONDARY_HOME)
            component = ComponentName(
                context.packageName,
                "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher"
            )
        }
        assertTrue(context.packageManager.queryIntentActivities(secondaryIntent, 0).isEmpty())
    }

    @Test
    fun settingsHidesVaultCountVoiceMemoRejectsTraversalAndPasswordUsesAllClasses() {
        prefs.hiddenPackages = setOf("com.a.hidden", "com.b.hidden", "com.c.hidden")
        val activity = Robolectric.buildActivity(NothingSettingsActivity::class.java).setup().get()
        val vaultSub = activity.getString(R.string.nt_setting_open_vault_sub)
        assertTrue(vaultSub.isNotBlank())
        assertFalse(vaultSub.contains("3"))

        // Generated passwords always cover upper, lower, digit, and symbol classes cleanly.
        repeat(10) {
            val res = CommandEngine.evalUtility("pass 12")
            assertNotNull(res)
            val pw = res!!.copy
            assertEquals(12, pw.length)
            assertTrue(pw.any { it.isUpperCase() })
            assertTrue(pw.any { it.isLowerCase() })
            assertTrue(pw.any { it.isDigit() })
            assertTrue(pw.any { !it.isLetterOrDigit() })
        }

        // VoiceMemoRepository refuses to delete files outside voice_memos/.
        val outside = File(context.cacheDir, "outside_secret.m4a").apply { writeText("keep") }
        VoiceMemoRepository.deleteMemo(
            VoiceMemoRepository.Memo(outside, "outside_secret", 1L, "NOW")
        )
        assertTrue(outside.exists())
        outside.delete()

        // VaultAuthActivity finishes cleanly when started without a valid token.
        val invalid = Robolectric.buildActivity(VaultAuthActivity::class.java).setup().get()
        assertTrue(invalid.isFinishing)
        shadowOf(invalid.mainLooper).idle()
    }
}
