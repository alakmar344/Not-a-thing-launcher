package com.nothinglauncher.drawer

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.nothinglauncher.R

/**
 * Translucent confirm-device-credential activity used when [HiddenVaultSheet] runs on
 * Android 8.0–8.1/9 (API 26–28), when `BiometricPrompt` is unavailable, or from non-Activity callers.
 * Fails closed unless the system keyguard confirms the user's credential with [Activity.RESULT_OK].
 */
class VaultAuthActivity : Activity() {

    private var token: Long = INVALID_TOKEN
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getLongExtra(EXTRA_TOKEN, INVALID_TOKEN)
        if (token == INVALID_TOKEN) {
            finish()
            return
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            // Device lock state changed before launch; never grant implicitly while token expects auth.
            failClosed()
            return
        }
        @Suppress("DEPRECATION")
        val confirmIntent = try {
            km.createConfirmDeviceCredentialIntent(
                getString(R.string.vault_auth_title),
                getString(R.string.vault_auth_sub)
            )
        } catch (_: Throwable) {
            null
        }
        if (confirmIntent == null) {
            failClosed()
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(confirmIntent, REQ_CONFIRM)
        } catch (_: Throwable) {
            failClosed()
        }
    }

    @Deprecated("Required for Activity.createConfirmDeviceCredentialIntent on API 26-28")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONFIRM) return
        completed = true
        if (resultCode == RESULT_OK) {
            HiddenVaultSheet.completeAuthToken(token)
        } else {
            HiddenVaultSheet.cancelAuthToken(token)
            Toast.makeText(this, R.string.vault_auth_required, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onDestroy() {
        if (!completed && token != INVALID_TOKEN && isFinishing) {
            HiddenVaultSheet.cancelAuthToken(token)
        }
        super.onDestroy()
    }

    private fun failClosed() {
        completed = true
        HiddenVaultSheet.cancelAuthToken(token)
        Toast.makeText(this, R.string.vault_auth_required, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val EXTRA_TOKEN = "vault_auth_token"
        private const val INVALID_TOKEN = -1L
        private const val REQ_CONFIRM = 4091

        fun launch(context: Context, token: Long) {
            val intent = Intent(context, VaultAuthActivity::class.java).apply {
                putExtra(EXTRA_TOKEN, token)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
