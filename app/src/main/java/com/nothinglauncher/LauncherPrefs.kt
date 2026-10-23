package com.nothinglauncher

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import org.json.JSONArray

/**
 * Typed access to every persisted launcher setting and layout list.
 *
 * Writes to *settings* bump [settingsVersion] so the home screen can cheaply detect
 * "something changed while I was in the background" and re-apply itself.
 * Layout/data writes (pinned apps, recents…) deliberately do not bump it.
 */
class LauncherPrefs(context: Context) {

    enum class IconStyle { MONO, COLOR }
    enum class Background { DOTS, BLACK, WALLPAPER }
    enum class SwipeDown { NOTIFICATIONS, SEARCH }
    enum class Accent { RED, WHITE }

    private val appContext = context.applicationContext
    private val sp: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Settings -----------------------------------------------------------------------

    var iconStyle: IconStyle
        get() = enumOf(sp.getString(K_ICON_STYLE, null), IconStyle.MONO)
        set(value) = putSetting(K_ICON_STYLE, value.name)

    var background: Background
        get() = enumOf(sp.getString(K_BACKGROUND, null), Background.DOTS)
        set(value) = putSetting(K_BACKGROUND, value.name)

    var swipeDown: SwipeDown
        get() = enumOf(sp.getString(K_SWIPE_DOWN, null), SwipeDown.NOTIFICATIONS)
        set(value) = putSetting(K_SWIPE_DOWN, value.name)

    var accent: Accent
        get() = enumOf(sp.getString(K_ACCENT, null), Accent.RED)
        set(value) = putSetting(K_ACCENT, value.name)

    var showHomeLabels: Boolean
        get() = sp.getBoolean(K_LABELS, true)
        set(value) = putSetting(K_LABELS, value)

    var gridColumns: Int
        get() = sp.getInt(K_COLUMNS, 4).coerceIn(4, 5)
        set(value) = putSetting(K_COLUMNS, value.coerceIn(4, 5))

    var showSeconds: Boolean
        get() = sp.getBoolean(K_SECONDS, true)
        set(value) = putSetting(K_SECONDS, value)

    var showRecents: Boolean
        get() = sp.getBoolean(K_RECENTS, true)
        set(value) = putSetting(K_RECENTS, value)

    var autoKeyboard: Boolean
        get() = sp.getBoolean(K_KEYBOARD, false)
        set(value) = putSetting(K_KEYBOARD, value)

    val settingsVersion: Int get() = sp.getInt(K_VERSION, 0)

    fun accentColor(): Int = ContextCompat.getColor(
        appContext,
        if (accent == Accent.RED) R.color.nothing_red else R.color.nothing_white
    )

    // ---- Layout data --------------------------------------------------------------------

    /** Ordered component keys pinned to home, or null if the user never customised it. */
    var homeItems: List<String>?
        get() = sp.getString(K_HOME, null)?.let(::parseStrings)
        set(value) = putData(K_HOME, value?.let { JSONArray(it).toString() })

    var dockItems: List<String>?
        get() = sp.getString(K_DOCK, null)?.let(::parseStrings)
        set(value) = putData(K_DOCK, value?.let { JSONArray(it).toString() })

    /** Package-name lists written by v1 of the launcher; read once for migration. */
    val legacyHomePackages: List<String>? get() = sp.getString(K_LEGACY_HOME, null)?.let(::parseStrings)
    val legacyDockPackages: List<String>? get() = sp.getString(K_LEGACY_DOCK, null)?.let(::parseStrings)

    var recents: List<String>
        get() = sp.getString(K_RECENT_LIST, null)?.let(::parseStrings) ?: emptyList()
        set(value) = putData(K_RECENT_LIST, JSONArray(value.take(MAX_RECENTS)).toString())

    var hiddenApps: Set<String>
        get() = sp.getStringSet(K_HIDDEN, emptySet())?.toSet() ?: emptySet()
        set(value) {
            // Hidden apps affect what the home screen shows, so treat as a setting.
            sp.edit().putStringSet(K_HIDDEN, value.toSet()).putInt(K_VERSION, settingsVersion + 1).apply()
        }

    var widgetIds: List<Int>
        get() = sp.getString(K_WIDGETS, null)?.let(::parseStrings)?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        set(value) = putData(K_WIDGETS, JSONArray(value.map { it.toString() }).toString())

    fun resetLayout() {
        sp.edit()
            .remove(K_HOME).remove(K_DOCK).remove(K_LEGACY_HOME).remove(K_LEGACY_DOCK)
            .remove(K_WIDGETS)
            .putInt(K_VERSION, settingsVersion + 1)
            .apply()
    }

    // ---- Helpers ------------------------------------------------------------------------

    private fun putSetting(key: String, value: Any) {
        val e = sp.edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            else -> e.putString(key, value.toString())
        }
        e.putInt(K_VERSION, settingsVersion + 1).apply()
    }

    private fun putData(key: String, value: String?) {
        if (value == null) sp.edit().remove(key).apply() else sp.edit().putString(key, value).apply()
    }

    private fun parseStrings(json: String): List<String>? = try {
        val arr = JSONArray(json)
        List(arr.length()) { arr.getString(it) }
    } catch (e: Exception) {
        null
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    companion object {
        const val PREFS_NAME = "launcher_prefs"
        const val MAX_RECENTS = 10

        private const val K_ICON_STYLE = "icon_style"
        private const val K_BACKGROUND = "background"
        private const val K_SWIPE_DOWN = "swipe_down"
        private const val K_ACCENT = "accent"
        private const val K_LABELS = "show_labels"
        private const val K_COLUMNS = "grid_columns"
        private const val K_SECONDS = "show_seconds"
        private const val K_RECENTS = "show_recents"
        private const val K_KEYBOARD = "auto_keyboard"
        private const val K_VERSION = "settings_version"
        private const val K_HOME = "home_items_v2"
        private const val K_DOCK = "dock_items_v2"
        private const val K_LEGACY_HOME = "home_apps"
        private const val K_LEGACY_DOCK = "dock_apps"
        private const val K_RECENT_LIST = "recent_items"
        private const val K_HIDDEN = "hidden_items"
        private const val K_WIDGETS = "widget_ids"
    }
}
