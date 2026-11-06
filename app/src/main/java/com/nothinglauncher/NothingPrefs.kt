package com.nothinglauncher

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings owned by the Not a Thing layer (Launcher3 keeps its own LauncherPrefs for the
 * home screen model). Only state the dot-matrix elements need lives here.
 */
class NothingPrefs private constructor(context: Context) {

    private val sp: SharedPreferences? = try {
        (context.applicationContext ?: context).getSharedPreferences(FILE, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }

    /**
     * Values written this process, read before [sp]. Concurrent because [WeatherRepository]
     * writes the cache from its fetch thread while the main thread reads: a plain HashMap
     * mutated from two threads can corrupt itself. ConcurrentHashMap rejects nulls, so a
     * stored null is kept as [NULL].
     */
    private val memFallback = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun put(key: String, value: Any?, write: SharedPreferences.Editor.() -> Unit) {
        memFallback[key] = value ?: NULL
        sp?.edit()?.apply {
            write()
            apply()
        }
    }

    /** The in-memory value for [key]: absent → [Unset], stored null → null. */
    private fun mem(key: String): Any? = when (val v = memFallback[key]) {
        null -> Unset
        NULL -> null
        else -> v
    }

    private object Unset

    /** Manual weather city; blank means "locate automatically". */
    var weatherCity: String
        get() {
            mem(K_WEATHER_CITY).let { v -> if (v !== Unset) return (v as? String).orEmpty() }
            return sp?.getString(K_WEATHER_CITY, "").orEmpty()
        }
        set(value) = put(K_WEATHER_CITY, value.trim()) { putString(K_WEATHER_CITY, value.trim()) }

    /** Last successful weather response (serialized by [WeatherRepository]). */
    var weatherCache: String?
        get() {
            mem(K_WEATHER_CACHE).let { v -> if (v !== Unset) return v as? String }
            return sp?.getString(K_WEATHER_CACHE, null)
        }
        set(value) = put(K_WEATHER_CACHE, value) {
            if (value == null) remove(K_WEATHER_CACHE) else putString(K_WEATHER_CACHE, value)
        }

    /** Home and drawer backdrop (the v10 "Background" setting). */
    var background: Background
        get() {
            mem(K_BACKGROUND).let { v ->
                if (v !== Unset) return Background.entries.getOrElse(v as? Int ?: 0) { Background.DOTS }
            }
            return Background.entries.getOrElse(sp?.getInt(K_BACKGROUND, 0) ?: 0) { Background.DOTS }
        }
        set(value) = put(K_BACKGROUND, value.ordinal) { putInt(K_BACKGROUND, value.ordinal) }

    /** Live row of 60 dots under the LED clock. */
    var showSeconds: Boolean
        get() {
            mem(K_SECONDS).let { v -> if (v !== Unset) return v as? Boolean ?: true }
            return sp?.getBoolean(K_SECONDS, true) ?: true
        }
        set(value) = put(K_SECONDS, value) { putBoolean(K_SECONDS, value) }

    /** Night mode the cached app icons were rendered for (-1 = unknown). */
    var iconsRenderedNight: Int
        get() {
            mem(K_ICONS_NIGHT).let { v -> if (v !== Unset) return v as? Int ?: -1 }
            return sp?.getInt(K_ICONS_NIGHT, -1) ?: -1
        }
        set(value) = put(K_ICONS_NIGHT, value) { putInt(K_ICONS_NIGHT, value) }

    /** Whether the default v10 home tiles were placed on the first screen. */
    var defaultTilesPlaced: Boolean
        get() {
            mem(K_TILES_PLACED).let { v -> if (v !== Unset) return v as? Boolean ?: false }
            return sp?.getBoolean(K_TILES_PLACED, false) ?: false
        }
        set(value) = put(K_TILES_PLACED, value) { putBoolean(K_TILES_PLACED, value) }

    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp?.registerOnSharedPreferenceChangeListener(listener)

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp?.unregisterOnSharedPreferenceChangeListener(listener)

    enum class Background { DOTS, BLACK, WALLPAPER }

    enum class IconStyle { MONO, COLOR }

    /** Whether the one-time defaults of this layer (e.g. monochrome icons) were applied. */
    var defaultsApplied: Boolean
        get() {
            mem(K_DEFAULTS_APPLIED).let { v -> if (v !== Unset) return v as? Boolean ?: false }
            return sp?.getBoolean(K_DEFAULTS_APPLIED, false) ?: false
        }
        set(value) = put(K_DEFAULTS_APPLIED, value) { putBoolean(K_DEFAULTS_APPLIED, value) }

    companion object {
        private const val FILE = "not_a_thing"
        private val NULL = Any()
        private const val K_WEATHER_CITY = "weather_city"
        private const val K_WEATHER_CACHE = "weather_cache"
        private const val K_BACKGROUND = "background"
        private const val K_SECONDS = "show_seconds"
        private const val K_ICONS_NIGHT = "icons_rendered_night"
        private const val K_TILES_PLACED = "default_tiles_placed"
        private const val K_DEFAULTS_APPLIED = "defaults_applied_v11"

        @Volatile private var instance: NothingPrefs? = null

        fun get(context: Context): NothingPrefs =
            instance ?: synchronized(this) {
                instance ?: NothingPrefs(context).also { instance = it }
            }
    }
}
