package com.nothinglauncher

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import org.json.JSONArray

/**
 * Typed access to every persisted launcher setting.
 *
 * Keys are shared with v1–v8 so settings survive upgrades. Writes to *settings* bump
 * [settingsVersion] so the home screen can cheaply detect "something changed while I was in
 * the background" and re-apply itself. The home layout itself lives in [data.LauncherDb]; the
 * v8 layout keys are only read once by the migration and are never deleted (safe rollback).
 */
class LauncherPrefs(context: Context) {

    enum class IconStyle { MONO, COLOR }
    enum class Background { DOTS, BLACK, WALLPAPER }
    enum class SwipeDown { NOTIFICATIONS, SEARCH, QUICK_SETTINGS, NONE }
    enum class Accent { RED, WHITE }
    enum class ThemeMode { SYSTEM, DARK, LIGHT }
    enum class IconSize { SMALL, MEDIUM, LARGE }
    enum class DoubleTap { LOCK, NONE }

    private val appContext = context.applicationContext
    val sp: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Look -----------------------------------------------------------------------------

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

    var accent: Accent
        get() = enumOf(sp.getString(K_ACCENT, null), Accent.RED)
        set(value) = putSetting(K_ACCENT, value.name)

    // ---- Home -----------------------------------------------------------------------------

    var gridColumns: Int
        get() = sp.getInt(K_COLUMNS, 4).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        set(value) = putSetting(K_COLUMNS, value.coerceIn(MIN_COLUMNS, MAX_COLUMNS))

    var gridRows: Int
        get() = sp.getInt(K_ROWS, 6).coerceIn(MIN_ROWS, MAX_ROWS)
        set(value) = putSetting(K_ROWS, value.coerceIn(MIN_ROWS, MAX_ROWS))

    var showHomeLabels: Boolean
        get() = sp.getBoolean(K_LABELS, true)
        set(value) = putSetting(K_LABELS, value)

    var showDockLabels: Boolean
        get() = sp.getBoolean(K_DOCK_LABELS, false)
        set(value) = putSetting(K_DOCK_LABELS, value)

    var showSearchBar: Boolean
        get() = sp.getBoolean(K_SEARCH_BAR, true)
        set(value) = putSetting(K_SEARCH_BAR, value)

    var addNewAppsToHome: Boolean
        get() = sp.getBoolean(K_ADD_NEW_APPS, true)
        set(value) = putSetting(K_ADD_NEW_APPS, value)

    var notificationDots: Boolean
        get() = sp.getBoolean(K_DOTS, true)
        set(value) = putSetting(K_DOTS, value)

    var lockLayout: Boolean
        get() = sp.getBoolean(K_LOCK_LAYOUT, false)
        set(value) = putSetting(K_LOCK_LAYOUT, value)

    var showSeconds: Boolean
        get() = sp.getBoolean(K_SECONDS, true)
        set(value) = putSetting(K_SECONDS, value)

    var quickNote: String
        get() = sp.getString(K_QUICK_NOTE, DEFAULT_NOTE) ?: DEFAULT_NOTE
        set(value) = putSetting(K_QUICK_NOTE, value)

    /** Index of the page the Home button returns to. */
    var defaultPage: Int
        get() = sp.getInt(K_DEFAULT_PAGE, 0).coerceAtLeast(0)
        set(value) = putSetting(K_DEFAULT_PAGE, value.coerceAtLeast(0))

    // ---- Gestures -------------------------------------------------------------------------

    var swipeDown: SwipeDown
        get() = enumOf(sp.getString(K_SWIPE_DOWN, null), SwipeDown.NOTIFICATIONS)
        set(value) = putSetting(K_SWIPE_DOWN, value.name)

    /** v1–v8 stored a boolean under [K_DOUBLE_TAP]; it maps onto LOCK/NONE. */
    var doubleTap: DoubleTap
        get() = if (sp.getBoolean(K_DOUBLE_TAP, true)) DoubleTap.LOCK else DoubleTap.NONE
        set(value) = putSetting(K_DOUBLE_TAP, value == DoubleTap.LOCK)

