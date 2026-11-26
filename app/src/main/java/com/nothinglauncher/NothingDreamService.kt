package com.nothinglauncher

import android.app.AlarmManager
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
import androidx.core.view.isVisible
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Android System StandBy Screensaver (`DreamService`).
 * Automatically turns any docked or charging phone into an interactive dark OLED Nothing display
 * with burn-in pixel shift, weather, alarm, Notification Digest summary, and 1-tap Desk HUD launch.
 */
class NothingDreamService : DreamService() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { NothingPrefs.get(this) }
    private var attached = false
    private var shiftStep = 0

    private val weatherListener: (WeatherRepository.Weather?) -> Unit = { bindTelemetry() }
    private val mediaListener: (MediaSessionRepository.State) -> Unit = { bindTelemetry() }
    private val digestListener: (NotificationDigestRepository.Snapshot) -> Unit = { bindTelemetry() }

    private val tick = object : Runnable {
        override fun run() {
            if (!attached) return
            bindClock()
            bindTelemetry()
            val canvas = findViewById<View?>(R.id.dreamCanvas)
            if (canvas != null && prefs.deskBurnInShift) {
                val sec = Calendar.getInstance().get(Calendar.SECOND)
                if (sec == 0) {
                    shiftStep = (shiftStep + 1) % 4
                    val d = resources.displayMetrics.density
                    val dx = when (shiftStep) { 1 -> 2f; 3 -> -2f; else -> 0f } * d
                    val dy = when (shiftStep) { 2 -> 2f; 3 -> -2f; else -> 0f } * d
                    canvas.animate().translationX(dx).translationY(dy).setDuration(900L).start()
                }
            }
            handler.postDelayed(this, 1000L)
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

        val cycleFace = View.OnClickListener {
            Tactile.tick(it)
            prefs.clockFaceMode = (prefs.clockFaceMode + 1) % 4
            bindClock()
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
        WeatherRepository.addListener(prefs, weatherListener)
        MediaSessionRepository.addListener(mediaListener)
        NotificationDigestRepository.addListener(this, digestListener)
        bindClock()
        bindTelemetry()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        attached = false
        handler.removeCallbacks(tick)
        WeatherRepository.removeListener(weatherListener)
        MediaSessionRepository.removeListener(mediaListener)
        NotificationDigestRepository.removeListener(digestListener)
        super.onDetachedFromWindow()
    }

    private fun bindClock() {
        val root = findViewById<View?>(R.id.dreamRoot) ?: return
        val accent = AccentTheme.accentColor(this)
        findViewById<View?>(R.id.dreamAccentDot)?.let { AccentTheme.tintDot(it, this) }
        val digital = root.findViewById<DotMatrixClockView>(R.id.dreamClock)
        val analog = root.findViewById<AnalogDotClockView>(R.id.dreamClockAnalog)
        val word = root.findViewById<DotMatrixView>(R.id.dreamClockWord)
        val seconds = root.findViewById<SecondsTickerView>(R.id.dreamSeconds)
        val facePill = root.findViewById<TextView>(R.id.dreamFacePill)
        val date = root.findViewById<TextView>(R.id.dreamDate)

        digital.accentColor = accent
        analog.accentColor = accent
        word.accentColor = accent
        seconds.accentColor = accent

        val mode = prefs.clockFaceMode
        digital.isVisible = mode == 0 || mode == 3
        analog.isVisible = mode == 1
        word.isVisible = mode == 2
        seconds.isVisible = mode == 0 && prefs.showSeconds

        val cal = Calendar.getInstance()
        date.text = DateFormat.format("EEEE, MMM d", cal).toString().uppercase(Locale.getDefault())
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
                val tz = TimeZone.getTimeZone(prefs.worldClockZoneId)
                val wc = Calendar.getInstance(tz)
                val city = tz.id.substringAfterLast('/').replace('_', ' ').uppercase(Locale.US)
                facePill.text = String.format(
                    Locale.US, "%s %02d:%02d", city, wc.get(Calendar.HOUR_OF_DAY), wc.get(Calendar.MINUTE)
                )
            }
        }
    }

    private fun bindTelemetry() {
        val batTv = findViewById<TextView?>(R.id.dreamBatteryPill) ?: return
        val batIntent = try { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Throwable) { null }
        val level = batIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
        val status = batIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0) (level * 100) / scale else 100
        batTv.text = if (charging) "⚡ $pct%" else "$pct%"

        val weather = WeatherRepository.cached(prefs)
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
        findViewById<TextView?>(R.id.dreamWeatherText)?.text =
            listOfNotNull(mediaStr ?: wStr, alarmStr).joinToString("   •   ")
                .ifEmpty { getString(R.string.nt_desk_shift_note) }

        val digest = NotificationDigestRepository.current(this)
        findViewById<TextView?>(R.id.dreamDigestText)?.text = when {
            !digest.permissionGranted -> getString(R.string.nt_desk_title)
            digest.totalCount == 0 -> getString(R.string.nt_digest_empty)
            prefs.focusMode -> getString(R.string.nt_digest_shielded, digest.totalCount)
            else -> digest.entries.firstOrNull()?.let {
                "${it.appLabel.uppercase(Locale.getDefault())}: ${it.title}"
            } ?: getString(R.string.nt_digest_empty)
        }
    }
}
