package com.nothinglauncher

import com.android.launcher3.LauncherApplication
import com.android.launcher3.LauncherPrefs

/** Stock Launcher3 application plus the Not a Thing first-run defaults. */
class NothingApplication : LauncherApplication() {

    override fun onCreate() {
        applyFirstRunDefaults()
        super.onCreate()
    }

    private fun applyFirstRunDefaults() {
        val prefs = NothingPrefs.get(this)
        if (prefs.defaultsApplied) return
        // Monochrome (themed) app icons are the Not a Thing look; users can turn them off in
        // Home settings. Apps without a monochrome layer keep their colour icon (stock behaviour).
        LauncherPrefs.get(this).putSync(LauncherPrefs.THEMED_ICONS.to(true))
        prefs.defaultsApplied = true
    }
}