    // ---- Drawer ---------------------------------------------------------------------------

    var showRecents: Boolean
        get() = sp.getBoolean(K_RECENTS, true)
        set(value) = putSetting(K_RECENTS, value)

    var autoKeyboard: Boolean
        get() = sp.getBoolean(K_KEYBOARD, false)
        set(value) = putSetting(K_KEYBOARD, value)

    var recents: List<String>
        get() = sp.getString(K_RECENT_LIST, null)?.let(::parseStrings) ?: emptyList()
        set(value) {
            sp.edit().putString(K_RECENT_LIST, JSONArray(value.take(MAX_RECENTS)).toString()).apply()
        }

    var hiddenApps: Set<String>
        get() = sp.getStringSet(K_HIDDEN, emptySet())?.toSet() ?: emptySet()
        set(value) {
            // Hidden apps affect what the launcher shows, so treat as a setting.
            sp.edit().putStringSet(K_HIDDEN, value.toSet()).putInt(K_VERSION, settingsVersion + 1).apply()
        }

    // ---- State ----------------------------------------------------------------------------

    val settingsVersion: Int get() = sp.getInt(K_VERSION, 0)

    var onboardingDone: Boolean
        get() = sp.getBoolean(K_ONBOARDING_DONE, false)
        set(value) = sp.edit().putBoolean(K_ONBOARDING_DONE, value).apply()

    /** Layout schema the data has been migrated to (8 = SharedPreferences JSON, 9 = SQLite). */
    var layoutSchema: Int
        get() = sp.getInt(K_LAYOUT_SCHEMA, 0)
        set(value) = sp.edit().putInt(K_LAYOUT_SCHEMA, value).commit().let { }

    /** versionCode of the APK that last ran; used to detect upgrades. */
    var lastRunVersionCode: Int
        get() = sp.getInt(K_LAST_VERSION_CODE, 0)
        set(value) = sp.edit().putInt(K_LAST_VERSION_CODE, value).apply()

    /** AppWidget id currently going through bind/configure (survives process death). */
    var pendingWidgetId: Int
        get() = sp.getInt(K_PENDING_WIDGET, -1)
        set(value) = sp.edit().putInt(K_PENDING_WIDGET, value).commit().let { }

    var activePageIndex: Int
        get() = sp.getInt(K_ACTIVE_PAGE, 0)
        set(value) = sp.edit().putInt(K_ACTIVE_PAGE, value).apply()

    fun accentColor(): Int = ContextCompat.getColor(
        appContext,
        if (accent == Accent.RED) R.color.nothing_red else R.color.nothing_white
    )

    /** True if anything from an older launcher version exists (used to skip onboarding). */
    val hasAnyV8Data: Boolean
        get() = sp.contains(K_PAGES_V3) || sp.contains(K_DOCK) || sp.contains(K_HOME) ||
            sp.contains(K_LEGACY_HOME) || sp.contains(K_LEGACY_DOCK) || sp.contains(K_VERSION)

    // ---- v1–v8 layout (read-only, for migration) -----------------------------------------

    val v8Pages: List<WorkspacePage>? get() = sp.getString(K_PAGES_V3, null)?.let(::parsePages)
    val v8HomeItems: List<String>? get() = sp.getString(K_HOME, null)?.let(::parseStrings)
    val v8DockItems: List<String>? get() = sp.getString(K_DOCK, null)?.let(::parseStrings)
    val legacyHomePackages: List<String>? get() = sp.getString(K_LEGACY_HOME, null)?.let(::parseStrings)
    val legacyDockPackages: List<String>? get() = sp.getString(K_LEGACY_DOCK, null)?.let(::parseStrings)
    val v8WidgetIds: List<Int>
        get() = sp.getString(K_WIDGETS, null)?.let(::parseStrings)?.mapNotNull { it.toIntOrNull() } ?: emptyList()

