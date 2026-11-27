package com.nothinglauncher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings and persistent workspace state owned by the Not a Thing layer.
 * Launcher3 keeps its own LauncherPrefs for workspace grid, icon placement, and rotation;
 * this singleton owns visual personality, Essential Space notes/timers, gestures, and vault.
 */
class NothingPrefs private constructor(context: Context) {

    private val sp: SharedPreferences? = try {
        (context.applicationContext ?: context).getSharedPreferences(FILE, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }

    /**
     * In-memory cache + Robolectric/layoutlib fallback. Uses [NULL] sentinel because
     * ConcurrentHashMap rejects null values.
     */
    private val memFallback = ConcurrentHashMap<String, Any>()

    private fun put(key: String, value: Any?, write: SharedPreferences.Editor.() -> Unit) {
        memFallback[key] = value ?: NULL
        sp?.edit()?.apply {
            write()
            apply()
        }
    }

    private fun mem(key: String): Any? = when (val v = memFallback[key]) {
        null -> Unset
        NULL -> null
        else -> v
    }

    private object Unset

    private fun getBool(key: String, def: Boolean): Boolean {
        mem(key).let { if (it !== Unset) return it as? Boolean ?: def }
        return sp?.getBoolean(key, def) ?: def
    }

    private fun putBool(key: String, v: Boolean) = put(key, v) { putBoolean(key, v) }

    private fun getInt(key: String, def: Int): Int {
        mem(key).let { if (it !== Unset) return it as? Int ?: def }
        return sp?.getInt(key, def) ?: def
    }

    private fun putInt(key: String, v: Int) = put(key, v) { putInt(key, v) }

    private fun getLong(key: String, def: Long): Long {
        mem(key).let { if (it !== Unset) return it as? Long ?: def }
        return sp?.getLong(key, def) ?: def
    }

    private fun putLong(key: String, v: Long) = put(key, v) { putLong(key, v) }

    private fun getStr(key: String, def: String = ""): String {
        mem(key).let { if (it !== Unset) return (it as? String) ?: def }
        return sp?.getString(key, def) ?: def
    }

    private fun getNullableStr(key: String): String? {
        mem(key).let { if (it !== Unset) return it as? String }
        return sp?.getString(key, null)
    }

    private fun putStr(key: String, v: String?) = put(key, v) {
        if (v == null) remove(key) else putString(key, v)
    }

    var weatherCity: String
        get() = getStr(K_WEATHER_CITY)
        set(value) = putStr(K_WEATHER_CITY, value.trim())

    var weatherCache: String?
        get() = getNullableStr(K_WEATHER_CACHE)
        set(value) = putStr(K_WEATHER_CACHE, value)

    var background: Background
        get() = Background.entries.getOrElse(getInt(K_BACKGROUND, 0)) { Background.DOTS }
        set(value) = putInt(K_BACKGROUND, value.ordinal)

    var showSeconds: Boolean
        get() = getBool(K_SECONDS, true)
        set(value) = putBool(K_SECONDS, value)

    var iconsRenderedNight: Int
        get() = getInt(K_ICONS_NIGHT, -1)
        set(value) = putInt(K_ICONS_NIGHT, value)

    var defaultTilesPlaced: Boolean
        get() = getBool(K_TILES_PLACED, false)
        set(value) = putBool(K_TILES_PLACED, value)

    var visualQuality: VisualQuality
        get() = VisualQuality.entries.getOrElse(getInt(K_QUALITY, 0)) { VisualQuality.AUTO }
        set(value) = putInt(K_QUALITY, value.ordinal)

    var memoryPressureSeen: Boolean
        get() = getBool(K_MEM_PRESSURE, false)
        set(value) = putBool(K_MEM_PRESSURE, value)

    var lastExitCheck: Long
        get() = getLong(K_EXIT_CHECK, 0L)
        set(value) = putLong(K_EXIT_CHECK, value)

    var defaultsApplied: Boolean
        get() = getBool(K_DEFAULTS_APPLIED, false)
        set(value) = putBool(K_DEFAULTS_APPLIED, value)

    // --- v13 personalization, physics, Essential Space & tools ---

    var accentPalette: AccentTheme.Palette
        get() = AccentTheme.Palette.entries.getOrElse(getInt(K_ACCENT_PALETTE, 0)) { AccentTheme.Palette.RED }
        set(value) = putInt(K_ACCENT_PALETTE, value.ordinal)

    var iconStyle: IconStyle
        get() = IconStyle.entries.getOrElse(getInt(K_ICON_STYLE, 0)) { IconStyle.MONO }
        set(value) = putInt(K_ICON_STYLE, value.ordinal)

    var iconPackPackage: String
        get() = getStr(K_ICON_PACK)
        set(value) = putStr(K_ICON_PACK, value.trim())

    var hideHomeLabels: Boolean
        get() = getBool(K_HIDE_HOME_LABELS, false)
        set(value) = putBool(K_HIDE_HOME_LABELS, value)

    var showSearchPill: Boolean
        get() = getBool(K_SHOW_SEARCH_PILL, true)
        set(value) = putBool(K_SHOW_SEARCH_PILL, value)

    var drawerLayoutMode: DrawerLayoutMode
        get() = DrawerLayoutMode.entries.getOrElse(getInt(K_DRAWER_MODE, 0)) { DrawerLayoutMode.GRID }
        set(value) = putInt(K_DRAWER_MODE, value.ordinal)

    var showDrawerRecents: Boolean
        get() = getBool(K_DRAWER_RECENTS, true)
        set(value) = putBool(K_DRAWER_RECENTS, value)

    var tactileSound: Boolean
        get() = getBool(K_TACTILE_SOUND, false)
        set(value) = putBool(K_TACTILE_SOUND, value)

    var nightDimDots: Boolean
        get() = getBool(K_NIGHT_DIM, false)
        set(value) = putBool(K_NIGHT_DIM, value)

    var focusMode: Boolean
        get() = getBool(K_FOCUS_MODE, false)
        set(value) = putBool(K_FOCUS_MODE, value)

    var caffeineEnabled: Boolean
        get() = getBool(K_CAFFEINE, false)
        set(value) = putBool(K_CAFFEINE, value)

    var essentialNote: String
        get() = getStr(K_ESSENTIAL_NOTE)
        set(value) = putStr(K_ESSENTIAL_NOTE, value.trim())

    var clockFaceMode: Int
        get() = getInt(K_CLOCK_FACE, 0).coerceIn(0, 3)
        set(value) = putInt(K_CLOCK_FACE, value.coerceIn(0, 3))

    var worldClockZoneId: String
        get() = getStr(K_WORLD_ZONE, "UTC").ifBlank { "UTC" }
        set(value) = putStr(K_WORLD_ZONE, value.trim().ifBlank { "UTC" })

    var swipeDownAction: GestureAction
        get() = GestureAction.entries.getOrElse(getInt(K_GESTURE_DOWN, GestureAction.NOTIFICATIONS.ordinal)) { GestureAction.NOTIFICATIONS }
        set(value) = putInt(K_GESTURE_DOWN, value.ordinal)

    var doubleTapAction: GestureAction
        get() = GestureAction.entries.getOrElse(getInt(K_GESTURE_DOUBLE_TAP, GestureAction.LOCK_SCREEN.ordinal)) { GestureAction.LOCK_SCREEN }
        set(value) = putInt(K_GESTURE_DOUBLE_TAP, value.ordinal)

    var pinchAction: GestureAction
        get() = GestureAction.entries.getOrElse(getInt(K_GESTURE_PINCH, GestureAction.CUSTOMIZE.ordinal)) { GestureAction.CUSTOMIZE }
        set(value) = putInt(K_GESTURE_PINCH, value.ordinal)

    var onboardingDone: Boolean
        get() = getBool(K_ONBOARDING_DONE, false)
        set(value) = putBool(K_ONBOARDING_DONE, value)

    var habitTitle: String
        get() = getStr(K_HABIT_TITLE, "FOCUS").ifBlank { "FOCUS" }
        set(value) = putStr(K_HABIT_TITLE, value.trim().ifBlank { "FOCUS" })

    var habitDates: Set<String>
        get() = parseStringSet(getStr(K_HABIT_DATES))
        set(value) = putStr(K_HABIT_DATES, encodeStringSet(value))

    var hiddenPackages: Set<String>
        get() = parseStringSet(getStr(K_HIDDEN_PACKAGES))
        set(value) = putStr(K_HIDDEN_PACKAGES, encodeStringSet(value))

    var customAppLabels: Map<String, String>
        get() = parseStringMap(getStr(K_CUSTOM_LABELS))
        set(value) = putStr(K_CUSTOM_LABELS, encodeStringMap(value))

    var recentComponents: List<String>
        get() = parseStringList(getStr(K_RECENT_APPS))
        set(value) = putStr(K_RECENT_APPS, JSONArray(value.take(8)).toString())

    fun recordAppLaunch(componentFlat: String) {
        if (componentFlat.isBlank()) return
        val next = listOf(componentFlat) + recentComponents.filterNot { it == componentFlat }
        recentComponents = next.take(8)
    }

    // Focus timer & stopwatch shared state
    var timerDurationMs: Long
        get() = getLong(K_TIMER_DURATION, 25 * 60_000L).coerceAtLeast(60_000L)
        set(value) = putLong(K_TIMER_DURATION, value.coerceAtLeast(60_000L))

    var timerRemainingMs: Long
        get() = getLong(K_TIMER_REMAINING, timerDurationMs).coerceAtLeast(0L)
        set(value) = putLong(K_TIMER_REMAINING, value.coerceAtLeast(0L))

    var timerEndAtMs: Long
        get() = getLong(K_TIMER_END_AT, 0L)
        set(value) = putLong(K_TIMER_END_AT, value)

    var timerRunning: Boolean
        get() = getBool(K_TIMER_RUNNING, false)
        set(value) = putBool(K_TIMER_RUNNING, value)

    var timerStopwatchMode: Boolean
        get() = getBool(K_TIMER_STOPWATCH, false)
        set(value) = putBool(K_TIMER_STOPWATCH, value)

    var stopwatchAccumMs: Long
        get() = getLong(K_SW_ACCUM, 0L)
        set(value) = putLong(K_SW_ACCUM, value)

    var stopwatchStartMs: Long
        get() = getLong(K_SW_START, 0L)
        set(value) = putLong(K_SW_START, value)

    // Pinned quick folder / max app tile bindings
    var maxAppComponent: String
        get() = getStr(K_MAX_APP)
        set(value) = putStr(K_MAX_APP, value.trim())

    var quickFolderTitle: String
        get() = getStr(K_FOLDER_TITLE, "ESSENTIALS").ifBlank { "ESSENTIALS" }
        set(value) = putStr(K_FOLDER_TITLE, value.trim().ifBlank { "ESSENTIALS" })

    var quickFolderApps: List<String>
        get() = parseStringList(getStr(K_FOLDER_APPS))
        set(value) = putStr(K_FOLDER_APPS, JSONArray(value.take(4)).toString())

    fun widgetSurface(widgetKey: String): AccentTheme.WidgetSurface {
        val map = parseStringMap(getStr(K_WIDGET_SURFACES))
        val name = map[widgetKey] ?: return AccentTheme.WidgetSurface.DEFAULT
        return AccentTheme.WidgetSurface.entries.firstOrNull { it.name == name }
            ?: AccentTheme.WidgetSurface.DEFAULT
    }

    fun setWidgetSurface(widgetKey: String, surface: AccentTheme.WidgetSurface) {
        val map = parseStringMap(getStr(K_WIDGET_SURFACES)).toMutableMap()
        if (surface == AccentTheme.WidgetSurface.DEFAULT) map.remove(widgetKey) else map[widgetKey] = surface.name
        putStr(K_WIDGET_SURFACES, encodeStringMap(map))
    }

    var deskKeepAwake: Boolean
        get() = getBool(K_DESK_KEEP_AWAKE, true)
        set(value) = putBool(K_DESK_KEEP_AWAKE, value)

    var deskBurnInShift: Boolean
        get() = getBool(K_DESK_BURN_IN_SHIFT, true)
        set(value) = putBool(K_DESK_BURN_IN_SHIFT, value)

    var deskMode: Int
        get() = getInt(K_DESK_MODE, 0).coerceIn(0, 3)
        set(value) = putInt(K_DESK_MODE, value.coerceIn(0, 3))

    var kineticMode: Int
        get() = getInt(K_KINETIC_MODE, 0).coerceIn(0, 2)
        set(value) = putInt(K_KINETIC_MODE, value.coerceIn(0, 2))

    var soundscapePreset: Int
        get() = getInt(K_SOUNDSCAPE_PRESET, 1).coerceIn(1, 3)
        set(value) = putInt(K_SOUNDSCAPE_PRESET, value.coerceIn(1, 3))

    fun allWidgetSurfaces(): Map<String, String> = parseStringMap(getStr(K_WIDGET_SURFACES))

    fun exportToJson(): String = JSONObject().apply {
        put("version", 15)
        put("background", background.name)
        put("visualQuality", visualQuality.name)
        put("accentPalette", accentPalette.name)
        put("iconStyle", iconStyle.name)
        put("iconPackPackage", iconPackPackage)
        put("showSeconds", showSeconds)
        put("hideHomeLabels", hideHomeLabels)
        put("showSearchPill", showSearchPill)
        put("drawerLayoutMode", drawerLayoutMode.name)
        put("showDrawerRecents", showDrawerRecents)
        put("tactileSound", tactileSound)
        put("nightDimDots", nightDimDots)
        put("focusMode", focusMode)
        put("weatherCity", weatherCity)
        put("essentialNote", essentialNote)
        put("clockFaceMode", clockFaceMode)
        put("worldClockZoneId", worldClockZoneId)
        put("swipeDownAction", swipeDownAction.name)
        put("doubleTapAction", doubleTapAction.name)
        put("pinchAction", pinchAction.name)
        put("habitTitle", habitTitle)
        put("habitDates", JSONArray(habitDates.toList()))
        put("hiddenPackages", JSONArray(hiddenPackages.toList()))
        put("customAppLabels", JSONObject(customAppLabels))
        put("maxAppComponent", maxAppComponent)
        put("quickFolderTitle", quickFolderTitle)
        put("quickFolderApps", JSONArray(quickFolderApps))
        put("widgetSurfaces", JSONObject(allWidgetSurfaces()))
        put("deskKeepAwake", deskKeepAwake)
        put("deskBurnInShift", deskBurnInShift)
        put("deskMode", deskMode)
        put("kineticMode", kineticMode)
        put("soundscapePreset", soundscapePreset)
    }.toString(2)

    fun importFromJson(raw: String): Boolean {
        if (raw.length > MAX_IMPORT_JSON_CHARS) return false
        return try {
            val o = JSONObject(raw)
            if (!o.has("version")) return false
            o.optString("background").takeIf { it.isNotEmpty() }?.let { n ->
                Background.entries.firstOrNull { it.name == n }?.let { background = it }
            }
            o.optString("visualQuality").takeIf { it.isNotEmpty() }?.let { n ->
                VisualQuality.entries.firstOrNull { it.name == n }?.let { visualQuality = it }
            }
            o.optString("accentPalette").takeIf { it.isNotEmpty() }?.let { n ->
                AccentTheme.Palette.entries.firstOrNull { it.name == n }?.let { accentPalette = it }
            }
            o.optString("iconStyle").takeIf { it.isNotEmpty() }?.let { n ->
                IconStyle.entries.firstOrNull { it.name == n }?.let { iconStyle = it }
            }
            if (o.has("iconPackPackage")) {
                iconPackPackage = o.optString("iconPackPackage", "").trim().take(256)
            }
            if (o.has("showSeconds")) showSeconds = o.optBoolean("showSeconds", showSeconds)
            if (o.has("hideHomeLabels")) hideHomeLabels = o.optBoolean("hideHomeLabels", hideHomeLabels)
            if (o.has("showSearchPill")) showSearchPill = o.optBoolean("showSearchPill", showSearchPill)
            o.optString("drawerLayoutMode").takeIf { it.isNotEmpty() }?.let { n ->
                DrawerLayoutMode.entries.firstOrNull { it.name == n }?.let { drawerLayoutMode = it }
            }
            if (o.has("showDrawerRecents")) showDrawerRecents = o.optBoolean("showDrawerRecents", showDrawerRecents)
            if (o.has("tactileSound")) tactileSound = o.optBoolean("tactileSound", tactileSound)
            if (o.has("nightDimDots")) nightDimDots = o.optBoolean("nightDimDots", nightDimDots)
            if (o.has("focusMode")) focusMode = o.optBoolean("focusMode", focusMode)
            if (o.has("weatherCity")) weatherCity = o.optString("weatherCity", "").trim().take(80)
            if (o.has("essentialNote")) essentialNote = o.optString("essentialNote", "").take(2000)
            if (o.has("clockFaceMode")) clockFaceMode = o.optInt("clockFaceMode", 0).coerceIn(0, 3)
            if (o.has("worldClockZoneId")) {
                val z = o.optString("worldClockZoneId", "UTC").trim()
                if (isValidZoneId(z)) worldClockZoneId = z
            }
            o.optString("swipeDownAction").takeIf { it.isNotEmpty() }?.let { n ->
                GestureAction.entries.firstOrNull { it.name == n }?.let { swipeDownAction = it }
            }
            o.optString("doubleTapAction").takeIf { it.isNotEmpty() }?.let { n ->
                GestureAction.entries.firstOrNull { it.name == n }?.let { doubleTapAction = it }
            }
            o.optString("pinchAction").takeIf { it.isNotEmpty() }?.let { n ->
                GestureAction.entries.firstOrNull { it.name == n }?.let { pinchAction = it }
            }
            if (o.has("habitTitle")) habitTitle = o.optString("habitTitle", "FOCUS").trim().take(32)
            o.optJSONArray("habitDates")?.let { arr ->
                val cap = arr.length().coerceAtMost(500)
                habitDates = (0 until cap)
                    .mapNotNull { arr.optString(it).trim().takeIf { s -> HABIT_DATE_REGEX.matches(s) } }
                    .toSet()
            }
            o.optJSONArray("hiddenPackages")?.let { arr ->
                val cap = arr.length().coerceAtMost(500)
                hiddenPackages = (0 until cap)
                    .mapNotNull { arr.optString(it).trim().takeIf { s -> PACKAGE_REGEX.matches(s) } }
                    .toSet()
            }
            o.optJSONObject("customAppLabels")?.let { obj ->
                val m = LinkedHashMap<String, String>()
                val keys = obj.keys()
                while (keys.hasNext() && m.size < 500) {
                    val k = keys.next()
                    if (k.length <= 256) {
                        val v = obj.optString(k, "").trim().take(64)
                        if (v.isNotBlank()) m[k] = v
                    }
                }
                customAppLabels = m
            }
            if (o.has("maxAppComponent")) {
                maxAppComponent = o.optString("maxAppComponent", "").trim().take(256)
            }
            if (o.has("quickFolderTitle")) {
                quickFolderTitle = o.optString("quickFolderTitle", "ESSENTIALS").trim().take(32)
            }
            o.optJSONArray("quickFolderApps")?.let { arr ->
                val cap = arr.length().coerceAtMost(24)
                quickFolderApps = (0 until cap)
                    .mapNotNull { arr.optString(it).trim().take(256).takeIf(String::isNotBlank) }
            }
            o.optJSONObject("widgetSurfaces")?.let { obj ->
                val m = LinkedHashMap<String, String>()
                val keys = obj.keys()
                while (keys.hasNext() && m.size < 100) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    if (k.length <= 64 && AccentTheme.WidgetSurface.entries.any { it.name == v }) {
                        m[k] = v
                    }
                }
                putStr(K_WIDGET_SURFACES, encodeStringMap(m))
            }
            if (o.has("deskKeepAwake")) deskKeepAwake = o.optBoolean("deskKeepAwake", deskKeepAwake)
            if (o.has("deskBurnInShift")) deskBurnInShift = o.optBoolean("deskBurnInShift", deskBurnInShift)
            if (o.has("deskMode")) deskMode = o.optInt("deskMode", deskMode).coerceIn(0, 3)
            if (o.has("kineticMode")) kineticMode = o.optInt("kineticMode", kineticMode).coerceIn(0, 2)
            if (o.has("soundscapePreset")) soundscapePreset = o.optInt("soundscapePreset", soundscapePreset).coerceIn(1, 3)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp?.registerOnSharedPreferenceChangeListener(listener)

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp?.unregisterOnSharedPreferenceChangeListener(listener)

    enum class Background { DOTS, BLACK, WALLPAPER }

    /** Settings -> Look -> Visual quality. AUTO follows [com.nothinglauncher.perf.VisualTier]. */
    enum class VisualQuality { AUTO, LITE, FULL }

    enum class IconStyle { MONO, COLOR, DOTS }

    enum class DrawerLayoutMode { GRID, LIST, CATEGORIES }

    enum class GestureAction { NONE, NOTIFICATIONS, SEARCH, ESSENTIAL_SPACE, DESK_HUD, LOCK_SCREEN, CUSTOMIZE, VAULT }

    companion object {
        private const val K_DESK_KEEP_AWAKE = "v14_desk_keep_awake"
        private const val K_DESK_BURN_IN_SHIFT = "v14_desk_burn_in_shift"
        private const val K_DESK_MODE = "v14_desk_mode"
        private const val FILE = "not_a_thing"
        private val NULL = Any()
        private const val K_WEATHER_CITY = "weather_city"
        private const val K_WEATHER_CACHE = "weather_cache"
        internal const val K_BACKGROUND = "background"
        private const val K_SECONDS = "show_seconds"
        private const val K_ICONS_NIGHT = "icons_rendered_night"
        private const val K_TILES_PLACED = "default_tiles_placed"
        private const val K_DEFAULTS_APPLIED = "defaults_applied_v11"
        internal const val K_QUALITY = "visual_quality"
        private const val K_MEM_PRESSURE = "memory_pressure_seen"
        private const val K_EXIT_CHECK = "last_exit_check"

        internal const val K_ACCENT_PALETTE = "v13_accent_palette"
        private const val K_ICON_STYLE = "v13_icon_style"
        private const val K_ICON_PACK = "v13_icon_pack"
        internal const val K_HIDE_HOME_LABELS = "v13_hide_home_labels"
        internal const val K_SHOW_SEARCH_PILL = "v13_show_search_pill"
        internal const val K_DRAWER_MODE = "v13_drawer_mode"
        internal const val K_DRAWER_RECENTS = "v13_drawer_recents"
        private const val K_TACTILE_SOUND = "v13_tactile_sound"
        internal const val K_NIGHT_DIM = "v13_night_dim"
        internal const val K_FOCUS_MODE = "v13_focus_mode"
        internal const val K_CAFFEINE = "v13_caffeine"
        private const val K_ESSENTIAL_NOTE = "v13_essential_note"
        private const val K_CLOCK_FACE = "v13_clock_face"
        private const val K_WORLD_ZONE = "v13_world_zone"
        private const val K_GESTURE_DOWN = "v13_gesture_down"
        private const val K_GESTURE_DOUBLE_TAP = "v13_gesture_double_tap"
        private const val K_GESTURE_PINCH = "v13_gesture_pinch"
        private const val K_ONBOARDING_DONE = "v13_onboarding_done"
        private const val K_HABIT_TITLE = "v13_habit_title"
        private const val K_HABIT_DATES = "v13_habit_dates"
        internal const val K_HIDDEN_PACKAGES = "v13_hidden_packages"
        internal const val K_CUSTOM_LABELS = "v13_custom_labels"
        internal const val K_RECENT_APPS = "v13_recent_apps"
        private const val K_TIMER_DURATION = "v13_timer_duration"
        private const val K_TIMER_REMAINING = "v13_timer_remaining"
        private const val K_TIMER_END_AT = "v13_timer_end_at"
        private const val K_TIMER_RUNNING = "v13_timer_running"
        private const val K_TIMER_STOPWATCH = "v13_timer_stopwatch"
        private const val K_SW_ACCUM = "v13_sw_accum"
        private const val K_SW_START = "v13_sw_start"
        private const val K_MAX_APP = "v13_max_app"
        private const val K_FOLDER_TITLE = "v13_folder_title"
        private const val K_FOLDER_APPS = "v13_folder_apps"
        private const val K_WIDGET_SURFACES = "v13_widget_surfaces"
        private const val K_KINETIC_MODE = "v14_kinetic_mode"
        private const val K_SOUNDSCAPE_PRESET = "v14_soundscape_preset"

        private const val MAX_IMPORT_JSON_CHARS = 512 * 1024
        private val HABIT_DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private val PACKAGE_REGEX = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
        private val VALID_ZONE_IDS: Set<String> by lazy { java.util.TimeZone.getAvailableIDs().toHashSet() }

        fun isValidZoneId(id: String): Boolean = id.isNotBlank() && id in VALID_ZONE_IDS

        @Volatile private var instance: NothingPrefs? = null

        fun get(context: Context): NothingPrefs =
            instance ?: synchronized(this) {
                instance ?: NothingPrefs(context).also { instance = it }
            }

        private fun parseStringSet(raw: String): Set<String> = parseStringList(raw).toSet()

        private fun encodeStringSet(set: Set<String>): String = JSONArray(set.toList()).toString()

        private fun parseStringList(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun parseStringMap(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            return try {
                val obj = JSONObject(raw)
                val out = LinkedHashMap<String, String>()
                obj.keys().forEach { k -> out[k] = obj.optString(k, "") }
                out
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun encodeStringMap(map: Map<String, String>): String = JSONObject(map).toString()
    }
}
