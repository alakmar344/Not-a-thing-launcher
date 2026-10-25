package com.nothinglauncher.elements

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.RectF
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.text.InputType
import android.text.StaticLayout
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nothinglauncher.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Real weather from Open-Meteo (no API key) for the device location or a chosen city.
 * Results are cached in the shared element store so several instances share one fetch.
 */
class WeatherElement(context: Context) : ElementView(context) {

    private data class Place(val name: String, val lat: Double, val lon: Double)

    private data class Snapshot(
        val temp: Double,
        val code: Int,
        val isDay: Boolean,
        val max: Double,
        val min: Double,
        val fetchedAt: Long
    ) {
        fun toJson(): String? = try {
            val o = JSONObject()
            o.put("t", temp)
            o.put("c", code)
            o.put("d", isDay)
            if (!max.isNaN()) o.put("hi", max)
            if (!min.isNaN()) o.put("lo", min)
            o.put("ts", fetchedAt)
            o.toString()
        } catch (e: Exception) {
            null
        }

        companion object {
            fun parse(s: String?): Snapshot? {
                if (s.isNullOrEmpty()) return null
                return try {
                    val o = JSONObject(s)
                    Snapshot(
                        o.getDouble("t"),
                        o.optInt("c", -1),
                        o.optBoolean("d", true),
                        o.optDouble("hi", Double.NaN),
                        o.optDouble("lo", Double.NaN),
                        o.getLong("ts")
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private enum class Family { SUN, MOON, PARTLY_DAY, PARTLY_NIGHT, CLOUD, FOG, RAIN, SNOW, STORM }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val shared by lazy { ElementStore(context, ElementStore.SHARED_ID) }

    // Paints (allocated once).
    private val dotOn = ElementUi.dotOn(context)
    private val dotOff = ElementUi.dotOff(context)
    private val accentPaint = ElementUi.accent(accentColor)
    private val label = ElementUi.labelPaint(context, 10f)
    private val labelPrimary = ElementUi.labelPaint(context, 10f, primary = true)
    private val messagePaint = ElementUi.labelPaint(context, 10.5f, primary = true)

    // State.
    private var place: Place? = null
    private var cacheKey: String? = null
    private var snapshot: Snapshot? = null
    private var refreshing = false
    private var problem = PROBLEM_NONE
    private var refreshJob: Job? = null
    private var scopeRef: CoroutineScope? = null

    // Icon raster.
    private var iconFamily: Family? = null
    private val iconLit = Array(ICON_N) { BooleanArray(ICON_N) }
    private val iconAccent = Array(ICON_N) { BooleanArray(ICON_N) }

    // Layout cache, rebuilt only when state or size changes.
    private var dirty = true
    private var showData = false
    private var headerText: String? = null
    private var headerY = 0f
    private val iconRect = RectF()
    private var showIcon = false
    private var tempText = ""
    private var tempX = 0f
    private var tempY = 0f
    private var tempPitch = 0f
    private var tempAccent: Set<Int> = emptySet()
    private val lineTexts = ArrayList<String>(4)
    private val lineX = FloatArray(6)
    private val lineY = FloatArray(6)
    private val linePrimary = BooleanArray(6)
    private var message: StaticLayout? = null
    private var messageTop = 0f
    private var messageH = 0f

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
        contentDescription = context.getString(R.string.eb_weather_cd_unset)
    }

    // ---------------------------------------------------------------- config

    private val mode: String get() = store.getString(K_MODE, null).orEmpty()
    private val configured: Boolean get() = mode == MODE_DEVICE || (mode == MODE_CITY && store.contains(K_LAT))
    private val fahrenheit: Boolean get() = store.getBoolean(K_FAHRENHEIT, defaultFahrenheit())

    private fun defaultFahrenheit(): Boolean =
        Locale.getDefault().country.uppercase(Locale.US) in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun storedPlace(): Place? = when (mode) {
        MODE_CITY -> {
            val lat = store.getString(K_LAT)?.toDoubleOrNull()
            val lon = store.getString(K_LON)?.toDoubleOrNull()
            if (lat != null && lon != null) Place(store.getString(K_CITY_NAME).orEmpty(), lat, lon) else null
        }
        MODE_DEVICE -> {
            val lat = shared.getString(S_DEV_LAT)?.toDoubleOrNull()
            val lon = shared.getString(S_DEV_LON)?.toDoubleOrNull()
            if (lat != null && lon != null) Place(shared.getString(S_DEV_NAME).orEmpty(), lat, lon) else null
        }
        else -> null
    }

    private fun keyFor(p: Place, f: Boolean): String =
        "wx.cache." + String.format(Locale.US, "%.2f,%.2f", p.lat, p.lon) + if (f) ".F" else ".C"

    // ---------------------------------------------------------------- lifecycle

    override fun onBound() {
        loadFromCache()
        updateUi()
    }

    override fun onLiveChanged(live: Boolean) {
        if (live) {
            instances.add(this)
            loadFromCache()
            updateUi()
            if (configured && isStale()) refresh(force = false)
        } else {
            instances.remove(this)
            refreshJob?.cancel()
            refreshJob = null
            if (refreshing) {
                refreshing = false
                updateUi()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scopeRef?.cancel()
        scopeRef = null
    }

    override fun onAccentChanged(color: Int) {
        accentPaint.color = color
        invalidate()
    }

    override fun onSpanChanged(spanX: Int, spanY: Int) {
        dirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dirty = true
    }

    override val hasSettings: Boolean get() = true

    private fun scope(): CoroutineScope =
        scopeRef?.takeIf { it.isActive } ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopeRef = it }

    private fun isStale(): Boolean {
        val s = snapshot ?: return true
        val age = System.currentTimeMillis() - s.fetchedAt
        return age < 0 || age > STALE_MS
    }

    private fun loadFromCache() {
        val p = storedPlace()
        place = p
        if (p == null) {
            cacheKey = null
            snapshot = null
            return
        }
        val key = keyFor(p, fahrenheit)
        cacheKey = key
        snapshot = Snapshot.parse(shared.getString(key))
    }

    private fun onSharedCacheChanged() {
        if (!isLive) return
        val wasProblem = problem
        loadFromCache()
        if (snapshot != null && !isStale() && wasProblem == PROBLEM_OFFLINE) problem = PROBLEM_NONE
        updateUi()
    }

    // ---------------------------------------------------------------- interaction

    private fun onTap() {
        if (!isHostReady) return
        when {
            !configured -> openSettings()
            mode == MODE_DEVICE && !hasLocationPermission() -> requestLocationPermission { refresh(force = true) }
            else -> refresh(force = true)
        }
    }

    private fun requestLocationPermission(after: () -> Unit) {
        try {
            host.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)) { _ -> after() }
        } catch (e: Exception) {
            after()
        }
    }

    // ---------------------------------------------------------------- refresh

    private fun refresh(force: Boolean) {
        if (!configured) {
            updateUi()
            return
        }
        if (refreshJob?.isActive == true) return
        refreshJob = scope().launch {
            val me = coroutineContext[Job]
            refreshing = true
            updateUi()
            try {
                val p = resolvePlace()
                if (p == null) {
                    problem = if (mode == MODE_DEVICE && !hasLocationPermission()) PROBLEM_PERMISSION else PROBLEM_LOCATION
                    return@launch
                }
                val f = fahrenheit
                val key = keyFor(p, f)
                place = p
                cacheKey = key
                snapshot = Snapshot.parse(shared.getString(key))
                if (!force && !isStale()) {
                    problem = PROBLEM_NONE
                    return@launch
                }
                if (!inFlight.add(key)) return@launch // another instance is fetching the same data
                try {
                    val fresh = withContext(Dispatchers.IO) { fetchForecast(p, f) }
                    fresh.toJson()?.let { shared.putString(key, it) }
                    snapshot = fresh
                    problem = PROBLEM_NONE
                    instances.toList().forEach { if (it !== this@WeatherElement) it.onSharedCacheChanged() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problem = PROBLEM_OFFLINE
                } finally {
                    inFlight.remove(key)
                }
            } finally {
                // A cancelled, superseded job must not clear the flag of its replacement.
                if (refreshJob === me || refreshJob?.isActive != true) {
                    refreshing = false
                    updateUi()
                }
            }
        }
    }

    /** Resolves where to fetch weather for; may query the device location (main thread, suspending). */
    private suspend fun resolvePlace(): Place? {
        if (mode == MODE_CITY) return storedPlace()
        if (mode != MODE_DEVICE) return null
        if (!hasLocationPermission()) return null
        val loc = obtainLocation() ?: return storedPlace()
        // Round to ~1 km: enough for weather, kinder to privacy, and lets instances share a cache key.
        val lat = (loc.latitude * 100.0).roundToInt() / 100.0
        val lon = (loc.longitude * 100.0).roundToInt() / 100.0
        val previous = storedPlace()
        val geocoded = try {
            withContext(Dispatchers.IO) { reverseGeocode(lat, lon) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val name = geocoded
            ?: previous?.takeIf { abs(it.lat - lat) < 0.05 && abs(it.lon - lon) < 0.05 && it.name.isNotEmpty() }?.name
            ?: String.format(Locale.US, "%.2f, %.2f", lat, lon)
        shared.putString(S_DEV_LAT, lat.toString())
        shared.putString(S_DEV_LON, lon.toString())
        shared.putString(S_DEV_NAME, name)
        return Place(name, lat, lon)
    }

    @SuppressLint("MissingPermission")
    private suspend fun obtainLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        val b = best
        if (b != null && System.currentTimeMillis() - b.time < LOCATION_MAX_AGE_MS) return b
        val fresh = withTimeoutOrNull(LOCATION_TIMEOUT_MS) { currentLocation(lm) }
        return fresh ?: b
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(lm: LocationManager): Location? {
        val provider = try {
            when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return null

        return suspendCancellableCoroutine { cont ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { try { signal.cancel() } catch (_: Exception) {} }
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) cont.resume(location)
                        }

                        override fun onProviderDisabled(provider: String) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onProviderEnabled(provider: String) {}

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    }
                    cont.invokeOnCancellation { try { lm.removeUpdates(listener) } catch (_: Exception) {} }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /** Blocking; call on Dispatchers.IO. */
    private fun reverseGeocode(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        @Suppress("DEPRECATION")
        val list = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
        val a = list?.firstOrNull() ?: return null
        return listOf(a.locality, a.subAdminArea, a.adminArea, a.countryName).firstOrNull { !it.isNullOrBlank() }
    }

    // ---------------------------------------------------------------- settings

    override fun openSettings() {
        if (!isHostReady) return
        val ctx = host.activity
        val sheet = ElementSheet(ctx, ctx.getString(R.string.eb_weather_settings_title))
        var selMode = mode.ifEmpty { MODE_DEVICE }
        var selF = fahrenheit
        var busy = false
        var restyle: () -> Unit = {}

        sheet.addText(ctx.getString(R.string.eb_weather_location_label))
        val optDevice = sheet.addOption(ctx.getString(R.string.eb_weather_use_device), selMode == MODE_DEVICE) {
            selMode = MODE_DEVICE
            restyle()
        }
        val optCity = sheet.addOption(ctx.getString(R.string.eb_weather_choose_city), selMode == MODE_CITY) {
            selMode = MODE_CITY
            restyle()
        }
        val field = sheet.addField(
            ctx.getString(R.string.eb_weather_city_field),
            if (mode == MODE_CITY) store.getString(K_CITY_NAME) else null,
            ctx.getString(R.string.eb_weather_city_hint),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        )
        field.imeOptions = EditorInfo.IME_ACTION_DONE
        val fieldIndex = sheet.content.indexOfChild(field)
        val fieldLabel: View? = if (fieldIndex > 0) sheet.content.getChildAt(fieldIndex - 1) else null

        sheet.addText(ctx.getString(R.string.eb_weather_units_label))
        val optC = sheet.addOption(ctx.getString(R.string.eb_weather_celsius), !selF) {
            selF = false
            restyle()
        }
        val optF = sheet.addOption(ctx.getString(R.string.eb_weather_fahrenheit), selF) {
            selF = true
            restyle()
        }
        val status = sheet.addText("")
        status.visibility = View.GONE
        sheet.addText(ctx.getString(R.string.eb_weather_source))

        fun showStatus(res: Int) {
            status.text = ctx.getString(res)
            status.visibility = View.VISIBLE
        }

        restyle = {
            styleOption(optDevice, selMode == MODE_DEVICE)
            styleOption(optCity, selMode == MODE_CITY)
            styleOption(optC, !selF)
            styleOption(optF, selF)
            val v = if (selMode == MODE_CITY) View.VISIBLE else View.GONE
            field.visibility = v
            fieldLabel?.visibility = v
            if (selMode != MODE_CITY) status.visibility = View.GONE
        }
        restyle()

        sheet.buttons(ctx.getString(R.string.eb_save), onPrimary = save@{
            if (busy) return@save false
            if (selMode == MODE_DEVICE) {
                applyConfig(MODE_DEVICE, selF)
                if (!hasLocationPermission()) requestLocationPermission { refresh(force = true) }
                return@save true
            }
            val q = field.text?.toString()?.trim().orEmpty()
            if (q.isEmpty()) {
                showStatus(R.string.eb_weather_city_empty)
                return@save false
            }
            val storedName = store.getString(K_CITY_NAME)
            if (mode == MODE_CITY && storedName != null && q.equals(storedName, ignoreCase = true) && store.contains(K_LAT)) {
                applyConfig(MODE_CITY, selF)
                return@save true
            }
            busy = true
            showStatus(R.string.eb_weather_searching)
            scope().launch {
                val found = try {
                    withContext(Dispatchers.IO) { searchCity(q) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    busy = false
                    showStatus(R.string.eb_weather_lookup_failed)
                    return@launch
                }
                busy = false
                if (found == null) {
                    showStatus(R.string.eb_weather_city_not_found)
                    return@launch
                }
                store.putString(K_CITY_NAME, found.name)
                store.putString(K_LAT, found.lat.toString())
                store.putString(K_LON, found.lon.toString())
                applyConfig(MODE_CITY, selF)
                sheet.dismiss()
            }
            false
        })
        sheet.show(host)
    }

    private fun styleOption(tv: TextView, selected: Boolean) {
        tv.setBackgroundResource(if (selected) R.drawable.bg_pill_white else R.drawable.bg_pill)
        tv.setTextColor(ContextCompat.getColor(context, if (selected) R.color.pill_active_text else R.color.text_primary))
    }

    private fun applyConfig(newMode: String, f: Boolean) {
        store.putString(K_MODE, newMode)
        store.putBoolean(K_FAHRENHEIT, f)
        refreshJob?.cancel()
        refreshJob = null
        refreshing = false
        problem = PROBLEM_NONE
        loadFromCache()
        if (newMode == MODE_DEVICE && !hasLocationPermission()) {
            problem = PROBLEM_PERMISSION
            updateUi()
            return
        }
        updateUi()
        refresh(force = false)
    }

    // ---------------------------------------------------------------- network (Dispatchers.IO only)

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = NET_TIMEOUT_MS
            conn.readTimeout = NET_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun coord(v: Double) = String.format(Locale.US, "%.4f", v)

    private fun fetchForecast(p: Place, f: Boolean): Snapshot {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${coord(p.lat)}&longitude=${coord(p.lon)}" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=1" + if (f) "&temperature_unit=fahrenheit" else ""
        val o = JSONObject(httpGet(url))
        val cur = o.getJSONObject("current")
        val daily = o.optJSONObject("daily")
        return Snapshot(
            temp = cur.getDouble("temperature_2m"),
            code = cur.optInt("weather_code", -1),
            isDay = cur.optInt("is_day", 1) == 1,
            max = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN) ?: Double.NaN,
            min = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN) ?: Double.NaN,
            fetchedAt = System.currentTimeMillis()
        )
    }

    private fun searchCity(q: String): Place? {
        val lang = Locale.getDefault().language.ifBlank { "en" }
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(q, "UTF-8") +
            "&count=1&language=" + URLEncoder.encode(lang, "UTF-8") + "&format=json"
        val arr = JSONObject(httpGet(url)).optJSONArray("results") ?: return null
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        return Place(o.optString("name", q).ifBlank { q }, o.getDouble("latitude"), o.getDouble("longitude"))
    }

    // ---------------------------------------------------------------- presentation

    private fun conditionRes(code: Int): Int = when (code) {
        0 -> R.string.eb_wx_clear
        1 -> R.string.eb_wx_mostly_clear
        2 -> R.string.eb_wx_partly_cloudy
        3 -> R.string.eb_wx_overcast
        45, 48 -> R.string.eb_wx_fog
        51, 53, 55 -> R.string.eb_wx_drizzle
        56, 57, 66, 67 -> R.string.eb_wx_freezing_rain
        61, 63, 65 -> R.string.eb_wx_rain
        71, 73, 75, 77, 85, 86 -> R.string.eb_wx_snow
        80, 81, 82 -> R.string.eb_wx_showers
        95, 96, 99 -> R.string.eb_wx_thunderstorm
        else -> R.string.eb_wx_unknown
    }

    private fun familyOf(code: Int, isDay: Boolean): Family = when (code) {
        0, 1 -> if (isDay) Family.SUN else Family.MOON
        2 -> if (isDay) Family.PARTLY_DAY else Family.PARTLY_NIGHT
        45, 48 -> Family.FOG
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Family.RAIN
        71, 73, 75, 77, 85, 86 -> Family.SNOW
        95, 96, 99 -> Family.STORM
        else -> Family.CLOUD
    }

    private fun formatTemp(v: Double): String = if (v.isNaN()) "--" else "${v.roundToInt()}°"

    private fun updateUi() {
        dirty = true
        val s = snapshot
        contentDescription = if (!configured) {
            context.getString(R.string.eb_weather_cd_unset)
        } else if (s != null) {
            context.getString(
                R.string.eb_weather_cd,
                place?.name?.ifEmpty { null } ?: context.getString(R.string.eb_weather_device_location),
                formatTemp(s.temp) + if (fahrenheit) "F" else "C",
                context.getString(conditionRes(s.code))
            )
        } else {
            context.getString(R.string.eb_weather_title)
        }
        invalidate()
    }

    private fun statusText(s: Snapshot): String {
        val time = try {
            android.text.format.DateFormat.getTimeFormat(context).format(Date(s.fetchedAt))
        } catch (e: Exception) {
            ""
        }
        val updated = context.getString(R.string.eb_weather_updated, time)
        return EbDraw.upper(
            when {
                refreshing -> context.getString(R.string.eb_weather_updating)
                problem == PROBLEM_PERMISSION -> context.getString(R.string.eb_weather_tap_to_allow_location)
                problem != PROBLEM_NONE -> context.getString(R.string.eb_weather_offline) + context.getString(R.string.eb_dot_sep) + updated
                else -> updated
            }
        )
    }

    private fun messageRes(): Int = when {
        !configured -> R.string.eb_weather_tap_to_set
        problem == PROBLEM_PERMISSION -> R.string.eb_weather_tap_to_allow_location
        problem == PROBLEM_LOCATION -> R.string.eb_weather_location_unavailable
        problem == PROBLEM_OFFLINE && !refreshing -> R.string.eb_weather_offline_retry
        else -> R.string.eb_weather_loading
    }

    private fun rebuild() {
        dirty = false
        val w = width.toFloat()
        val h = height.toFloat()
        lineTexts.clear()
        message = null
        showIcon = false
        headerText = null
        if (w <= 0f || h <= 0f) return

        val gap = dp(6f)
        val lh = label.textSize * 1.45f
        val showHeader = h >= dp(96f)
        var top = 0f
        val placeName = place?.name?.ifEmpty { null } ?: context.getString(R.string.eb_weather_device_location)
        if (showHeader) {
            headerY = label.textSize
            top = label.textSize + gap * 1.5f
        }

        val s = snapshot
        showData = configured && s != null
        if (!showData || s == null) {
            if (showHeader) headerText = EbDraw.fit(EbDraw.upper(if (configured) placeName else context.getString(R.string.eb_weather_title)), label, w - label.textSize * 1.2f)
            messageTop = top
            messageH = h - top
            val maxLines = floor(messageH / (messagePaint.fontSpacing)).toInt().coerceAtLeast(1)
            message = EbDraw.centredLayout(EbDraw.upper(context.getString(messageRes())), messagePaint, w.toInt(), maxLines)
            return
        }

        if (showHeader) headerText = EbDraw.fit(EbDraw.upper(placeName), label, w - label.textSize * 1.2f)

        val fam = familyOf(s.code, s.isDay)
        if (fam != iconFamily) buildIcon(fam)

        val cond = EbDraw.upper(context.getString(conditionRes(s.code)))
        val hasHl = !s.max.isNaN() && !s.min.isNaN()
        val hl = if (hasHl) context.getString(R.string.eb_weather_high_low, formatTemp(s.max), formatTemp(s.min)) else null
        val status = statusText(s)
        tempText = EbDraw.dotSafe(formatTemp(s.temp))
        val degree = tempText.indexOf('°')
        tempAccent = if (degree >= 0) setOf(degree) else emptySet()

        val footerLines = when {
            h >= dp(150f) -> 2
            h >= dp(110f) -> 1
            else -> 0
        }
        val bottom = h - footerLines * lh
        val mainTop = top
        val mainH = (bottom - top - if (footerLines > 0) gap * 0.5f else 0f).coerceAtLeast(0f)

        // Items that may go next to the temperature, in priority order.
        val optional = ArrayList<String>(4)
        optional.add(cond)
        if (hl != null) optional.add(hl)
        val extra = ArrayList<String>(2)
        if (!showHeader) extra.add(EbDraw.upper(placeName))
        if (footerLines == 0) extra.add(status)

        val sideBySide = w >= mainH * 1.3f
        val leftover = ArrayList<String>(4)
        if (sideBySide) {
            val s0 = min(mainH, w * 0.42f)
            showIcon = s0 >= dp(18f)
            val iconSize = if (showIcon) s0 else 0f
            iconRect.set(0f, mainTop + (mainH - iconSize) / 2f, iconSize, mainTop + (mainH + iconSize) / 2f)
            val rx = if (showIcon) iconSize + gap * 2f else 0f
            val rw = w - rx
            val candidates = ArrayList<String>(optional.size + extra.size)
            candidates.addAll(optional)
            candidates.addAll(extra)
            val maxLines = floor((mainH * 0.5f - gap) / lh).toInt().coerceIn(0, candidates.size)
            val placed = candidates.subList(0, maxLines).toList()
            for (c in candidates.subList(maxLines, candidates.size)) if (c in optional) leftover.add(c)
            val textBlock = if (placed.isNotEmpty()) gap + placed.size * lh else 0f
            tempPitch = ElementUi.fitPitch(tempText, rw, (mainH - textBlock).coerceAtLeast(dp(8f)))
            tempPitch = min(tempPitch, dp(9f))
            val blockH = tempPitch * 7f + textBlock
            val blockTop = mainTop + (mainH - blockH) / 2f
            tempX = rx
            tempY = blockTop
            var y = blockTop + tempPitch * 7f + gap
            for ((i, t) in placed.withIndex()) {
                addLine(EbDraw.fit(t, if (i == 0) labelPrimary else label, rw), rx, y + label.textSize, i == 0)
                y += lh
            }
        } else {
            val s0 = min(w * 0.7f, mainH * 0.5f)
            showIcon = s0 >= dp(18f)
            val iconSize = if (showIcon) s0 else 0f
            iconRect.set((w - iconSize) / 2f, mainTop, (w + iconSize) / 2f, mainTop + iconSize)
            val tempAreaTop = mainTop + if (showIcon) iconSize + gap else 0f
            val tempAreaH = (mainTop + mainH - tempAreaTop).coerceAtLeast(dp(8f))
            tempPitch = min(ElementUi.fitPitch(tempText, w, tempAreaH), dp(9f))
            val tw = ElementUi.dotTextWidth(tempText, tempPitch)
            tempX = (w - tw) / 2f
            tempY = tempAreaTop + (tempAreaH - tempPitch * 7f) / 2f
            leftover.addAll(optional)
        }

        if (footerLines > 0) {
            val footer = ArrayList<String>(footerLines)
            val room = footerLines - 1
            footer.addAll(leftover.take(room))
            footer.add(status)
            var y = h - (footer.size - 1) * lh - label.descent()
            for ((i, t) in footer.withIndex()) {
                val primary = i == 0 && t == cond
                val p = if (primary) labelPrimary else label
                val ft = EbDraw.fit(t, p, w)
                val x = if (sideBySide) 0f else (w - p.measureText(ft)) / 2f
                addLine(ft, x, y, primary)
                y += lh
            }
        }
    }

    private fun addLine(text: String, x: Float, baseline: Float, primary: Boolean) {
        val i = lineTexts.size
        if (i >= lineX.size) return
        lineTexts.add(text)
        lineX[i] = x
        lineY[i] = baseline
        linePrimary[i] = primary
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dirty) rebuild()
        headerText?.let { ElementUi.drawHeader(canvas, 0f, headerY, it, accentPaint, label) }
        val m = message
        if (m != null) {
            EbDraw.drawLayoutCentred(canvas, m, 0f, messageTop, width.toFloat(), messageH)
            return
        }
        if (!showData) return
        if (showIcon) drawIcon(canvas)
        if (tempPitch > 0f) {
            ElementUi.drawDotText(canvas, tempText, tempX, tempY, tempPitch, dotOn, null, accentPaint, tempAccent)
        }
        for (i in lineTexts.indices) {
            canvas.drawText(lineTexts[i], lineX[i], lineY[i], if (linePrimary[i]) labelPrimary else label)
        }
    }

    private fun drawIcon(canvas: Canvas) {
        val pitch = iconRect.width() / ICON_N
        val r = pitch * 0.38f
        for (row in 0 until ICON_N) {
            for (col in 0 until ICON_N) {
                val cx = iconRect.left + (col + 0.5f) * pitch
                val cy = iconRect.top + (row + 0.5f) * pitch
                if (iconLit[row][col]) {
                    canvas.drawCircle(cx, cy, r, if (iconAccent[row][col]) accentPaint else dotOn)
                } else {
                    canvas.drawCircle(cx, cy, r * 0.55f, dotOff)
                }
            }
        }
    }

    // ---------------------------------------------------------------- procedural icons (11×11 dots)

    private fun buildIcon(f: Family) {
        iconFamily = f
        for (r in 0 until ICON_N) for (c in 0 until ICON_N) {
            iconLit[r][c] = false
            iconAccent[r][c] = false
        }
        when (f) {
            Family.SUN -> sun(5f, 5f, 2.3f, longRays = true)
            Family.MOON -> eachCell { c, r -> if (disc(c, r, 5f, 5f, 4.2f) && !disc(c, r, 7.3f, 3.2f, 3.4f)) set(c, r) }
            Family.PARTLY_DAY, Family.PARTLY_NIGHT -> {
                if (f == Family.PARTLY_DAY) sun(3f, 3f, 1.5f, longRays = false)
                else eachCell { c, r -> if (disc(c, r, 3f, 3f, 2.6f) && !disc(c, r, 4.6f, 1.8f, 2.0f)) set(c, r) }
                // Clear a one-dot gap around the cloud, then draw it.
                eachCell { c, r ->
                    var near = false
                    for (dy in -1..1) for (dx in -1..1) if (cloud(c + dx, r + dy, 2.5f)) near = true
                    if (near) iconLit[r][c] = false
                }
                eachCell { c, r -> if (cloud(c, r, 2.5f)) set(c, r) }
            }
            Family.CLOUD -> eachCell { c, r -> if (cloud(c, r, 1.5f)) set(c, r) }
            Family.FOG -> {
                for (c in 1..7) set(c, 2)
                for (c in 3..9) set(c, 4)
                for (c in 1..8) set(c, 6)
                for (c in 2..9) set(c, 8)
            }
            Family.RAIN -> {
                eachCell { c, r -> if (cloud(c, r, -0.5f)) set(c, r) }
                set(3, 7); set(2, 8)
                set(6, 8); set(5, 9)
                set(9, 7); set(8, 8)
                set(4, 10)
            }
            Family.SNOW -> {
                eachCell { c, r -> if (cloud(c, r, -0.5f)) set(c, r) }
                set(2, 8); set(5, 8); set(8, 8)
                set(3, 10); set(7, 10)
            }
            Family.STORM -> {
                eachCell { c, r -> if (cloud(c, r, -0.5f)) set(c, r) }
                set(6, 7, true); set(5, 8, true)
                set(4, 9, true); set(5, 9, true); set(6, 9, true)
                set(5, 10, true)
            }
        }
    }

    private inline fun eachCell(block: (Int, Int) -> Unit) {
        for (r in 0 until ICON_N) for (c in 0 until ICON_N) block(c, r)
    }

    private fun set(c: Int, r: Int, accent: Boolean = false) {
        if (c !in 0 until ICON_N || r !in 0 until ICON_N) return
        iconLit[r][c] = true
        if (accent) iconAccent[r][c] = true
    }

    private fun disc(c: Int, r: Int, cx: Float, cy: Float, radius: Float) = hypot(c - cx, r - cy) <= radius

    private fun cloud(c: Int, r: Int, oy: Float): Boolean {
        val x = c.toFloat()
        val y = r.toFloat()
        if (y > 6.4f + oy) return false
        return hypot(x - 3.2f, y - (4.6f + oy)) <= 2.0f ||
            hypot(x - 6.2f, y - (3.4f + oy)) <= 2.9f ||
            hypot(x - 8.6f, y - (5.0f + oy)) <= 1.8f ||
            (x in 1.4f..9.6f && y >= 4.6f + oy)
    }

    private fun sun(cx: Float, cy: Float, core: Float, longRays: Boolean) {
        eachCell { c, r -> if (disc(c, r, cx, cy, core)) set(c, r) }
        val x = cx.roundToInt()
        val y = cy.roundToInt()
        if (longRays) {
            for (d in 4..5) {
                set(x, y - d); set(x, y + d); set(x - d, y); set(x + d, y)
            }
            for (d in 3..4) {
                set(x - d, y - d); set(x + d, y - d); set(x - d, y + d); set(x + d, y + d)
            }
        } else {
            set(x, y - 3); set(x - 3, y); set(x + 3, y); set(x, y + 3)
            set(x - 2, y - 2); set(x + 2, y - 2); set(x - 2, y + 2)
        }
    }

    companion object {
        private const val ICON_N = 11
        private const val STALE_MS = 30 * 60 * 1000L
        private const val NET_TIMEOUT_MS = 8000
        private const val LOCATION_TIMEOUT_MS = 10_000L
        private const val LOCATION_MAX_AGE_MS = 3 * 60 * 60 * 1000L

        private const val MODE_DEVICE = "device"
        private const val MODE_CITY = "city"

        private const val K_MODE = "wx.mode"
        private const val K_FAHRENHEIT = "wx.fahrenheit"
        private const val K_CITY_NAME = "wx.city.name"
        private const val K_LAT = "wx.city.lat"
        private const val K_LON = "wx.city.lon"

        private const val S_DEV_LAT = "wx.device.lat"
        private const val S_DEV_LON = "wx.device.lon"
        private const val S_DEV_NAME = "wx.device.name"

        private const val PROBLEM_NONE = 0
        private const val PROBLEM_PERMISSION = 1
        private const val PROBLEM_LOCATION = 2
        private const val PROBLEM_OFFLINE = 3

        /** Live instances (main thread only), told when a shared cache entry changes. */
        private val instances = LinkedHashSet<WeatherElement>()

        /** Cache keys currently being fetched (main thread only). */
        private val inFlight = HashSet<String>()
    }
}
