package com.nothinglauncher.services

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Notification access powers two features: notification dots on icons and the Media element
 * (MediaSessionManager.getActiveSessions requires an enabled listener).
 */
class NotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        refreshAll()
    }

    override fun onListenerDisconnected() {
        connected = false
        NotificationDots.replace(emptyMap())
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refreshAll()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refreshAll()

    private fun refreshAll() {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return
        val counts = HashMap<NotificationDots.Key, Int>()
        for (sbn in active) {
            if (!countsForDot(sbn)) continue
            val key = NotificationDots.Key(sbn.packageName, sbn.user)
            val n = sbn.notification.number.coerceAtLeast(1)
            counts[key] = (counts[key] ?: 0) + n
        }
        NotificationDots.replace(counts)
    }

    private fun countsForDot(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (!sbn.isClearable && (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_SERVICE) return false
        return true
    }

    companion object {
        @Volatile
        var connected = false
            private set

        fun component(context: Context) = ComponentName(context, NotificationListener::class.java)

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = component(context)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** In-memory notification counts per (package, user), observed by the launcher UI. */
object NotificationDots {
    data class Key(val packageName: String, val user: UserHandle)

    fun interface Listener {
        fun onDotsChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    private var counts: Map<Key, Int> = emptyMap()

    fun count(pkg: String?, user: UserHandle?): Int {
        if (pkg == null || user == null) return 0
        return counts[Key(pkg, user)] ?: 0
    }

    fun replace(newCounts: Map<Key, Int>) {
        if (newCounts == counts) return
        counts = newCounts
        main.post { listeners.forEach { it.onDotsChanged() } }
    }

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)
}
