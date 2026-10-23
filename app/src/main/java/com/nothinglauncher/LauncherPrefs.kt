package com.nothinglauncher

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

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
    enum class ThemeMode { SYSTEM, DARK, LIGHT }
    enum class IconSize { SMALL, MEDIUM, LARGE }

    private val appContext = context.applicationContext
    private val sp: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Settings -----------------------------------------------------------------------

    var themeMode: ThemeMode
        get() = enumOf(sp.getString(K_THEME_MODE, null), ThemeMode.SYSTEM)
        set(value) = putSetting(K_THEME_MODE, value.name)

    var iconStyle: IconStyle
        get() = enumOf(sp.getString(K_ICON_STYLE, null), IconStyle.MONO)
        set(value) = putSetting(K_ICON_STYLE, value.name)

    var iconSize: IconSize
        get() = enumOf(sp.getString(K_ICON_SIZE, null), IconSize.MEDIUM)
        set(value) = putSetting(K_ICON_SIZE, value.name)

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

    var showDockLabels: Boolean
        get() = sp.getBoolean(K_DOCK_LABELS, false)
        set(value) = putSetting(K_DOCK_LABELS, value)

    var doubleTapToSleep: Boolean
        get() = sp.getBoolean(K_DOUBLE_TAP, true)
        set(value) = putSetting(K_DOUBLE_TAP, value)

    var gridColumns: Int
        get() = sp.getInt(K_COLUMNS, 4).coerceIn(3, 5)
        set(value) = putSetting(K_COLUMNS, value.coerceIn(3, 5))

    var showSeconds: Boolean
        get() = sp.getBoolean(K_SECONDS, true)
        set(value) = putSetting(K_SECONDS, value)

    var showRecents: Boolean
        get() = sp.getBoolean(K_RECENTS, true)
        set(value) = putSetting(K_RECENTS, value)

    var quickNote: String
        get() = sp.getString(K_QUICK_NOTE, "NOTHING BUT TIME") ?: "NOTHING BUT TIME"
        set(value) = putSetting(K_QUICK_NOTE, value)

    var showDotGrid: Boolean
        get() = sp.getBoolean(K_SHOW_DOT_GRID, true)
        set(value) = putSetting(K_SHOW_DOT_GRID, value)

    var autoKeyboard: Boolean
        get() = sp.getBoolean(K_KEYBOARD, false)
        set(value) = putSetting(K_KEYBOARD, value)

    val settingsVersion: Int get() = sp.getInt(K_VERSION, 0)

    fun accentColor(): Int = ContextCompat.getColor(
        appContext,
        if (accent == Accent.RED) R.color.nothing_red else R.color.nothing_white
    )

    // ---- Layout data --------------------------------------------------------------------

    var pages: List<WorkspacePage>?
        get() = sp.getString(K_PAGES_V3, null)?.let(::parsePages)
        set(value) = putData(K_PAGES_V3, value?.let(::serializePages))

    var activePageIndex: Int
        get() = sp.getInt(K_ACTIVE_PAGE, 0)
        set(value) = sp.edit().putInt(K_ACTIVE_PAGE, value).apply()

    /** Ordered component keys pinned to home page 0 (backward-compatible). */
    var homeItems: List<String>?
        get() = pages?.firstOrNull()?.appKeys ?: sp.getString(K_HOME, null)?.let(::parseStrings)
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
        get() {
            val fromPages = pages?.flatMap { it.widgetIds }
            if (fromPages != null && fromPages.isNotEmpty()) return fromPages
            return sp.getString(K_WIDGETS, null)?.let(::parseStrings)?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        }
        set(value) = putData(K_WIDGETS, JSONArray(value.map { it.toString() }).toString())

    fun resetLayout() {
        sp.edit()
            .remove(K_HOME).remove(K_DOCK).remove(K_LEGACY_HOME).remove(K_LEGACY_DOCK)
            .remove(K_WIDGETS).remove(K_PAGES_V3).remove(K_ACTIVE_PAGE)
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

    private fun parsePages(json: String): List<WorkspacePage>? = try {
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val obj = arr.getJSONObject(i)
            val id = obj.optString("id", "page_$i")
            val apps = obj.optJSONArray("apps")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val elements = obj.optJSONArray("elements")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val widgets = obj.optJSONArray("widgets")?.let { a -> List(a.length()) { a.getInt(it) } } ?: emptyList()
            val enlarged = obj.optJSONArray("enlarged")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val foldersArr = obj.optJSONArray("folders")
            val folders = if (foldersArr != null) {
                List(foldersArr.length()) { fIdx ->
                    val fObj = foldersArr.getJSONObject(fIdx)
                    val fId = fObj.optString("id", "f_$fIdx")
                    val fName = fObj.optString("name", "FOLDER")
                    val fApps = fObj.optJSONArray("apps")?.let { fa -> List(fa.length()) { fa.getString(it) } } ?: emptyList()
                    val fEnlarged = fObj.optBoolean("isEnlarged", false)
                    WorkspaceFolder(fId, fName, fApps, fEnlarged)
                }
            } else emptyList()
            WorkspacePage(id, apps, elements, widgets, enlarged, folders)
        }
    } catch (e: Exception) {
        null
    }

    private fun serializePages(pages: List<WorkspacePage>): String {
        val arr = JSONArray()
        pages.forEach { p ->
            val obj = JSONObject().apply {
                put("id", p.id)
                put("apps", JSONArray(p.appKeys))
                put("elements", JSONArray(p.elements))
                put("widgets", JSONArray(p.widgetIds))
                put("enlarged", JSONArray(p.enlargedApps))
                val fArr = JSONArray()
                p.folders.forEach { f ->
                    val fObj = JSONObject().apply {
                        put("id", f.id)
                        put("name", f.name)
                        put("apps", JSONArray(f.appKeys))
                        put("isEnlarged", f.isEnlarged)
                    }
                    fArr.put(fObj)
                }
                put("folders", fArr)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    companion object {
        const val PREFS_NAME = "launcher_prefs"
        const val MAX_RECENTS = 10

        private const val K_THEME_MODE = "theme_mode"
        private const val K_ICON_STYLE = "icon_style"
        private const val K_ICON_SIZE = "icon_size"
        private const val K_BACKGROUND = "background"
        private const val K_SWIPE_DOWN = "swipe_down"
        private const val K_ACCENT = "accent"
        private const val K_LABELS = "show_labels"
        private const val K_DOCK_LABELS = "show_dock_labels"
        private const val K_DOUBLE_TAP = "double_tap_to_sleep"
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
        private const val K_PAGES_V3 = "workspace_pages_v3"
        private const val K_ACTIVE_PAGE = "active_page_index"
        private const val K_QUICK_NOTE = "quick_note_text"
        private const val K_SHOW_DOT_GRID = "show_dot_grid"
    }
}
