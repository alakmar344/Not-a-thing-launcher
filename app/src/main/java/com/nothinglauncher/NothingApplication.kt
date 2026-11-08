package com.nothinglauncher

import android.content.ComponentCallbacks2
import com.android.launcher3.LauncherApplication
import com.android.launcher3.LauncherPrefs
import com.nothinglauncher.perf.VisualTier

/** Stock Launcher3 application plus the Not a Thing first-run defaults. */
class NothingApplication : LauncherApplication() {

    override fun onCreate() {
        applyFirstRunDefaults()
        super.onCreate()
        // Android 11+: if the system killed home for memory last time, Auto quality goes Lite.
        VisualTier.checkPastExits(this)
    }

    /**
     * Gives back what the Not a Thing layer can rebuild cheaply as soon as home is out of
     * sight. On a 3–4 GB phone (and under Android 17's per-app memory limits) every megabyte
     * the launcher holds while another app runs makes it likelier to be killed and cold-start.
     */
    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_* are only delivered before Android 14.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            DotFont.clearCache()
            IconFactory.clearCache()
        }
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
