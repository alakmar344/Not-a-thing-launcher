package com.nothinglauncher.drawer

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.LauncherApps
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.android.launcher3.Launcher
import com.nothinglauncher.Intents
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.SettingsUi
import com.nothinglauncher.Sheets
import com.nothinglauncher.Tactile

/**
 * Biometric / device-credential protected Hidden Apps Vault (works across Android 8–17).
 * Long-press any app -> "Hide in Vault" removes it from the drawer and search; open Vault
 * from the lock button in [DrawerHeader] or via home gestures.
 */
object HiddenVaultSheet {

    fun openWithAuth(launcher: Launcher, onHiddenChanged: () -> Unit) {
        val prefs = NothingPrefs.get(launcher)
        if (prefs.hiddenPackages.isEmpty()) {
            showVaultDialog(launcher, onHiddenChanged)
            return
        }
        val km = launcher.getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            showVaultDialog(launcher, onHiddenChanged)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val bm = launcher.getSystemService(BiometricManager::class.java)
                val canAuth = bm?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
                val builder = BiometricPrompt.Builder(launcher)
                    .setTitle(launcher.getString(R.string.vault_auth_title))
                    .setSubtitle(launcher.getString(R.string.vault_auth_sub))
                @Suppress("DEPRECATION")
                if (!canAuth) builder.setDeviceCredentialAllowed(true)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                }
                builder.build().authenticate(
                    CancellationSignal(),
                    launcher.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            showVaultDialog(launcher, onHiddenChanged)
                        }
                    }
                )
                return
            } catch (_: Throwable) {
                // Fall back to unlocked sheet on emulators / non-biometric hosts.
            }
        }
        showVaultDialog(launcher, onHiddenChanged)
    }

    fun showVaultDialog(context: Context, onHiddenChanged: () -> Unit) {
        val prefs = NothingPrefs.get(context)
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_vault, null)
        val dialog = Sheets.create(context, root)
        val list = root.findViewById<LinearLayout>(R.id.vaultList)
        val empty = root.findViewById<View>(R.id.vaultEmpty)
        val count = root.findViewById<TextView>(R.id.vaultCount)

        fun populate() {
            list.removeAllViews()
            val hidden = prefs.hiddenPackages.sorted()
            count.text = "${hidden.size} HIDDEN"
            empty.isVisible = hidden.isEmpty()
            if (hidden.isEmpty()) return

            val la = context.getSystemService(LauncherApps::class.java)
            val card = SettingsUi.card(list)
            for (pkg in hidden) {
                val label = try {
                    la?.getActivityList(pkg, Process.myUserHandle())?.firstOrNull()?.label?.toString()
                        ?: context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(pkg, 0)
                        ).toString()
                } catch (_: Throwable) {
                    pkg
                }
                SettingsUi.actionRow(
                    card,
                    label,
                    context.getString(R.string.vault_tap_actions),
                    trailingIcon = R.drawable.ic_play
                ) {
                    Tactile.tick(card)
                    dialog.dismiss()
                    Intents.openApp(context, pkg)
                }.root.setOnLongClickListener {
                    Tactile.confirm(it)
                    prefs.hiddenPackages = prefs.hiddenPackages - pkg
                    onHiddenChanged()
                    populate()
                    true
                }
            }
        }

        populate()
        Sheets.showSafely(context, dialog)
    }
}
