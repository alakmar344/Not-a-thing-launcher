package com.nothinglauncher.drawer

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.nothinglauncher.R
import com.nothinglauncher.security.DeviceAuthGate

/**
 * Translucent confirm-device-credential activity used when [DeviceAuthGate] runs on
 * Android 8.0-8.1/9 (API 26-28), when `BiometricPrompt` is unavailable, or from non-Activity callers.
 * Fails closed unless the system keyguard confirms the user's credential with [Activity.RESULT_OK].
 */
class VaultAuthActivity : Activity() {

    private var token: String = ""
    private var completed = false
    private var launchedAuth = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceAuthGate.applySecureWindow(this)
        token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (token.isEmpty()) {
            finish()
            return
        }
        if (savedInstanceState != null) {
            launchedAuth = savedInstanceState.getBoolean(STATE_LAUNCHED, false)
            completed = savedInstanceState.getBoolean(STATE_COMPLETED, false)
            if (launchedAuth || completed) return
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            // Device lock state changed before launch; never grant implicitly while token expects auth.
            failClosed()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.vault_auth_title)
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.vault_auth_sub)
        @Suppress("DEPRECATION")
        val confirmIntent = try {
            km.createConfirmDeviceCredentialIntent(title, subtitle)
        } catch (_: Throwable) {
            null
        }
        if (confirmIntent == null) {
            failClosed()
            return
        }
        try {
            launchedAuth = true
            @Suppress("DEPRECATION")
            startActivityForResult(confirmIntent, REQ_CONFIRM)
        } catch (_: Throwable) {
            launchedAuth = false
            failClosed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED, launchedAuth)
        outState.putBoolean(STATE_COMPLETED, completed)
    }

    @Deprecated("Required for Activity.createConfirmDeviceCredentialIntent on API 26-28")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONFIRM) return
        completed = true
        if (resultCode == RESULT_OK) {
            DeviceAuthGate.completeAuthToken(token)
        } else {
            DeviceAuthGate.cancelAuthToken(token)
            Toast.makeText(this, R.string.vault_auth_required, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onDestroy() {
        if (!completed && token.isNotEmpty() && isFinishing) {
            DeviceAuthGate.cancelAuthToken(token)
        }
        super.onDestroy()
    }

    private fun failClosed() {
        completed = true
        DeviceAuthGate.cancelAuthToken(token)
        Toast.makeText(this, R.string.vault_auth_required, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_TOKEN = "vault_auth_token"
        const val EXTRA_TITLE = "vault_auth_title"
        const val EXTRA_SUBTITLE = "vault_auth_subtitle"
        private const val STATE_LAUNCHED = "state_launched"
        private const val STATE_COMPLETED = "state_completed"
        private const val REQ_CONFIRM = 4091
    }
}