    // ---- Backup support -------------------------------------------------------------------

    /** Every user-facing setting as a plain map (for export). */
    fun exportSettings(): Map<String, Any?> =
        sp.all.filterKeys { it in EXPORTABLE_KEYS }

    fun importSettings(values: Map<String, Any?>) {
        val e = sp.edit()
        values.forEach { (k, v) ->
            if (k !in EXPORTABLE_KEYS) return@forEach
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putInt(k, v.toInt())
                is String -> e.putString(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
                null -> e.remove(k)
            }
        }
        e.putInt(K_VERSION, settingsVersion + 1).apply()
    }

    // ---- Helpers --------------------------------------------------------------------------

    private fun putSetting(key: String, value: Any) {
        val e = sp.edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            else -> e.putString(key, value.toString())
        }
        e.putInt(K_VERSION, settingsVersion + 1).apply()
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
            val apps = obj.optJSONArray("apps")?.let { a -> List(a.length()) { a.optString(it, "") } } ?: emptyList()
            val elements = obj.optJSONArray("elements")?.let { a -> List(a.length()) { a.optString(it, "") } } ?: emptyList()
            val widgets = obj.optJSONArray("widgets")?.let { a -> List(a.length()) { a.optInt(it, -1) } } ?: emptyList()
            val enlarged = obj.optJSONArray("enlarged")?.let { a -> List(a.length()) { a.optString(it, "") } } ?: emptyList()
            val foldersArr = obj.optJSONArray("folders")
            val folders = if (foldersArr != null) {
                List(foldersArr.length()) { fIdx ->
                    val fObj = foldersArr.getJSONObject(fIdx)
                    WorkspaceFolder(
                        id = fObj.optString("id", "f_$fIdx"),
                        name = fObj.optString("name", "FOLDER"),
                        appKeys = fObj.optJSONArray("apps")?.let { fa -> List(fa.length()) { fa.optString(it, "") } } ?: emptyList(),
                        isEnlarged = fObj.optBoolean("isEnlarged", false)
                    )
                }
            } else emptyList()
            WorkspacePage(id, apps, elements, widgets.filter { it > 0 }, enlarged, folders)
        }
    } catch (e: Exception) {
        null
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    companion object {
        const val PREFS_NAME = "launcher_prefs"
        const val MAX_RECENTS = 10
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 6
        const val MIN_ROWS = 4
        const val MAX_ROWS = 8
        const val DEFAULT_NOTE = "NOTHING BUT TIME"

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
        private const val K_ROWS = "grid_rows"
        private const val K_SECONDS = "show_seconds"
        private const val K_RECENTS = "show_recents"
        private const val K_KEYBOARD = "auto_keyboard"
        private const val K_SEARCH_BAR = "show_search_bar"
        private const val K_ADD_NEW_APPS = "add_new_apps"
        private const val K_DOTS = "notification_dots"
        private const val K_LOCK_LAYOUT = "lock_layout"
        private const val K_DEFAULT_PAGE = "default_page"
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
        private const val K_ONBOARDING_DONE = "onboarding_done_v9"
        private const val K_LAYOUT_SCHEMA = "layout_schema"
        private const val K_LAST_VERSION_CODE = "last_version_code"
        private const val K_PENDING_WIDGET = "pending_widget_id"

        private val EXPORTABLE_KEYS = setOf(
            K_THEME_MODE, K_ICON_STYLE, K_ICON_SIZE, K_BACKGROUND, K_SWIPE_DOWN, K_ACCENT, K_LABELS,
            K_DOCK_LABELS, K_DOUBLE_TAP, K_COLUMNS, K_ROWS, K_SECONDS, K_RECENTS, K_KEYBOARD,
            K_SEARCH_BAR, K_ADD_NEW_APPS, K_DOTS, K_LOCK_LAYOUT, K_DEFAULT_PAGE, K_HIDDEN, K_QUICK_NOTE
        )
    }
}
