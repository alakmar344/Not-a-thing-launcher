package com.nothinglauncher

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings owned by the Not a Thing layer (Launcher3 keeps its own LauncherPrefs for the
 * home screen model). Only state the dot-matrix elements need lives here.
 */
class NothingPrefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Manual weather city; blank means "locate automatically". */
    var weatherCity: String
        get() = sp.getString(K_WEATHER_CITY, "").orEmpty()
        set(value) = sp.edit().putString(K_WEATHER_CITY, value.trim()).apply()

    /** Last successful weather response (serialized by [WeatherRepository]). */
    var weatherCache: String?
        get() = sp.getString(K_WEATHER_CACHE, null)
        set(value) = sp.edit().apply {
            if (value == null) remove(K_WEATHER_CACHE) else putString(K_WEATHER_CACHE, value)
        }.apply()

    /** Home and drawer backdrop (the v10 "Background" setting). */
    var background: Background
        get() = Background.entries.getOrElse(sp.getInt(K_BACKGROUND, 0)) { Background.DOTS }
        set(value) = sp.edit().putInt(K_BACKGROUND, value.ordinal).apply()

    /** Live row of 60 dots under the LED clock. */
    var showSeconds: Boolean
        get() = sp.getBoolean(K_SECONDS, true)
        set(value) = sp.edit().putBoolean(K_SECONDS, value).apply()

    /** Night mode the cached app icons were rendered for (-1 = unknown). */
    var iconsRenderedNight: Int
        get() = sp.getInt(K_ICONS_NIGHT, -1)
        set(value) = sp.edit().putInt(K_ICONS_NIGHT, value).apply()

    /** Whether the default v10 home tiles were placed on the first screen. */
    var defaultTilesPlaced: Boolean
        get() = sp.getBoolean(K_TILES_PLACED, false)
        set(value) = sp.edit().putBoolean(K_TILES_PLACED, value).apply()

    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

    enum class Background { DOTS, BLACK, WALLPAPER }

    enum class IconStyle { MONO, COLOR }

    /** Whether the one-time defaults of this layer (e.g. monochrome icons) were applied. */
    var defaultsApplied: Boolean
        get() = sp.getBoolean(K_DEFAULTS_APPLIED, false)
        set(value) = sp.edit().putBoolean(K_DEFAULTS_APPLIED, value).apply()

    companion object {
        private const val FILE = "not_a_thing"
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
