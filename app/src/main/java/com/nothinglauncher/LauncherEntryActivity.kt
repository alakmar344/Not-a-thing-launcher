package com.nothinglauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible entry point behind the app-list icon (and the installer's "Open" button).
 *
 * [MainActivity] is a HOME-only activity, so on its own the launcher has no icon and the only
 * way to switch to it is buried in Settings > Default apps. This trampoline routes the tap:
 *  - already the default home app -> go to the home screen;
 *  - first run                    -> welcome tour (which offers "Set as default home");
 *  - otherwise                    -> the system "choose your home app" prompt.
 */
class LauncherEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-created while the role dialog is showing: just wait for its result.
        if (savedInstanceState != null) return

        val forceRequest = intent?.action == ACTION_SET_DEFAULT_HOME
        when {
            LauncherSystem.isDefaultHome(this) -> {
                goHome()
                finish()
            }
            !forceRequest && !LauncherPrefs(this).onboardingComplete -> {
                startActivity(Intent(this, OnboardingActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
            !LauncherSystem.requestDefaultHome(this) -> {
                openSettings()
                finish()
            }
            // else: the system prompt is showing; continue in onActivityResult.
        }
    }

    @Deprecated("Role request is started through LauncherSystem.requestDefaultHome")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != LauncherSystem.REQUEST_HOME_ROLE) return
        if (LauncherSystem.isDefaultHome(this)) {
            goHome()
        } else {
            AppLauncher.toast(this, R.string.entry_not_default)
            openSettings()
        }
        finish()
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(home)
        } catch (e: Exception) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val ACTION_SET_DEFAULT_HOME = "com.nothinglauncher.action.SET_DEFAULT_HOME"
    }
}
