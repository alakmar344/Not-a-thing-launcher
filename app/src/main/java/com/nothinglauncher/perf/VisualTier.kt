package com.nothinglauncher.perf

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.nothinglauncher.NothingPrefs

/**
 * How much visual richness this device gets. Picked once per process from what the hardware
 * can comfortably afford, so a 3–4 GB phone keeps Not a Thing's own footprint and redraws to a
 * minimum while an 8 GB phone gets the full look. Settings › Look › Visual quality can force
 * either end; [NothingPrefs.VisualQuality.AUTO] is the default.
 *
 * Only the Not a Thing layer reacts to this. Launcher3 itself (icon cache, model, widget host)
 * is stock and sizes its own memory use.
 */
object VisualTier {

    enum class Tier { LITE, BALANCED, RICH }

    /** What the hardware reported; [totalRamBytes] is what the kernel exposes, below the box size. */
    data class DeviceInfo(val totalRamBytes: Long, val lowRamDevice: Boolean, val cores: Int) {
        val totalRamGb: Float get() = totalRamBytes / GIB.toFloat()
    }

    private const val GIB = 1024L * 1024L * 1024L

    /**
     * Phones under-report their marketed RAM (the kernel and carve-outs take a slice): a "4 GB"
     * phone reports about 3.6 GiB, a "6 GB" one about 5.5 and an "8 GB" one 7.2–7.6.
     */
    private const val LITE_BELOW = 9L * GIB / 2 // 4.5 GiB: everything up to a "4 GB" phone
    private const val RICH_FROM = 68L * GIB / 10 // 6.8 GiB: "8 GB" phones and up

    /** Pure classification, separated from the system calls so it can be unit tested. */
    fun classify(info: DeviceInfo): Tier = when {
        info.lowRamDevice || info.totalRamBytes in 1 until LITE_BELOW || info.cores in 1..4 -> Tier.LITE
        info.totalRamBytes >= RICH_FROM && info.cores >= 6 -> Tier.RICH
        else -> Tier.BALANCED
    }

    @Volatile private var deviceInfo: DeviceInfo? = null
    @Volatile private var cached: Tier? = null
    @Volatile private var testOverride: Tier? = null

    fun deviceInfo(context: Context): DeviceInfo = deviceInfo ?: detect(context).also { deviceInfo = it }

