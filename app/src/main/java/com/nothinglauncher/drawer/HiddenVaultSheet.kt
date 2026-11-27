package com.nothinglauncher.drawer

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
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
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.view.isVisible
import com.nothinglauncher.Intents
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.SettingsUi
import com.nothinglauncher.Sheets
import com.nothinglauncher.Tactile
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Biometric / device-credential protected Hidden Apps Vault (works across Android 8–17).
 * Fails closed whenever the device has a secure lock screen configured and authentication
 * cannot be verified.
 */
object HiddenVaultSheet {

    internal enum class AuthPath {
        ALLOW_DIRECT,
        REQUIRE_BIOMETRIC_OR_CREDENTIAL,
        REQUIRE_CREDENTIAL_FALLBACK
    }

    private data class PendingAuth(
        val activityRef: WeakReference<Activity>,
        val onHiddenChanged: () -> Unit
    )

    private val tokenSeq = AtomicLong(1L)
    private val pendingAuths = ConcurrentHashMap<Long, PendingAuth>()

    @VisibleForTesting
    internal fun determineAuthPath(
        hiddenCount: Int,
        isDeviceSecure: Boolean,
        sdkInt: Int
    ): AuthPath = when {
        hiddenCount <= 0 || !isDeviceSecure -> AuthPath.ALLOW_DIRECT
        sdkInt >= Build.VERSION_CODES.Q -> AuthPath.REQUIRE_BIOMETRIC_OR_CREDENTIAL
        else -> AuthPath.REQUIRE_CREDENTIAL_FALLBACK
    }

    fun openWithAuth(context: Context, onHiddenChanged: () -> Unit) {
        val prefs = NothingPrefs.get(context)
        val km = context.getSystemService(KeyguardManager::class.java)
        val isSecure = km?.isDeviceSecure == true
        val activity = findActivity(context)
        when (determineAuthPath(prefs.hiddenPackages.size, isSecure, Build.VERSION.SDK_INT)) {
            AuthPath.ALLOW_DIRECT -> {
                showVaultDialog(activity ?: context, onHiddenChanged)
                return
            }
            AuthPath.REQUIRE_BIOMETRIC_OR_CREDENTIAL -> {
                if (activity != null && tryBiometricAuth(activity, onHiddenChanged)) {
                    return
                }
                launchCredentialFallback(activity ?: context, onHiddenChanged)
            }
            AuthPath.REQUIRE_CREDENTIAL_FALLBACK -> {
                launchCredentialFallback(activity ?: context, onHiddenChanged)
            }
        }
    }

    private fun tryBiometricAuth(activity: Activity, onHiddenChanged: () -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val builder = BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.vault_auth_title))
                .setSubtitle(activity.getString(R.string.vault_auth_sub))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            builder.build().authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            showVaultDialog(activity, onHiddenChanged)
                        }
                    }
                }
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun launchCredentialFallback(context: Context, onHiddenChanged: () -> Unit) {
        val activity = findActivity(context) ?: run {
            Toast.makeText(context, R.string.vault_auth_required, Toast.LENGTH_SHORT).show()
            return
        }
        val token = tokenSeq.getAndIncrement()
        pendingAuths[token] = PendingAuth(WeakReference(activity), onHiddenChanged)
        try {
            VaultAuthActivity.launch(activity, token)
        } catch (_: Throwable) {
            pendingAuths.remove(token)
            Toast.makeText(activity, R.string.vault_auth_required, Toast.LENGTH_SHORT).show()
        }
    }

    internal fun completeAuthToken(token: Long) {
        val pending = pendingAuths.remove(token) ?: return
        val activity = pending.activityRef.get() ?: return
        if (!activity.isFinishing && !activity.isDestroyed) {
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    showVaultDialog(activity, pending.onHiddenChanged)
                }
            }
        }
    }

    internal fun cancelAuthToken(token: Long) {
        pendingAuths.remove(token)
    }

    private tailrec fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }

    @VisibleForTesting
    internal fun showVaultDialog(context: Context, onHiddenChanged: () -> Unit) {
        val prefs = NothingPrefs.get(context)
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_vault, null)
        val dialog = Sheets.create(context, root)
        val list = root.findViewById<LinearLayout>(R.id.vaultList)
        val scroll = root.findViewById<View>(R.id.vaultScroll)
        val empty = root.findViewById<View>(R.id.vaultEmpty)
        val count = root.findViewById<TextView>(R.id.vaultCount)
        val d = context.resources.displayMetrics.density

        fun populate() {
            list.removeAllViews()
            val hidden = prefs.hiddenPackages.sorted()
            count.text = "${hidden.size} HIDDEN"
            empty.isVisible = hidden.isEmpty()
            scroll?.layoutParams = scroll?.layoutParams?.apply {
                height = if (hidden.size > 5) (340 * d).toInt()
                else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
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
