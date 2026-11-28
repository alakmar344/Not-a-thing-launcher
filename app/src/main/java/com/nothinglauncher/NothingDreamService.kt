package com.nothinglauncher

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Android System StandBy Screensaver (`DreamService`).
 * Turns any docked or charging phone into an interactive dark OLED dot-matrix display
 * with burn-in pixel shift, weather, alarm, Notification Digest summary, and 1-tap Desk HUD launch.
 */
class NothingDreamService : DreamService() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { NothingPrefs.get(this) }
    private var attached = false
    private var shiftStep = 0
    private var lastShiftMinute = -1
    private var lastTelemetryMinute = -1
    private var cachedWeather: WeatherRepository.Weather? = null
    private var cachedBatteryIntent: Intent? = null
    private var receiverRegistered = false
    private val tickCal: Calendar = Calendar.getInstance()
    private val worldCal: Calendar = Calendar.getInstance()
    private val worldDateFmt by lazy(LazyThreadSafetyMode.NONE) {
        java.text.SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
    }
    private var dreamCanvasView: View? = null
    private var dreamAccentDotView: View? = null
    private var dreamDigitalView: DotMatrixClockView? = null
    private var dreamAnalogView: AnalogDotClockView? = null
    private var dreamWordView: DotMatrixView? = null
    private var dreamSecondsView: SecondsTickerView? = null
    private var dreamFacePillView: TextView? = null
    private var dreamDateView: TextView? = null
    private var dreamBatteryPillView: TextView? = null
    private var dreamWeatherTextView: TextView? = null
    private var dreamDigestTextView: TextView? = null

    private val weatherListener: (WeatherRepository.Weather?) -> Unit = { weather ->
        cachedWeather = weather
        bindTelemetry()
    }
    private val mediaListener: (MediaSessionRepository.State) -> Unit = { bindTelemetry() }
    private val digestListener: (NotificationDigestRepository.Snapshot) -> Unit = { bindTelemetry() }

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!attached) return
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                cachedBatteryIntent = intent
                bindBattery(intent)
            } else {
                bindTelemetry()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!attached) return
            tickCal.timeInMillis = System.currentTimeMillis()
            val now = tickCal
            bindClock(now)
            val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            if (minute != lastTelemetryMinute) {
                lastTelemetryMinute = minute
                bindTelemetry()
            }
            val canvas = dreamCanvasView
            if (canvas != null && prefs.deskBurnInShift) {
                if (minute != lastShiftMinute) {
                    lastShiftMinute = minute
                    shiftStep = (shiftStep + 1) % 4
                    val d = resources.displayMetrics.density
                    val dx = when (shiftStep) { 1 -> 2f; 3 -> -2f; else -> 0f } * d
                    val dy = when (shiftStep) { 2 -> 2f; 3 -> -2f; else -> 0f } * d
                    canvas.animate().translationX(dx).translationY(dy).setDuration(900L).start()
                }
            } else if (canvas != null && (canvas.translationX != 0f || canvas.translationY != 0f)) {
                canvas.translationX = 0f
                canvas.translationY = 0f
            }
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = false

        val themed = ContextThemeWrapper(this, R.style.NtTheme_Backdrop_Dark)
        val root = LayoutInflater.from(themed).inflate(R.layout.dream_nothing_standby, null)
        setContentView(root)

        dreamCanvasView = root.findViewById(R.id.dreamCanvas)
        dreamAccentDotView = root.findViewById(R.id.dreamAccentDot)
        dreamDigitalView = root.findViewById(R.id.dreamClock)
        dreamAnalogView = root.findViewById(R.id.dreamClockAnalog)
        dreamWordView = root.findViewById(R.id.dreamClockWord)
        dreamSecondsView = root.findViewById(R.id.dreamSeconds)
        dreamFacePillView = root.findViewById(R.id.dreamFacePill)
        dreamDateView = root.findViewById(R.id.dreamDate)
        dreamBatteryPillView = root.findViewById(R.id.dreamBatteryPill)
        dreamWeatherTextView = root.findViewById(R.id.dreamWeatherText)
        dreamDigestTextView = root.findViewById(R.id.dreamDigestText)

        val cycleFace = View.OnClickListener {
            Tactile.tick(it)
            prefs.clockFaceMode = (prefs.clockFaceMode + 1) % 4
            tickCal.timeInMillis = System.currentTimeMillis()
            bindClock(tickCal)
        }
        root.findViewById<View>(R.id.dreamFacePill).setOnClickListener(cycleFace)
        root.findViewById<View>(R.id.dreamClock).setOnClickListener(cycleFace)
        root.findViewById<View>(R.id.dreamClockAnalog).setOnClickListener(cycleFace)
        root.findViewById<View>(R.id.dreamClockWord).setOnClickListener(cycleFace)
        root.findViewById<View>(R.id.dreamOpenDeskBtn).setOnClickListener {
            Tactile.confirm(it)
            DeskHudActivity.launch(this)
            finish()
        }

        attached = true
        cachedWeather = WeatherRepository.addListener(prefs, weatherListener)
        MediaSessionRepository.start(applicationContext)
        MediaSessionRepository.addListener(mediaListener)
        NotificationDigestRepository.addListener(this, digestListener)
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            }
            ContextCompat.registerReceiver(
                this,
                telemetryReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (_: Throwable) {
            receiverRegistered = false
        }
        val now = Calendar.getInstance()
        lastShiftMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        lastTelemetryMinute = lastShiftMinute
        bindClock(now)
        bindTelemetry()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        attached = false
        handler.removeCallbacks(tick)
        if (receiverRegistered) {
            receiverRegistered = false
            try { unregisterReceiver(telemetryReceiver) } catch (_: Throwable) {}
        }
        WeatherRepository.removeListener(weatherListener)
        MediaSessionRepository.removeListener(mediaListener)
        NotificationDigestRepository.removeListener(digestListener)
        super.onDetachedFromWindow()
    }

    private fun bindClock(cal: Calendar) {
        val digital = dreamDigitalView ?: return
        val analog = dreamAnalogView ?: return
        val word = dreamWordView ?: return
        val seconds = dreamSecondsView ?: return
        val facePill = dreamFacePillView ?: return
        val date = dreamDateView ?: return
        val accent = AccentTheme.accentColor(this)
        dreamAccentDotView?.let { AccentTheme.tintDot(it, this) }
        val fx = com.nothinglauncher.perf.VisualTier.effects(this)
        digital.glow = digital.glow && fx.dotGlow
        word.glow = word.glow && fx.dotGlow

        digital.accentColor = accent
        analog.accentColor = accent
        word.accentColor = accent
        seconds.accentColor = accent

        val mode = prefs.clockFaceMode
        val worldTz = if (mode == 3) TimeZone.getTimeZone(prefs.worldClockZoneId) else null
        digital.timeZone = worldTz
        digital.isVisible = mode == 0 || mode == 3
        analog.isVisible = mode == 1
        word.isVisible = mode == 2
        seconds.isVisible = mode == 0 && prefs.showSeconds

        if (worldTz != null) {
            val fmt = worldDateFmt
            fmt.timeZone = worldTz
            val wCal = worldCal
            wCal.timeZone = worldTz
            wCal.timeInMillis = cal.timeInMillis
            date.text = fmt.format(wCal.time).uppercase(Locale.getDefault())
        } else {
            date.text = DateFormat.format("EEEE, MMM d", cal).toString().uppercase(Locale.getDefault())
        }
        when (mode) {
            0 -> facePill.text = if (DateFormat.is24HourFormat(this)) {
                getString(R.string.format_24h)
            } else {
                DateFormat.format("a", cal).toString().uppercase(Locale.getDefault())
            }
            1 -> facePill.setText(R.string.clock_mode_analog_short)
            2 -> {
                facePill.setText(R.string.clock_mode_word_short)
                val (l1, l2) = WordClockFormatter.format(cal)
                word.text = "$l1\n$l2"
            }
            else -> {
                val tz = worldTz ?: TimeZone.getTimeZone(prefs.worldClockZoneId)
                val city = tz.id.substringAfterLast('/').replace('_', ' ').uppercase(Locale.US)
                val localPat = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
                val localStr = DateFormat.format(localPat, cal).toString()
                facePill.text = "$city · LOCAL $localStr"
            }
        }
    }

    private fun bindBattery(batIntent: Intent?) {
        val batTv = dreamBatteryPillView ?: return
        val resolved = batIntent ?: cachedBatteryIntent ?: try {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.also {
                cachedBatteryIntent = it
            }
        } catch (_: Throwable) {
            null
        }
        val level = resolved?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = resolved?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
        val status = resolved?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0) (level * 100) / scale else 100
        batTv.text = if (charging) "⚡ $pct%" else "$pct%"
    }

    private fun bindTelemetry() {
        if (dreamBatteryPillView == null) return
        bindBattery(null)

        val weather = cachedWeather ?: WeatherRepository.cached(prefs).also { cachedWeather = it }
        val alarm = (getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.nextAlarmClock
        val alarmStr = alarm?.let {
            val pat = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
            "ALARM ${DateFormat.format(pat, it.triggerTime)}"
        }
        val media = MediaSessionRepository.state
        val mediaStr = if (media.permissionGranted && media.hasSession && media.isPlaying && media.title.isNotBlank()) {
            "♫ ${media.title}"
        } else null
        val wStr = weather?.let { "${it.temperature}° ${it.city.uppercase(Locale.getDefault())}" }
        dreamWeatherTextView?.text =
            listOfNotNull(mediaStr ?: wStr, alarmStr).joinToString("   •   ")
                .ifEmpty { getString(R.string.nt_desk_shift_note) }

        val digest = NotificationDigestRepository.current(this)
        dreamDigestTextView?.text = when {
            !digest.permissionGranted -> getString(R.string.nt_desk_title)
            digest.totalCount == 0 -> getString(R.string.nt_digest_empty)
            prefs.focusMode -> getString(R.string.nt_digest_shielded, digest.totalCount)
            else -> digest.entries.firstOrNull()?.let {
                "${it.appLabel.uppercase(Locale.getDefault())}: ${it.title}"
            } ?: getString(R.string.nt_digest_empty)
        }
    }
}
