package com.nothinglauncher.security

import android.app.Activity
import android.app.Dialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import android.widget.Toast
import com.nothinglauncher.R
import com.nothinglauncher.drawer.VaultAuthActivity
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared fail-closed device/biometric authentication gate and single-use session
 * token authority for sensitive surfaces (Hidden Vault, Data Export, Data Import).
 */
object DeviceAuthGate {

    enum class AuthPath {
        DIRECT,
        BIOMETRIC_PROMPT,
        CREDENTIAL_ACTIVITY
    }

    private data class PendingAuth(
        val onSuccess: () -> Unit,
        val onFailure: (() -> Unit)?,
        val expiresAtMs: Long
    )

    private data class ActionGrant(
        val operation: String,
        val payload: Any?,
        val expiresAtMs: Long
    )

    private const val PENDING_AUTH_TTL_MS = 180_000L
    private const val ACTION_GRANT_TTL_MS = 120_000L

    private val random = SecureRandom()
    private val pendingAuths = ConcurrentHashMap<String, PendingAuth>()
    private val actionGrants = ConcurrentHashMap<String, ActionGrant>()

    /**
     * Pure decision helper mapping lock state + SDK level to the authentication path.
     */
    @JvmStatic
    fun determineAuthPath(
        itemCount: Int,
        isDeviceSecure: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): AuthPath {
        if (itemCount <= 0 || !isDeviceSecure) return AuthPath.DIRECT
        return if (sdkInt >= Build.VERSION_CODES.Q) {
            AuthPath.BIOMETRIC_PROMPT
        } else {
            AuthPath.CREDENTIAL_ACTIVITY
        }
    }

    /** Returns whether the device currently has a PIN, pattern, password, or biometric lock. */
    fun isDeviceSecure(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure == true
    }

    /** Prevents screenshots and recent-apps task thumbnails on sensitive windows. */
    fun applySecureWindow(activity: Activity?) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Prevents screenshots and overlays on sensitive dialogs / sheets. */
    fun applySecureWindow(dialog: Dialog?) {
        dialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /**
     * Authenticates the user with BiometricPrompt (API 29+) or confirm-device-credential
     * (API 26-28, plus fallback on OEM biometric errors).
     *
     * When [requireAuthWhenSecured] is true and `isDeviceSecure` is true, auth is strictly
     * enforced regardless of whether any items are stored yet. If `isDeviceSecure` is false,
     * [onUnsecured] is invoked instead (callers must still enforce explicit confirmation or
     * passphrase lock).
     */
    fun authenticate(
        context: Context,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (() -> Unit)? = null,
        onUnsecured: (() -> Unit)? = onSuccess
    ) {
        if (!isDeviceSecure(context)) {
            onUnsecured?.invoke()
            return
        }
        val path = determineAuthPath(itemCount = 1, isDeviceSecure = true)
        when (path) {
            AuthPath.DIRECT -> onUnsecured?.invoke()
            AuthPath.BIOMETRIC_PROMPT -> tryBiometricAuth(context, title, subtitle, onSuccess, onFailure)
            AuthPath.CREDENTIAL_ACTIVITY -> launchCredentialFallback(context, title, subtitle, onSuccess, onFailure)
        }
    }

    /** Mints a single-use, short-lived, cryptographically random authorization token. */
    fun mintActionGrant(operation: String, payload: Any? = null): String {
        purgeExpiredGrants()
        val token = generateToken()
        actionGrants[token] = ActionGrant(
            operation = operation,
            payload = payload,
            expiresAtMs = nowMs() + ACTION_GRANT_TTL_MS
        )
        return token
    }

    /** Atomically consumes and validates a single-use authorization token for [expectedOperation]. */
    fun consumeActionGrant(token: String?, expectedOperation: String): Pair<Boolean, Any?> {
        purgeExpiredGrants()
        if (token.isNullOrBlank()) return false to null
        val grant = actionGrants.remove(token) ?: return false to null
        if (grant.operation != expectedOperation || nowMs() > grant.expiresAtMs) {
            return false to null
        }
        return true to grant.payload
    }

    /** Discards an unused grant (for example when a file picker is canceled). */
    fun revokeActionGrant(token: String?): Any? {
        if (token.isNullOrBlank()) return null
        return actionGrants.remove(token)?.payload
    }

    private fun tryBiometricAuth(
        context: Context,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (() -> Unit)?
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            launchCredentialFallback(context, title, subtitle, onSuccess, onFailure)
            return
        }
        try {
            val builder = BiometricPrompt.Builder(context)
                .setTitle(title)
                .setSubtitle(subtitle)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                builder.setAllowedAuthenticators(authenticators)
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }

            val prompt = builder.build()
            prompt.authenticate(
                CancellationSignal(),
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult?
                    ) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        when (errorCode) {
                            BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
                            BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
                            BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                            BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
                            BiometricPrompt.BIOMETRIC_ERROR_UNABLE_TO_PROCESS -> {
                                // Fall back cleanly to ConfirmDeviceCredential on OEM quirks
                                launchCredentialFallback(context, title, subtitle, onSuccess, onFailure)
                            }
                            BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                            BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> {
                                onFailure?.invoke()
                            }
                            else -> {
                                val msg = errString?.takeIf { it.isNotBlank() }
                                    ?: context.getString(R.string.vault_auth_required)
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                onFailure?.invoke()
                            }
                        }
                    }
                }
            )
        } catch (_: Exception) {
            launchCredentialFallback(context, title, subtitle, onSuccess, onFailure)
        }
    }

    internal fun launchCredentialFallback(
        context: Context,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (() -> Unit)? = null
    ) {
        purgeExpiredPendingAuths()
        val token = generateToken()
        pendingAuths[token] = PendingAuth(
            onSuccess = onSuccess,
            onFailure = onFailure,
            expiresAtMs = nowMs() + PENDING_AUTH_TTL_MS
        )
        val intent = Intent(context, VaultAuthActivity::class.java).apply {
            putExtra(VaultAuthActivity.EXTRA_TOKEN, token)
            putExtra(VaultAuthActivity.EXTRA_TITLE, title)
            putExtra(VaultAuthActivity.EXTRA_SUBTITLE, subtitle)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            pendingAuths.remove(token)?.onFailure?.invoke()
            Toast.makeText(context, R.string.vault_auth_required, Toast.LENGTH_SHORT).show()
        }
    }

    /** Invoked by [VaultAuthActivity] when device credential verification succeeds. */
    fun completeAuthToken(token: String) {
        purgeExpiredPendingAuths()
        val pending = pendingAuths.remove(token) ?: return
        if (nowMs() <= pending.expiresAtMs) {
            pending.onSuccess.invoke()
        } else {
            pending.onFailure?.invoke()
        }
    }

    /** Invoked by [VaultAuthActivity] when authentication is canceled or fails. */
    fun cancelAuthToken(token: String) {
        purgeExpiredPendingAuths()
        pendingAuths.remove(token)?.onFailure?.invoke()
    }

    private fun purgeExpiredPendingAuths() {
        val now = nowMs()
        pendingAuths.entries.removeIf { it.value.expiresAtMs < now }
    }

    private fun purgeExpiredGrants() {
        val now = nowMs()
        actionGrants.entries.removeIf { it.value.expiresAtMs < now }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()
}
