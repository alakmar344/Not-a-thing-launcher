package com.nothinglauncher.drawer

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.core.view.isVisible
import com.nothinglauncher.Intents
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.SettingsUi
import com.nothinglauncher.Sheets
import com.nothinglauncher.Tactile
import com.nothinglauncher.security.DeviceAuthGate
import java.lang.ref.WeakReference

/**
 * Biometric / device-credential protected Hidden Apps Vault (works across Android 8-17).
 * Fails closed whenever the device has a secure lock screen configured and authentication
 * cannot be verified.
 */
object HiddenVaultSheet {

    internal enum class AuthPath {
        ALLOW_DIRECT,
        REQUIRE_BIOMETRIC_OR_CREDENTIAL,
        REQUIRE_CREDENTIAL_FALLBACK
    }

    @VisibleForTesting
    internal fun determineAuthPath(
        hiddenCount: Int,
        isDeviceSecure: Boolean,
        sdkInt: Int
    ): AuthPath = when (DeviceAuthGate.determineAuthPath(hiddenCount, isDeviceSecure, sdkInt)) {
        DeviceAuthGate.AuthPath.DIRECT -> AuthPath.ALLOW_DIRECT
        DeviceAuthGate.AuthPath.BIOMETRIC_PROMPT -> AuthPath.REQUIRE_BIOMETRIC_OR_CREDENTIAL
        DeviceAuthGate.AuthPath.CREDENTIAL_ACTIVITY -> AuthPath.REQUIRE_CREDENTIAL_FALLBACK
    }

    fun openWithAuth(context: Context, onHiddenChanged: () -> Unit) {
        val prefs = NothingPrefs.get(context)
        val km = context.getSystemService(KeyguardManager::class.java)
        val isSecure = km?.isDeviceSecure == true
        val activity = findActivity(context)
        when (determineAuthPath(prefs.hiddenPackages.size, isSecure, Build.VERSION.SDK_INT)) {
            AuthPath.ALLOW_DIRECT -> {
                showVaultDialog(activity ?: context, onHiddenChanged)
            }
            AuthPath.REQUIRE_BIOMETRIC_OR_CREDENTIAL,
            AuthPath.REQUIRE_CREDENTIAL_FALLBACK -> {
                val targetContext = activity ?: context
                val ref = WeakReference(targetContext)
                com.nothinglauncher.security.DeviceAuthGate.authenticate(
                    context = targetContext,
                    title = targetContext.getString(R.string.vault_auth_title),
                    subtitle = targetContext.getString(R.string.vault_auth_sub),
                    onSuccess = {
                        val host = ref.get() ?: return@authenticate
                        val hostActivity = findActivity(host)
                        if (hostActivity == null || (!hostActivity.isFinishing && !hostActivity.isDestroyed)) {
                            showVaultDialog(hostActivity ?: host, onHiddenChanged)
                        }
                    }
                )
            }
        }
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
        val dialog = Sheets.create(context, root).also {
            com.nothinglauncher.security.DeviceAuthGate.applySecureWindow(it)
        }
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
                val activityInfo = try {
                    la?.getActivityList(pkg, Process.myUserHandle())?.firstOrNull()
                } catch (_: Throwable) {
                    null
                }
                val compKey = activityInfo?.componentName?.flattenToString()
                val label = (compKey?.let { prefs.customAppLabels[it] })
                    ?: activityInfo?.label?.toString()
                    ?: try {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(pkg, 0)
                        ).toString()
                    } catch (_: Throwable) {
                        pkg
                    }
                val rowBinding = SettingsUi.actionRow(
                    card,
                    label,
                    context.getString(R.string.vault_tap_actions),
                    trailingIcon = R.drawable.ic_play
                ) {
                    Tactile.tick(card)
                    dialog.dismiss()
                    Intents.openApp(context, pkg)
                }
                rowBinding.root.setOnLongClickListener {
                    Tactile.confirm(it)
                    prefs.hiddenPackages = prefs.hiddenPackages - pkg
                    onHiddenChanged()
                    populate()
                    true
                }
                // Explicit 1-tap unhide chip alongside launch so users don't have to discover long-press.
                val unhideChip = TextView(context).apply {
                    text = context.getString(R.string.vault_unhide)
                    textSize = 10f
                    setTextColor(context.getColor(R.color.text_primary))
                    setBackgroundResource(R.drawable.bg_pill_outline)
                    setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
                    isClickable = true
                    isFocusable = true
                    Tactile.press(this, 0.95f)
                    setOnClickListener {
                        Tactile.confirm(it)
                        prefs.hiddenPackages = prefs.hiddenPackages - pkg
                        onHiddenChanged()
                        populate()
                    }
                }
                val trailingParent = rowBinding.rowTrailing.parent as? android.view.ViewGroup
                val idx = trailingParent?.indexOfChild(rowBinding.rowTrailing) ?: -1
                if (trailingParent != null && idx >= 0) {
                    trailingParent.addView(
                        unhideChip,
                        idx,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = (8 * d).toInt() }
                    )
                }
            }
        }

        populate()
        Sheets.showSafely(context, dialog)
    }
}
