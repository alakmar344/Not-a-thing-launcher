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

    /** Whether the one-time defaults of this layer (e.g. monochrome icons) were applied. */
    var defaultsApplied: Boolean
        get() = sp.getBoolean(K_DEFAULTS_APPLIED, false)
        set(value) = sp.edit().putBoolean(K_DEFAULTS_APPLIED, value).apply()

    companion object {
        private const val FILE = "not_a_thing"
        private const val K_WEATHER_CITY = "weather_city"
        private const val K_WEATHER_CACHE = "weather_cache"
        private const val K_DEFAULTS_APPLIED = "defaults_applied_v11"

        @Volatile private var instance: NothingPrefs? = null

        fun get(context: Context): NothingPrefs =
            instance ?: synchronized(this) {
                instance ?: NothingPrefs(context).also { instance = it }
            }
    }
}
