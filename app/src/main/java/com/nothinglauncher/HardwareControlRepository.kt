package com.nothinglauncher

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Unified hardware control + device telemetry bridge for Dock Space and Quick Controls.
 * Every query degrades gracefully on emulators, Robolectric, and permission-restricted setups.
 */
object HardwareControlRepository {

    data class SystemVitals(
        val freeStorageGb: Float,
        val totalStorageGb: Float,
        val storageUsedPct: Int,
        val availRamGb: Float,
        val totalRamGb: Float,
        val ramUsedPct: Int,
        val screenTimeMinutesToday: Int?
    )

    data class CalendarSnippet(val title: String, val timeLabel: String)

    @Volatile var torchOn: Boolean = false
        private set

    @Volatile var hasFlash: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backCameraId: String? = null
    private var torchRegistered = false
    private val torchListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    private fun notifyTorchListeners(enabled: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            torchListeners.forEach { it(enabled) }
        } else {
            mainHandler.post { torchListeners.forEach { it(enabled) } }
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == backCameraId) {
                torchOn = enabled
                notifyTorchListeners(enabled)
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == backCameraId) {
                torchOn = false
                notifyTorchListeners(false)
            }
        }
    }

    fun initTorch(context: Context) {
        if (torchRegistered) return
        try {
            val cm = context.getSystemService(CameraManager::class.java) ?: return
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (flash && (facing == CameraCharacteristics.LENS_FACING_BACK || backCameraId == null)) {
                    backCameraId = id
                    hasFlash = true
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) break
                }
            }
            if (hasFlash) {
                cm.registerTorchCallback(torchCallback, mainHandler)
                torchRegistered = true
            }
        } catch (_: Throwable) {
            hasFlash = false
        }
    }

    fun addTorchListener(context: Context, listener: (Boolean) -> Unit) {
        initTorch(context.applicationContext)
        torchListeners += listener
        listener(torchOn)
    }

    fun removeTorchListener(listener: (Boolean) -> Unit) {
        torchListeners -= listener
    }

    fun toggleTorch(context: Context): Boolean {
        initTorch(context.applicationContext)
        val id = backCameraId ?: return false
        return try {
            val cm = context.getSystemService(CameraManager::class.java) ?: return false
            val next = !torchOn
            cm.setTorchMode(id, next)
            torchOn = next
            notifyTorchListeners(next)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun ringerMode(context: Context): Int = try {
        context.getSystemService(AudioManager::class.java)?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
    } catch (_: Throwable) {
        AudioManager.RINGER_MODE_NORMAL
    }

    fun cycleRingerOrOpenSettings(context: Context): Int {
        val am = context.getSystemService(AudioManager::class.java) ?: return AudioManager.RINGER_MODE_NORMAL
        val current = ringerMode(context)
        val next = when (current) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return try {
            am.ringerMode = next
            am.ringerMode
        } catch (_: SecurityException) {
            Intents.start(context, Intent(Settings.ACTION_SOUND_SETTINGS))
            current
        }
    }

    private const val VITALS_CACHE_MS = 5_000L
    private const val SCREEN_TIME_CACHE_MS = 45_000L
    private const val CALENDAR_CACHE_MS = 30_000L

    private val cacheLock = Any()
    private val midnightCal = Calendar.getInstance()
    private var vitalsCtxRef: WeakReference<Context>? = null
    private var vitalsAtMs: Long = -1L
    private var cachedVitals: SystemVitals? = null
    private var screenTimeCtxRef: WeakReference<Context>? = null
    private var screenTimeAtMs: Long = -1L
    private var cachedScreenMins: Int? = null
    private var calCtxRef: WeakReference<Context>? = null
    private var calAtMs: Long = -1L
    private var cachedCalendar: CalendarSnippet? = null

    fun clearCaches() {
        synchronized(cacheLock) {
            vitalsCtxRef = null
            vitalsAtMs = -1L
            cachedVitals = null
            screenTimeCtxRef = null
            screenTimeAtMs = -1L
            cachedScreenMins = null
            calCtxRef = null
            calAtMs = -1L
            cachedCalendar = null
        }
    }

    fun readVitals(context: Context, force: Boolean = false): SystemVitals {
        val appCtx = context.applicationContext ?: context
        val nowMs = SystemClock.elapsedRealtime()
        if (!force) {
            synchronized(cacheLock) {
                val prev = cachedVitals
                if (prev != null && vitalsCtxRef?.get() === appCtx && nowMs - vitalsAtMs in 0 until VITALS_CACHE_MS) {
                    return prev
                }
            }
        }
        var freeGb = 0f
        var totalGb = 0f
        var storagePct = 0
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes.coerceAtLeast(1L)
            val avail = stat.availableBytes.coerceAtLeast(0L)
            val gib = 1024f * 1024f * 1024f
            freeGb = avail / gib
            totalGb = total / gib
            storagePct = (((total - avail).toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } catch (_: Throwable) {}

        var availRam = 0f
        var totalRam = 0f
        var ramPct = 0
        try {
            val am = appCtx.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            if (info.totalMem > 0) {
                val gib = 1024f * 1024f * 1024f
                availRam = info.availMem / gib
                totalRam = info.totalMem / gib
                ramPct = (((info.totalMem - info.availMem).toDouble() / info.totalMem.toDouble()) * 100.0)
                    .toInt().coerceIn(0, 100)
            }
        } catch (_: Throwable) {}

        val screenMins = readScreenTimeMinutes(appCtx, force = force)
        val result = SystemVitals(freeGb, totalGb, storagePct, availRam, totalRam, ramPct, screenMins)
        synchronized(cacheLock) {
            vitalsCtxRef = WeakReference(appCtx)
            vitalsAtMs = SystemClock.elapsedRealtime()
            cachedVitals = result
        }
        return result
    }

    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    fun readScreenTimeMinutes(context: Context, force: Boolean = false): Int? {
        if (!hasUsageAccess(context)) {
            synchronized(cacheLock) {
                screenTimeCtxRef = null
                cachedScreenMins = null
            }
            return null
        }
        val appCtx = context.applicationContext ?: context
        val nowMs = SystemClock.elapsedRealtime()
        if (!force) {
            synchronized(cacheLock) {
                if (screenTimeCtxRef?.get() === appCtx && nowMs - screenTimeAtMs in 0 until SCREEN_TIME_CACHE_MS) {
                    return cachedScreenMins
                }
            }
        }
        val mins = try {
            val usm = appCtx.getSystemService(UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            val midnightMs = synchronized(cacheLock) {
                midnightCal.timeInMillis = now
                midnightCal.set(Calendar.HOUR_OF_DAY, 0)
                midnightCal.set(Calendar.MINUTE, 0)
                midnightCal.set(Calendar.SECOND, 0)
                midnightCal.set(Calendar.MILLISECOND, 0)
                midnightCal.timeInMillis
            }
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, midnightMs, now)
                ?: return 0
            var totalMs = 0L
            for (i in 0 until stats.size) {
                totalMs += stats[i].totalTimeInForeground
            }
            (totalMs / 60_000L).toInt().coerceAtLeast(0)
        } catch (_: Throwable) {
            null
        }
        synchronized(cacheLock) {
            screenTimeCtxRef = WeakReference(appCtx)
            screenTimeAtMs = SystemClock.elapsedRealtime()
            cachedScreenMins = mins
        }
        return mins
    }

    fun hasCalendarAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun queryNextCalendarEvent(context: Context, force: Boolean = false): CalendarSnippet? {
        if (!hasCalendarAccess(context)) return null
        val appCtx = context.applicationContext ?: context
        val nowMs = SystemClock.elapsedRealtime()
        if (!force) {
            synchronized(cacheLock) {
                if (calCtxRef?.get() === appCtx && nowMs - calAtMs in 0 until CALENDAR_CACHE_MS) {
                    return cachedCalendar
                }
            }
        }
        val snippet = try {
            val now = System.currentTimeMillis()
            val end = now + 48L * 3600_000L
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
                android.content.ContentUris.appendId(this, now)
                android.content.ContentUris.appendId(this, end)
            }.build()
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY
            )
            var found: CalendarSnippet? = null
            appCtx.contentResolver.query(
                uri, projection,
                "${CalendarContract.Instances.VISIBLE} = 1", null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val title = cursor.getString(0)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    val begin = cursor.getLong(1)
                    val allDay = cursor.getInt(2) == 1
                    val label = if (allDay) "ALL DAY"
                    else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(begin))
                    found = CalendarSnippet(title, label)
                    break
                }
            }
            found
        } catch (_: Throwable) {
            null
        }
        synchronized(cacheLock) {
            calCtxRef = WeakReference(appCtx)
            calAtMs = SystemClock.elapsedRealtime()
            cachedCalendar = snippet
        }
        return snippet
    }
}