    private fun detect(context: Context): DeviceInfo = try {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mem)
        DeviceInfo(mem.totalMem, am?.isLowRamDevice == true, Runtime.getRuntime().availableProcessors())
    } catch (_: Throwable) {
        // Host-side renderers (layoutlib) have no ActivityManager; assume a mid-range phone.
        DeviceInfo(0L, false, 0)
    }

    /** The tier to render with: the user's choice, else what this device was classified as. */
    fun current(context: Context): Tier {
        testOverride?.let { return it }
        cached?.let { return it }
        val prefs = NothingPrefs.get(context)
        val tier = when (prefs.visualQuality) {
            NothingPrefs.VisualQuality.LITE -> Tier.LITE
            NothingPrefs.VisualQuality.FULL -> Tier.RICH
            NothingPrefs.VisualQuality.AUTO ->
                if (prefs.memoryPressureSeen) Tier.LITE else classify(deviceInfo(context))
        }
        cached = tier
        return tier
    }

    /** What Auto resolves to on this device right now (for the settings subtitle). */
    fun autoTier(context: Context): Tier =
        if (NothingPrefs.get(context).memoryPressureSeen) Tier.LITE else classify(deviceInfo(context))

    /** Call after changing [NothingPrefs.visualQuality]; views pick it up when home is rebuilt. */
    fun invalidate() {
        cached = null
    }

    /** Screenshot tests render a fixed tier regardless of the host machine. */
    fun setForTests(tier: Tier?) {
        testOverride = tier
    }

    /**
     * Whether decorative motion may run now: not when the user turned animations off
     * (accessibility / developer options) or Battery Saver is on. Cheap enough to ask per use.
     */
    fun motionAllowed(context: Context): Boolean {
        if (testOverride != null) return false
        if (!ValueAnimator.areAnimatorsEnabled()) return false
        val pm = context.getSystemService(PowerManager::class.java)
        return pm?.isPowerSaveMode != true
    }

    /** Effects a surface should draw, resolved from [current]. */
    fun effects(context: Context): Effects = Effects.of(current(context))

    /**
     * Android 11+ keeps the reasons this process died. If the system killed the launcher for
     * memory, either the low-memory killer or Android 17's per-app memory limiter
     * ("MemoryLimiter" in the description), Auto drops to Lite from then on, because this phone
     * is evidently short of RAM for what's running on it. Picking Auto again in settings
     * clears it. Runs off the main thread; it's a binder call on the startup path.
     */
    fun checkPastExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        Thread({ scanExits(app) }, "nt-exit-check").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun scanExits(app: Context) {
        try {
            val prefs = NothingPrefs.get(app)
            val am = app.getSystemService(ActivityManager::class.java) ?: return
            val since = prefs.lastExitCheck
            val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 5)
                .filter { it.timestamp > since }
            if (exits.isEmpty()) return
            prefs.lastExitCheck = exits.maxOf { it.timestamp }
            if (exits.any(::isMemoryKill) && !prefs.memoryPressureSeen) {
                prefs.memoryPressureSeen = true
                invalidate()
            }
        } catch (_: Throwable) {
            // Best effort only.
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isMemoryKill(info: ApplicationExitInfo): Boolean =
        info.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
            (info.reason == ApplicationExitInfo.REASON_OTHER &&
                info.description?.contains("MemoryLimiter") == true)

    /** Cross-window blur (Android 12+) is a device/user setting; also off in Battery Saver. */
    fun blurAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        return wm.isCrossWindowBlurEnabled
    }
}

/**
 * The concrete knobs each tier turns. Kept as data so every surface reads the same decisions.
 */
data class Effects(
    /** Soft halo around lit LED dots. Doubles the circles drawn, so not on Lite. */
    val dotGlow: Boolean,
    /** Backdrop edge vignette (a full-screen gradient). */
    val vignette: Boolean,
    /** Backdrop "light pool": dots near the top glow brighter, like a lit LED panel. */
    val backdropLight: Boolean,
    /** Red scan-line that lights the clock up when you come back to home after a while. */
    val clockPowerOn: Boolean,
    /** Blur behind sheets on Android 12+, where the device supports it. */
    val sheetBlur: Boolean,
    /** Settings cards slide in one after another. */
    val entranceMotion: Boolean,
    /** Media visualiser step interval. */
    val visualizerFrameMs: Long,
    /** Distinct brightness steps of the charging pulse per half cycle (each is a redraw). */
    val pulseSteps: Int
) {
    companion object {
        private val LITE = Effects(
            dotGlow = false, vignette = false, backdropLight = false, clockPowerOn = false,
            sheetBlur = false, entranceMotion = false, visualizerFrameMs = 320L, pulseSteps = 6
        )
        private val BALANCED = Effects(
            dotGlow = true, vignette = true, backdropLight = false, clockPowerOn = true,
            sheetBlur = false, entranceMotion = true, visualizerFrameMs = 140L, pulseSteps = 24
        )
        private val RICH = Effects(
            dotGlow = true, vignette = true, backdropLight = true, clockPowerOn = true,
            sheetBlur = true, entranceMotion = true, visualizerFrameMs = 90L, pulseSteps = 0
        )

        /** pulseSteps = 0 means "every frame". */
        fun of(tier: VisualTier.Tier): Effects = when (tier) {
            VisualTier.Tier.LITE -> LITE
            VisualTier.Tier.BALANCED -> BALANCED
            VisualTier.Tier.RICH -> RICH
        }
    }
}
