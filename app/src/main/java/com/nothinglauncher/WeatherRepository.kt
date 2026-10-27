package com.nothinglauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Live weather for the Weather element (Open-Meteo, no API key).
 * A manual city can be set from the element menu; otherwise the approximate city is
 * resolved from the network's public IP. Responses are cached across restarts.
 */
object WeatherRepository {

    data class Weather(
        val city: String,
        val temperature: Int,
        val condition: String,
        val high: Int,
        val low: Int,
        val isDay: Boolean,
        val updatedAt: Long
    )

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "weather-fetch").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val lastAttempt = LongArray(1)
    private val listeners = CopyOnWriteArraySet<(Weather?) -> Unit>()
    private val pendingCallbacks = ConcurrentLinkedQueue<(Weather?) -> Unit>()
    private val rerunRequested = AtomicBoolean(false)
    private val cityGeneration = AtomicInteger(0)

    private const val CACHE_FRESH_MS = 30 * 60 * 1000L
    private const val RETRY_BACKOFF_MS = 60 * 1000L

    fun cached(prefs: LauncherPrefs): Weather? = parse(prefs.weatherCache)

    fun isFresh(weather: Weather?): Boolean =
        weather != null && System.currentTimeMillis() - weather.updatedAt < CACHE_FRESH_MS

    /** Every weather element on screen listens here so one fetch updates all of them. */
    fun addListener(prefs: LauncherPrefs, listener: (Weather?) -> Unit): Weather? {
        listeners += listener
        val cached = cached(prefs)
        main.post { if (listener in listeners) listener(cached) }
        return cached
    }

    fun removeListener(listener: (Weather?) -> Unit) {
        listeners -= listener
    }

    fun refresh(
        context: Context,
        prefs: LauncherPrefs,
        force: Boolean = false,
        callback: (Weather?) -> Unit
    ) {
        val now = System.currentTimeMillis()
        if (!force && isFresh(cached(prefs))) {
            callback(cached(prefs))
            return
        }
        if (now - lastAttempt[0] < RETRY_BACKOFF_MS && !force) {
            callback(cached(prefs))
            return
        }
        // Every caller gets an answer, even when it joins a fetch that is already running.
        pendingCallbacks += callback
        if (!running.compareAndSet(false, true)) {
            // A forced refresh (e.g. a new city) must not be satisfied by a stale in-flight fetch.
            if (force) rerunRequested.set(true)
            return
        }
        lastAttempt[0] = now
        val appContext = context.applicationContext
        io.execute { runFetch(appContext, prefs) }
    }

    private fun runFetch(appContext: Context, prefs: LauncherPrefs) {
        while (true) {
            rerunRequested.set(false)
            val generation = cityGeneration.get()
            val result = try {
                fetch(appContext, prefs)
            } catch (e: Exception) {
                null
            }
            // The city changed while we were fetching: discard the result and go again.
            if (generation != cityGeneration.get() || rerunRequested.get()) continue
            if (result != null) {
                prefs.weatherCache = serialize(result)
            }
            val resolved = result ?: cached(prefs)
            main.post {
                listeners.forEach { it(resolved) }
                val callbacks = ArrayList<(Weather?) -> Unit>()
                while (true) callbacks += pendingCallbacks.poll() ?: break
                callbacks.forEach { it(resolved) }
            }
            running.set(false)
            // A forced request may have slipped in between the check above and the reset.
            if (rerunRequested.get() && running.compareAndSet(false, true)) continue
            return
        }
    }

    fun setCity(context: Context, prefs: LauncherPrefs, city: String, callback: (Weather?) -> Unit) {
        prefs.weatherCity = city
        prefs.weatherCache = null
        cityGeneration.incrementAndGet()
        refresh(context, prefs, force = true, callback = callback)
    }

    private fun fetch(context: Context, prefs: LauncherPrefs): Weather? {
        val manual = prefs.weatherCity.trim()
        var lat: Double
        var lon: Double
        var city: String
        if (manual.isNotEmpty()) {
            val geo = geocode(manual) ?: return null
            lat = geo.first
            lon = geo.second
            city = manual
        } else {
            val ip = ipLocation()
            if (ip != null) {
                lat = ip.first
                lon = ip.second
                city = ip.third
            } else {
                val fallback = geocode("London") ?: return null
                lat = fallback.first
                lon = fallback.second
                city = "London"
            }
        }

        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
        val json = getJson(url) ?: return null
        val current = json.optJSONObject("current") ?: return null
        val daily = json.optJSONObject("daily")
        val temp = current.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) return null
        val code = current.optInt("weather_code", -1)
        val isDay = current.optInt("is_day", 1) == 1
        // Round like the current temperature (toInt() truncates -2.7 to -2 and 21.9 to 21).
        val high = (daily?.optJSONArray("temperature_2m_max")?.optDouble(0, temp) ?: temp)
            .takeUnless { it.isNaN() }?.roundToInt() ?: temp.roundToInt()
        val low = (daily?.optJSONArray("temperature_2m_min")?.optDouble(0, temp) ?: temp)
            .takeUnless { it.isNaN() }?.roundToInt() ?: temp.roundToInt()

        val displayCity = city.trim().ifEmpty { "LOCAL" }
        return Weather(
            city = displayCity,
            temperature = temp.roundToInt(),
            condition = conditionFor(code),
            high = high,
            low = low,
            isDay = isDay,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun geocode(name: String): Pair<Double, Double>? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(name, "UTF-8") + "&count=1&language=en&format=json"
        val json = getJson(url) ?: return null
        val results = json.optJSONArray("results") ?: return null
        val first = results.optJSONObject(0) ?: return null
        val lat = first.optDouble("latitude", Double.NaN)
        val lon = first.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        return lat to lon
    }

    private fun ipLocation(): Triple<Double, Double, String>? {
        val json = getJson("https://ipapi.co/json/") ?: return null
        val lat = json.optDouble("latitude", Double.NaN)
        val lon = json.optDouble("longitude", Double.NaN)
        val city = json.optString("city", "").ifBlank { json.optString("region", "LOCAL") }
        if (lat.isNaN() || lon.isNaN()) return null
        return Triple(lat, lon, city)
    }

    private fun getJson(urlString: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "NothingLauncher/9.0 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            JSONObject(body)
        } catch (e: Exception) {
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun conditionFor(code: Int): String = when (code) {
        0 -> "CLEAR"
        1 -> "MOSTLY CLEAR"
        2 -> "PARTLY CLOUDY"
        3 -> "OVERCAST"
        45, 48 -> "FOG"
        51, 53, 55, 56, 57 -> "DRIZZLE"
        61, 63, 65, 66, 67 -> "RAIN"
        71, 73, 75, 77 -> "SNOW"
        80, 81, 82 -> "SHOWERS"
        85, 86 -> "SNOW SHOWERS"
        95, 96, 99 -> "THUNDERSTORM"
        else -> "UNKNOWN"
    }

    private fun serialize(w: Weather): String = JSONObject().apply {
        put("city", w.city)
        put("temp", w.temperature)
        put("condition", w.condition)
        put("high", w.high)
        put("low", w.low)
        put("day", w.isDay)
        put("at", w.updatedAt)
    }.toString()

    private fun parse(raw: String?): Weather? {
        if (raw.isNullOrBlank()) return null
        return try {
            val o = JSONObject(raw)
            if (!o.has("temp")) return null
            Weather(
                city = o.optString("city", "LOCAL"),
                temperature = o.optInt("temp"),
                condition = o.optString("condition", ""),
                high = o.optInt("high"),
                low = o.optInt("low"),
                isDay = o.optBoolean("day", true),
                updatedAt = o.optLong("at", 0L)
            )
        } catch (e: Exception) {
            null
        }
    }
}
