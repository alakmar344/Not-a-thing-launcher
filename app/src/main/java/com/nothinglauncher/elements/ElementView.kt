package com.nothinglauncher.elements

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nothinglauncher.LauncherPrefs
import com.nothinglauncher.R

/** Services the launcher offers to bespoke elements. Implemented by MainActivity. */
interface ElementHost {
    val activity: AppCompatActivity
    val prefs: LauncherPrefs
    fun accentColor(): Int

    /** Requests runtime permissions; [callback] receives true only if all were granted. */
    fun requestPermissions(permissions: Array<String>, callback: (Boolean) -> Unit)

    /** Starts an activity, showing a friendly toast instead of crashing when it can't. */
    fun startActivitySafely(intent: Intent, source: View? = null): Boolean

    /** Registers a dialog so it is dismissed when the user presses Home. */
    fun trackDialog(dialog: Dialog)

    fun hasNotificationAccess(): Boolean
    fun requestNotificationAccess()
    fun hasUsageAccess(): Boolean
    fun requestUsageAccess()
}

/**
 * Base class of every bespoke Nothing element placed on the home screen.
 *
 * Lifecycle: the launcher creates the view, sets [host], [itemId] and spans, then calls
 * [onBound]. [onLiveChanged] is true while the element is on the visible page of a resumed
 * home screen — start sensors/timers/receivers only while live. Elements must draw sensibly
 * at any size from their minimum span upwards and must never show fabricated data: when a
 * data source is unavailable (permission missing, no network…) show a clear call to action.
 */
abstract class ElementView(context: Context) : FrameLayout(context) {

    lateinit var host: ElementHost
    var itemId: Long = -1L
    val store: ElementStore by lazy { ElementStore(context, itemId) }

    var spanX = 2
        private set
    var spanY = 2
        private set

    var accentColor: Int = ContextCompat.getColor(context, R.color.nothing_red)
        set(value) {
            if (field == value) return
            field = value
            onAccentChanged(value)
        }

    var isLive = false
        private set

    val isHostReady: Boolean get() = this::host.isInitialized

    /** Called once after [host] and [itemId] are set. */
    open fun onBound() {}

    open fun onAccentChanged(color: Int) { invalidate() }

    open fun onLiveChanged(live: Boolean) {}

    open fun onSpanChanged(spanX: Int, spanY: Int) {}

    /** True if [openSettings] shows element specific options (offered in the long-press menu). */
    open val hasSettings: Boolean get() = false

    open fun openSettings() {}

    /** The element was removed from the home screen for good: release anything persistent. */
    open fun onRemoved() {}

    fun setLive(live: Boolean) {
        if (live == isLive) return
        isLive = live
        onLiveChanged(live)
    }

    fun setSpan(x: Int, y: Int) {
        if (x == spanX && y == spanY) return
        spanX = x
        spanY = y
        onSpanChanged(x, y)
    }

    override fun onDetachedFromWindow() {
        setLive(false)
        super.onDetachedFromWindow()
    }
}

/** Per-instance persistent storage for an element (keys are namespaced by item id). */
class ElementStore(context: Context, private val itemId: Long) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private fun k(key: String) = "$itemId.$key"

    fun getString(key: String, def: String? = null): String? = sp.getString(k(key), def)
    fun putString(key: String, value: String?) = sp.edit().putString(k(key), value).apply()
    fun getInt(key: String, def: Int = 0) = sp.getInt(k(key), def)
    fun putInt(key: String, value: Int) = sp.edit().putInt(k(key), value).apply()
    fun getLong(key: String, def: Long = 0L) = sp.getLong(k(key), def)
    fun putLong(key: String, value: Long) = sp.edit().putLong(k(key), value).apply()
    fun getFloat(key: String, def: Float = 0f) = sp.getFloat(k(key), def)
    fun putFloat(key: String, value: Float) = sp.edit().putFloat(k(key), value).apply()
    fun getBoolean(key: String, def: Boolean = false) = sp.getBoolean(k(key), def)
    fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(k(key), value).apply()
    fun contains(key: String) = sp.contains(k(key))
    fun remove(key: String) = sp.edit().remove(k(key)).apply()

    companion object {
        private const val NAME = "element_store"

        fun clear(context: Context, itemId: Long) {
            val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            val prefix = "$itemId."
            val e = sp.edit()
            sp.all.keys.filter { it.startsWith(prefix) }.forEach { e.remove(it) }
            e.apply()
        }

        /** Shared, non per-instance values (e.g. cached weather) use this pseudo id. */
        const val SHARED_ID = 0L
    }
}
