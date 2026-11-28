package com.nothinglauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Live weather + 12-hour forecast sparkline for the Weather element & Dock Space (Open-Meteo, no API key).
 * A manual city can be set from Settings; otherwise the approximate city is resolved from the network's public IP.
 */
object WeatherRepository {

    data class Weather(
        val city: String,
        val temperature: Int,
        val condition: String,
        val high: Int,
        val low: Int,
        val isDay: Boolean,
        val updatedAt: Long,
        val hourlyTemps: List<Int> = emptyList(),
        val hourlyRainProbs: List<Int> = emptyList(),
        val windKmh: Int = 0
    )

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "weather-fetch").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val fetchLock = Any()
    private var running = false
    private var rerunRequested = false
    private val pendingCallbacks = ArrayList<(Weather?) -> Unit>()
    private val lastAttempt = LongArray(1)
    private val listeners = CopyOnWriteArraySet<(Weather?) -> Unit>()
    private val cityGeneration = AtomicInteger(0)

    private const val CACHE_FRESH_MS = 30 * 60 * 1000L
    private const val RETRY_BACKOFF_MS = 60 * 1000L
    private const val MAX_HTTP_BYTES = 1024 * 1024
    private val IP_LOCATION_URLS = listOf("https://ipwho.is/", "https://ipapi.co/json/")
    private val USER_AGENT = "NotAThingLauncher/${BuildConfig.VERSION_NAME} (Android)"

    private class ParsedCache(val raw: String?, val value: Weather?)

    @Volatile
    private var parsedCache: ParsedCache? = null

    fun cached(prefs: NothingPrefs): Weather? {
        val raw = prefs.weatherCache
        val hit = parsedCache
        if (hit != null && hit.raw == raw) return hit.value
        val parsed = parse(raw)
        parsedCache = ParsedCache(raw, parsed)
        return parsed
    }

    fun isFresh(weather: Weather?): Boolean =
        weather != null && System.currentTimeMillis() - weather.updatedAt < CACHE_FRESH_MS

    fun addListener(prefs: NothingPrefs, listener: (Weather?) -> Unit): Weather? {
        listeners += listener
        val cached = cached(prefs)
        main.post { if (listener in listeners) listener(cached) }
        return cached
    }

    fun removeListener(listener: (Weather?) -> Unit) {
        listeners -= listener
    }

    fun hasListeners(): Boolean = listeners.isNotEmpty()

    fun refresh(
        context: Context,
        prefs: NothingPrefs,
        force: Boolean = false,
        callback: (Weather?) -> Unit
    ) {
        val now = System.currentTimeMillis()
        if (!force) {
            val cached = cached(prefs)
            if (isFresh(cached) || now - lastAttempt[0] < RETRY_BACKOFF_MS) {
                callback(cached)
                return
            }
        }
        val shouldStart: Boolean
        synchronized(fetchLock) {
            pendingCallbacks += callback
            if (running) {
                if (force) rerunRequested = true
                shouldStart = false
            } else {
                running = true
                lastAttempt[0] = now
                shouldStart = true
            }
        }
        if (shouldStart) {
            io.execute { runFetch(prefs) }
        }
    }

    private fun runFetch(prefs: NothingPrefs) {
        while (true) {
            val generation: Int
            synchronized(fetchLock) {
                rerunRequested = false
                generation = cityGeneration.get()
            }
            val result = try {
                fetch(prefs)
            } catch (_: Exception) {
                null
            }
            var callbacksBatch: List<(Weather?) -> Unit> = emptyList()
            var retry = false
            synchronized(fetchLock) {
                if (generation != cityGeneration.get() || rerunRequested) {
                    retry = true
                } else {
                    if (result != null) {
                        val encoded = serialize(result)
                        parsedCache = ParsedCache(encoded, result)
                        prefs.weatherCache = encoded
                    }
                    callbacksBatch = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                    running = false
                }
            }
            if (retry) continue
            val resolved = result ?: cached(prefs)
            main.post {
                listeners.forEach { it(resolved) }
                callbacksBatch.forEach { it(resolved) }
            }
            return
        }
    }

    fun setCity(context: Context, prefs: NothingPrefs, city: String, callback: (Weather?) -> Unit) {
        prefs.weatherCity = city.trim().take(80)
        parsedCache = ParsedCache(null, null)
        prefs.weatherCache = null
        cityGeneration.incrementAndGet()
        refresh(context, prefs, force = true, callback = callback)
    }

    private fun fetch(prefs: NothingPrefs): Weather? {
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
            "&current=temperature_2m,weather_code,is_day,wind_speed_10m" +
            "&hourly=temperature_2m,precipitation_probability" +
            "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=2"
        val json = getJson(url) ?: return null
        val current = json.optJSONObject("current") ?: return null
        val daily = json.optJSONObject("daily")
        val temp = current.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) return null
        val code = current.optInt("weather_code", -1)
        val isDay = current.optInt("is_day", 1) == 1
        val wind = current.optDouble("wind_speed_10m", 0.0).takeUnless { it.isNaN() }?.roundToInt() ?: 0
        val high = (daily?.optJSONArray("temperature_2m_max")?.optDouble(0, temp) ?: temp)
            .takeUnless { it.isNaN() }?.roundToInt() ?: temp.roundToInt()
        val low = (daily?.optJSONArray("temperature_2m_min")?.optDouble(0, temp) ?: temp)
            .takeUnless { it.isNaN() }?.roundToInt() ?: temp.roundToInt()

        val hourly = json.optJSONObject("hourly")
        val hTemps = ArrayList<Int>(12)
        val hRain = ArrayList<Int>(12)
        if (hourly != null) {
            val tArr = hourly.optJSONArray("temperature_2m")
            val rArr = hourly.optJSONArray("precipitation_probability")
            val startIdx = resolveHourlyStartIndex(
                json,
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            )
            if (tArr != null) {
                for (i in 0 until 12) {
                    val idx = startIdx + i
                    if (idx < tArr.length()) {
                        val tv = tArr.optDouble(idx, Double.NaN)
                        if (!tv.isNaN()) hTemps += tv.roundToInt()
                    }
                    if (rArr != null && idx < rArr.length()) {
                        hRain += rArr.optInt(idx, 0).coerceIn(0, 100)
                    }
                }
            }
        }

        val displayCity = city.trim().take(80).ifEmpty { "LOCAL" }
        return Weather(
            city = displayCity,
            temperature = temp.roundToInt(),
            condition = conditionFor(code),
            high = high,
            low = low,
            isDay = isDay,
            updatedAt = System.currentTimeMillis(),
            hourlyTemps = hTemps,
            hourlyRainProbs = hRain,
            windKmh = wind
        )
    }

    @VisibleForTesting
    internal fun resolveHourlyStartIndex(json: JSONObject, fallbackHour: Int): Int {
        val currentTime = json.optJSONObject("current")?.optString("time", "").orEmpty()
        val hTimeArr = json.optJSONObject("hourly")?.optJSONArray("time")
        if (currentTime.length >= 13 && hTimeArr != null) {
            val hourPrefix = currentTime.substring(0, 13)
            for (i in 0 until hTimeArr.length()) {
                val entry = hTimeArr.optString(i, "")
                if (entry.startsWith(hourPrefix)) return i
            }
        }
        if (currentTime.length >= 13) {
            val parsedHour = currentTime.substring(11, 13).toIntOrNull()
            if (parsedHour != null && parsedHour in 0..23) return parsedHour
        }
        return fallbackHour.coerceIn(0, 23)
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

    private fun ipLocation(): Triple<Double, Double, String>? =
        IP_LOCATION_URLS.firstNotNullOfOrNull { url -> getJson(url)?.let(::parseIpLocation) }

    private fun parseIpLocation(json: JSONObject): Triple<Double, Double, String>? {
        if (!json.optBoolean("success", true)) return null
        val lat = json.optDouble("latitude", Double.NaN)
        val lon = json.optDouble("longitude", Double.NaN)
        fun field(name: String): String =
            if (json.isNull(name)) "" else json.optString(name, "").trim().take(80)
        val city = field("city").ifBlank { field("region") }.ifBlank { "LOCAL" }
        if (lat.isNaN() || lon.isNaN()) return null
        return Triple(lat, lon, city)
    }

    private fun getJson(urlString: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            if (!url.protocol.equals("https", ignoreCase = true)) return null
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { input ->
                val buf = ByteArray(8192)
                val out = ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_HTTP_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
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
        put("wind", w.windKmh)
        put("hTemps", JSONArray(w.hourlyTemps))
        put("hRain", JSONArray(w.hourlyRainProbs))
    }.toString()

    private fun parse(raw: String?): Weather? {
        if (raw.isNullOrBlank()) return null
        return try {
            val o = JSONObject(raw)
            if (!o.has("temp")) return null
            val hTemps = o.optJSONArray("hTemps")?.let { arr ->
                (0 until arr.length()).map { arr.optInt(it) }
            }.orEmpty()
            val hRain = o.optJSONArray("hRain")?.let { arr ->
                (0 until arr.length()).map { arr.optInt(it) }
            }.orEmpty()
            Weather(
                city = o.optString("city", "").ifBlank { "LOCAL" },
                temperature = o.optInt("temp"),
                condition = o.optString("condition", ""),
                high = o.optInt("high"),
                low = o.optInt("low"),
                isDay = o.optBoolean("day", true),
                updatedAt = o.optLong("at", 0L),
                hourlyTemps = hTemps,
                hourlyRainProbs = hRain,
                windKmh = o.optInt("wind", 0)
            )
        } catch (_: Exception) {
            null
        }
    }
}
