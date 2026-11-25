package com.nothinglauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Live weather + 12-hour forecast sparkline for the Weather element & Essential Space (Open-Meteo, no API key).
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
    private val running = AtomicBoolean(false)
    private val lastAttempt = LongArray(1)
    private val listeners = CopyOnWriteArraySet<(Weather?) -> Unit>()
    private val pendingCallbacks = ConcurrentLinkedQueue<(Weather?) -> Unit>()
    private val rerunRequested = AtomicBoolean(false)
    private val cityGeneration = AtomicInteger(0)

    private const val CACHE_FRESH_MS = 30 * 60 * 1000L
    private const val RETRY_BACKOFF_MS = 60 * 1000L
    private val IP_LOCATION_URLS = listOf("https://ipwho.is/", "https://ipapi.co/json/")
    private val USER_AGENT = "NotAThingLauncher/${BuildConfig.VERSION_NAME} (Android)"

    fun cached(prefs: NothingPrefs): Weather? = parse(prefs.weatherCache)

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
        pendingCallbacks += callback
        if (!running.compareAndSet(false, true)) {
            if (force) rerunRequested.set(true)
            return
        }
        lastAttempt[0] = now
        val appContext = context.applicationContext
        io.execute { runFetch(appContext, prefs) }
    }

    private fun runFetch(appContext: Context, prefs: NothingPrefs) {
        while (true) {
            rerunRequested.set(false)
            val generation = cityGeneration.get()
            val result = try {
                fetch(prefs)
            } catch (_: Exception) {
                null
            }
            if (generation != cityGeneration.get() || rerunRequested.get()) continue
            if (result != null) {
                prefs.weatherCache = serialize(result)
            }
            val resolved = result ?: cached(prefs)
            running.set(false)
            main.post {
                listeners.forEach { it(resolved) }
                val callbacks = ArrayList<(Weather?) -> Unit>()
                while (true) callbacks += pendingCallbacks.poll() ?: break
                callbacks.forEach { it(resolved) }
            }
            if (rerunRequested.get() && running.compareAndSet(false, true)) continue
            return
        }
    }

    fun setCity(context: Context, prefs: NothingPrefs, city: String, callback: (Weather?) -> Unit) {
        prefs.weatherCity = city
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
            val startIdx = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
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

        val displayCity = city.trim().ifEmpty { "LOCAL" }
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
            if (json.isNull(name)) "" else json.optString(name, "").trim()
        val city = field("city").ifBlank { field("region") }.ifBlank { "LOCAL" }
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
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            JSONObject(body)
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
