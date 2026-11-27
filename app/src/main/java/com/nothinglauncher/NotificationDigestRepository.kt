package com.nothinglauncher

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.android.launcher3.notification.NotificationKeyData
import com.android.launcher3.notification.NotificationListener
import com.android.launcher3.util.PackageUserKey
import java.util.concurrent.CopyOnWriteArraySet

/**
 * On-device Notification Digest powered directly by Launcher3's existing [NotificationListener]
 * (zero network, zero background services). Summarizes and prioritizes active notifications by app
 * inside `-1` Essential Space and StandBy Dream mode.
 */
object NotificationDigestRepository {

    data class DigestEntry(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val postTime: Long,
        val isPriority: Boolean,
        val countInPkg: Int,
        val contentIntent: PendingIntent?
    )

    data class Snapshot(
        val permissionGranted: Boolean,
        val totalCount: Int,
        val priorityCount: Int,
        val entries: List<DigestEntry>
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()
    private val stateLock = Any()

    @Volatile
    private var latestSnapshot: Snapshot = Snapshot(
        permissionGranted = false,
        totalCount = 0,
        priorityCount = 0,
        entries = emptyList()
    )

    @Volatile
    private var attachedContext: Context? = null

    @Volatile
    private var attachedToAosp = false

    private val aospListener = object : NotificationListener.NotificationsChangedListener {
        override fun onNotificationPosted(
            postedPackageUserKey: PackageUserKey,
            notificationKey: NotificationKeyData
        ) {
            scheduleRefresh()
        }

        override fun onNotificationRemoved(
            removedPackageUserKey: PackageUserKey,
            notificationKey: NotificationKeyData
        ) {
            scheduleRefresh()
        }

        override fun onNotificationFullRefresh(activeNotifications: List<StatusBarNotification>) {
            val ctx = attachedContext ?: return
            if (!hasAccess(ctx)) {
                detachOnRevoke()
                return
            }
            latestSnapshot = summarize(ctx, activeNotifications)
            notifyListeners()
        }
    }

    private val resyncRunnable = Runnable {
        if (listeners.isEmpty()) return@Runnable
        val ctx = attachedContext
        if (ctx != null && !hasAccess(ctx)) {
            detachOnRevoke()
            return@Runnable
        }
        synchronized(stateLock) {
            if (attachedToAosp) {
                try {
                    NotificationListener.removeNotificationsChangedListener(aospListener)
                    NotificationListener.addNotificationsChangedListener(aospListener)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun detachOnRevoke() {
        synchronized(stateLock) {
            if (attachedToAosp) {
                attachedToAosp = false
                mainHandler.removeCallbacks(resyncRunnable)
                try {
                    NotificationListener.removeNotificationsChangedListener(aospListener)
                } catch (_: Throwable) {}
            }
            latestSnapshot = Snapshot(false, 0, 0, emptyList())
        }
        notifyListeners()
    }

    fun hasAccess(context: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (_: Throwable) {
        false
    }

    fun current(context: Context): Snapshot {
        val granted = hasAccess(context)
        if (!granted) {
            synchronized(stateLock) {
                if (attachedToAosp) {
                    attachedToAosp = false
                    mainHandler.removeCallbacks(resyncRunnable)
                    try {
                        NotificationListener.removeNotificationsChangedListener(aospListener)
                    } catch (_: Throwable) {}
                }
                latestSnapshot = Snapshot(false, 0, 0, emptyList())
            }
            return Snapshot(false, 0, 0, emptyList())
        }
        synchronized(stateLock) {
            if (!attachedToAosp && listeners.isNotEmpty()) {
                attachedContext = context.applicationContext ?: context
                attachedToAosp = true
                try {
                    NotificationListener.addNotificationsChangedListener(aospListener)
                } catch (_: Throwable) {
                    attachedToAosp = false
                }
            }
        }
        return latestSnapshot.copy(permissionGranted = true)
    }

    fun addListener(context: Context, listener: (Snapshot) -> Unit): Snapshot {
        synchronized(stateLock) {
            attachedContext = context.applicationContext ?: context
            listeners += listener
            if (hasAccess(context) && !attachedToAosp) {
                attachedToAosp = true
                try {
                    NotificationListener.addNotificationsChangedListener(aospListener)
                } catch (_: Throwable) {
                    attachedToAosp = false
                }
            } else if (!hasAccess(context)) {
                latestSnapshot = Snapshot(false, 0, 0, emptyList())
            }
        }
        return current(context)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners -= listener
        synchronized(stateLock) {
            if (listeners.isEmpty() && attachedToAosp) {
                attachedToAosp = false
                mainHandler.removeCallbacks(resyncRunnable)
                try {
                    NotificationListener.removeNotificationsChangedListener(aospListener)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(resyncRunnable)
        mainHandler.postDelayed(resyncRunnable, 200L)
    }

    private fun notifyListeners() {
        mainHandler.post {
            val snap = latestSnapshot
            listeners.forEach { it(snap) }
        }
    }

    internal data class RawNotification(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val category: String?,
        val postTime: Long,
        val ongoing: Boolean,
        val isGroupSummary: Boolean,
        val contentIntent: PendingIntent? = null
    )

    fun summarize(context: Context, sbns: List<StatusBarNotification>): Snapshot {
        val granted = hasAccess(context)
        if (!granted) return Snapshot(false, 0, 0, emptyList())
        val pm = context.packageManager
        val selfPkg = context.packageName
        val raw = sbns.mapNotNull { sbn ->
            val pkg = sbn.packageName ?: return@mapNotNull null
            if (pkg == selfPkg || pkg == "android" || pkg == "com.android.systemui") return@mapNotNull null
            val n = sbn.notification ?: return@mapNotNull null
            val extras = n.extras
            val title = sanitizeText(extras?.getCharSequence(Notification.EXTRA_TITLE), 120)
            val text = sanitizeText(extras?.getCharSequence(Notification.EXTRA_TEXT), 240)
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Throwable) {
                pkg.substringAfterLast('.')
            }.let { sanitizeText(it, 64) }.ifEmpty { pkg.substringAfterLast('.').take(64) }
            RawNotification(
                key = sbn.key ?: "$pkg:${sbn.id}",
                packageName = pkg,
                appLabel = label,
                title = title,
                text = text,
                category = n.category,
                postTime = sbn.postTime,
                ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
                isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
                contentIntent = n.contentIntent
            )
        }
        return aggregateRaw(granted, raw)
    }

    private fun sanitizeText(cs: CharSequence?, maxLen: Int): String =
        cs?.toString()?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.take(maxLen).orEmpty()

    internal fun aggregateRaw(permissionGranted: Boolean, raw: List<RawNotification>): Snapshot {
        val filtered = raw.filter {
            !it.ongoing && !it.isGroupSummary && (it.title.isNotBlank() || it.text.isNotBlank())
        }
        if (filtered.isEmpty()) {
            return Snapshot(permissionGranted, 0, 0, emptyList())
        }

        val grouped = filtered.groupBy { it.packageName }
        val entries = grouped.values.map { list ->
            val newest = list.maxBy { it.postTime }
            val priority = list.any {
                it.category == Notification.CATEGORY_MESSAGE ||
                    it.category == Notification.CATEGORY_CALL ||
                    it.category == Notification.CATEGORY_EMAIL ||
                    it.category == Notification.CATEGORY_ALARM ||
                    it.category == Notification.CATEGORY_REMINDER ||
                    it.category == Notification.CATEGORY_EVENT
            }
            DigestEntry(
                key = newest.key,
                packageName = newest.packageName,
                appLabel = newest.appLabel,
                title = newest.title.ifBlank { newest.appLabel },
                text = newest.text,
                postTime = newest.postTime,
                isPriority = priority,
                countInPkg = list.size,
                contentIntent = newest.contentIntent
            )
        }.sortedWith(
            compareByDescending<DigestEntry> { it.isPriority }
                .thenByDescending { it.postTime }
        ).take(5)

        val priorityCount = grouped.values.sumOf { list ->
            val p = list.any {
                it.category == Notification.CATEGORY_MESSAGE ||
                    it.category == Notification.CATEGORY_CALL ||
                    it.category == Notification.CATEGORY_EMAIL ||
                    it.category == Notification.CATEGORY_ALARM ||
                    it.category == Notification.CATEGORY_REMINDER ||
                    it.category == Notification.CATEGORY_EVENT
            }
            if (p) list.size else 0
        }
        return Snapshot(
            permissionGranted = permissionGranted,
            totalCount = filtered.size,
            priorityCount = priorityCount,
            entries = entries
        )
    }
}
