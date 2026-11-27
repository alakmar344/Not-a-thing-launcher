package com.nothinglauncher

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.nothinglauncher.perf.VisualTier
import com.nothinglauncher.widgets.HabitMatrixWidget
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Full-screen StandBy / Glyph Desk HUD mode.
 * Supports 4 stages (COCKPIT bento, CLOCK theatre, FOCUS zen dial, and procedural GLYPH matrix),
 * responsive phone & 2-column tablet layouts, hardware keyboard navigation, and optional burn-in pixel shift.
 */
class DeskHudActivity : AppCompatActivity() {

    enum class Mode(val prefValue: Int) {
        COCKPIT(0),
        CLOCK(1),
        FOCUS(2),
        GLYPH(3);

        companion object {
            fun fromPref(value: Int): Mode = entries.firstOrNull { it.prefValue == value } ?: COCKPIT
        }
    }

    private val prefs by lazy { NothingPrefs.get(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var shiftStep = 0
    private var lastShiftMinute = -1
    private var lastMetaMinute = -1
    private var cachedEventStr: String? = null
    private var cachedAlarmStr: String? = null

    private lateinit var deskRoot: View
    private lateinit var deskCanvas: View
    private lateinit var clockView: DotMatrixClockView
    private lateinit var analogView: AnalogDotClockView
    private lateinit var wordView: DotMatrixView
    private lateinit var modePill: TextView
    private lateinit var secondsView: SecondsTickerView
    private lateinit var dateView: TextView
    private lateinit var nextBanner: TextView
    private lateinit var batteryView: BatteryDotsView
    private lateinit var batteryBolt: View
    private lateinit var weatherView: WeatherDotsView
    private lateinit var awakeBtn: TextView
    private lateinit var glyphCanvas: GlyphMatrixCanvasView
    private var habitBtn: TextView? = null

    private lateinit var modeButtons: Map<Mode, TextView>

    private val mediaListener: (MediaSessionRepository.State) -> Unit = { bindMedia(it) }
    private val weatherListener: (WeatherRepository.Weather?) -> Unit = { weatherView.bind(it) }
    private val timerListener: () -> Unit = { bindTimer() }
    private val torchListener: (Boolean) -> Unit = { bindToggles() }
    private val soundscapeListener: (FocusSoundEngine.Profile) -> Unit = {
        bindSoundscape()
        bindMedia(MediaSessionRepository.state)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = Calendar.getInstance()
            bindTimer()
            bindClockMeta(now)
            if (prefs.deskBurnInShift) {
                val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                if (minute != lastShiftMinute) {
                    lastShiftMinute = minute
                    shiftStep = (shiftStep + 1) % 4
                    val d = resources.displayMetrics.density
                    val dx = when (shiftStep) { 1 -> 2f; 3 -> -2f; else -> 0f } * d
                    val dy = when (shiftStep) { 2 -> 2f; 3 -> -2f; else -> 0f } * d
                    deskCanvas.animate().translationX(dx).translationY(dy).setDuration(900L).start()
                }
            } else if (deskCanvas.translationX != 0f || deskCanvas.translationY != 0f) {
                deskCanvas.translationX = 0f
                deskCanvas.translationY = 0f
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_desk_hud)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        deskRoot = findViewById(R.id.deskRoot)
        deskCanvas = findViewById(R.id.deskCanvas)
        deskRoot.background = DotGridDrawable.forTier(this)

        val baseLeft = deskCanvas.paddingLeft
        val baseTop = deskCanvas.paddingTop
        val baseRight = deskCanvas.paddingRight
        val baseBottom = deskCanvas.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(deskRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            deskCanvas.updatePadding(
                left = baseLeft + bars.left,
                top = baseTop + bars.top,
                right = baseRight + bars.right,
                bottom = baseBottom + bars.bottom
            )
            insets
        }

        clockView = findViewById(R.id.deskClock)
        analogView = findViewById(R.id.deskClockAnalog)
        wordView = findViewById(R.id.deskClockWord)
        modePill = findViewById(R.id.deskClockModePill)
        secondsView = findViewById(R.id.deskSeconds)
        dateView = findViewById(R.id.deskDate)
        nextBanner = findViewById(R.id.deskNextBanner)
        batteryView = findViewById(R.id.deskBattery)
        batteryBolt = findViewById(R.id.deskBatteryBolt)
        weatherView = findViewById(R.id.deskWeather)
        awakeBtn = findViewById(R.id.deskAwakeBtn)
        glyphCanvas = findViewById(R.id.deskGlyphCanvas)
        habitBtn = findViewById(R.id.deskHabitBtn)

        modeButtons = mapOf(
            Mode.COCKPIT to findViewById(R.id.deskModeCockpit),
            Mode.CLOCK to findViewById(R.id.deskModeClock),
            Mode.FOCUS to findViewById(R.id.deskModeFocus),
            Mode.GLYPH to findViewById(R.id.deskModeGlyph)
        )

        val fx = VisualTier.effects(this)
        clockView.glow = fx.dotGlow
        wordView.glow = fx.dotGlow
        batteryBolt.isVisible = batteryView.charging
        batteryView.onStateChanged = { _, charging -> batteryBolt.isVisible = charging }

        wireControls()
        applyDeskMode(Mode.fromPref(prefs.deskMode))
        bindAll()
    }

    override fun onStart() {
        super.onStart()
        running = true
        applyAwakeFlag()
        MediaSessionRepository.start(applicationContext)
        MediaSessionRepository.addListener(mediaListener)
        WeatherRepository.addListener(prefs, weatherListener)
        WeatherRepository.refresh(this, prefs) { }
        FocusTimerRepository.addListener(timerListener)
        HardwareControlRepository.addTorchListener(this, torchListener)
        FocusSoundEngine.addListener(soundscapeListener)
        bindAll()
        handler.post(tickRunnable)
    }

    override fun onStop() {
        running = false
        handler.removeCallbacks(tickRunnable)
        MediaSessionRepository.removeListener(mediaListener)
        WeatherRepository.removeListener(weatherListener)
        FocusTimerRepository.removeListener(timerListener)
        HardwareControlRepository.removeTorchListener(torchListener)
        FocusSoundEngine.removeListener(soundscapeListener)
        super.onStop()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val current = Mode.fromPref(prefs.deskMode)
        return when (keyCode) {
            KeyEvent.KEYCODE_1 -> { selectDeskMode(Mode.COCKPIT); true }
            KeyEvent.KEYCODE_2 -> { selectDeskMode(Mode.CLOCK); true }
            KeyEvent.KEYCODE_3 -> { selectDeskMode(Mode.FOCUS); true }
            KeyEvent.KEYCODE_4 -> { selectDeskMode(Mode.GLYPH); true }
            KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_TAB -> {
                val next = Mode.entries[(current.ordinal + 1) % Mode.entries.size]
                selectDeskMode(next)
                true
            }
            KeyEvent.KEYCODE_C -> {
                prefs.clockFaceMode = (prefs.clockFaceMode + 1) % 4
                bindClockMeta(Calendar.getInstance())
                true
            }
            KeyEvent.KEYCODE_F -> {
                prefs.focusMode = !prefs.focusMode
                deskRoot.background = DotGridDrawable.forTier(this)
                bindAll()
                true
            }
            KeyEvent.KEYCODE_H -> {
                HabitMatrixWidget.toggleToday(prefs)
                bindToggles()
                true
            }
            KeyEvent.KEYCODE_SPACE -> {
                val media = MediaSessionRepository.state
                if (current == Mode.FOCUS || !(media.permissionGranted && media.hasSession)) {
                    FocusTimerRepository.toggleStartPause(this)
                    bindTimer()
                } else {
                    MediaSessionRepository.playPause()
                }
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    private fun selectDeskMode(mode: Mode) {
        prefs.deskMode = mode.prefValue
        applyDeskMode(mode)
        bindTopBar()
        bindClockMeta(Calendar.getInstance())
        if (mode == Mode.GLYPH) {
            glyphCanvas.triggerRipple(0.5f, 0.5f)
        }
    }

    private fun wireControls() {
        modeButtons.forEach { (mode, btn) ->
            Tactile.press(btn)
            btn.setOnClickListener {
                Tactile.tick(it)
                selectDeskMode(mode)
            }
        }

        findViewById<View>(R.id.deskExitBtn).apply {
            Tactile.press(this)
            setOnClickListener {
                Tactile.tick(it)
                finish()
            }
        }
        awakeBtn.apply {
            Tactile.press(this)
            setOnClickListener {
                Tactile.confirm(it)
                prefs.deskKeepAwake = !prefs.deskKeepAwake
                applyAwakeFlag()
                bindTopBar()
            }
        }
        val cycleFace = View.OnClickListener {
            Tactile.tick(it)
            prefs.clockFaceMode = (prefs.clockFaceMode + 1) % 4
            bindClockMeta(Calendar.getInstance())
        }
        modePill.setOnClickListener(cycleFace)
        clockView.setOnClickListener(cycleFace)
        analogView.setOnClickListener(cycleFace)
        wordView.setOnClickListener(cycleFace)

        findViewById<View>(R.id.deskTorchBtn).apply {
            Tactile.press(this)
            setOnClickListener {
                Tactile.confirm(it)
                HardwareControlRepository.toggleTorch(this@DeskHudActivity)
                bindToggles()
            }
        }
        findViewById<View>(R.id.deskFocusBtn).apply {
            Tactile.press(this)
            setOnClickListener {
                Tactile.confirm(it)
                prefs.focusMode = !prefs.focusMode
                deskRoot.background = DotGridDrawable.forTier(this@DeskHudActivity)
                bindAll()
            }
        }
        findViewById<View>(R.id.deskSoundBtn).apply {
            Tactile.press(this)
            setOnClickListener {
                Tactile.confirm(it)
                HardwareControlRepository.cycleRingerOrOpenSettings(this@DeskHudActivity)
                bindToggles()
            }
        }
        habitBtn?.apply {
            Tactile.press(this)
            setOnClickListener {
                Tactile.confirm(it)
                HabitMatrixWidget.toggleToday(prefs)
                bindToggles()
            }
        }

        findViewById<View>(R.id.deskMediaPrev).setOnClickListener {
            Tactile.tick(it)
            MediaSessionRepository.previous()
        }
        findViewById<View>(R.id.deskMediaPlayPause).setOnClickListener {
            Tactile.confirm(it)
            MediaSessionRepository.playPause()
        }
        findViewById<View>(R.id.deskMediaNext).setOnClickListener {
            Tactile.tick(it)
            MediaSessionRepository.next()
        }

        findViewById<View>(R.id.deskGlyphMediaPill)?.apply {
            Tactile.press(this)
            setOnClickListener {
                Tactile.confirm(it)
                glyphCanvas.triggerRipple(0.28f, 0.85f)
                MediaSessionRepository.playPause()
            }
        }
        findViewById<View>(R.id.deskGlyphTimerPill)?.apply {
            Tactile.press(this)
            setOnClickListener {
                Tactile.confirm(it)
                glyphCanvas.triggerRipple(0.72f, 0.85f)
                FocusTimerRepository.toggleStartPause(this@DeskHudActivity)
                bindTimer()
            }
        }

        findViewById<View>(R.id.deskTimerRing).apply {
            setOnClickListener {
                Tactile.confirm(it)
                FocusTimerRepository.toggleStartPause(this@DeskHudActivity)
                bindTimer()
            }
            setOnLongClickListener {
                Tactile.tick(it)
                FocusTimerRepository.toggleMode(this@DeskHudActivity)
                bindTimer()
                true
            }
        }
        findViewById<View>(R.id.deskTimerMode).setOnClickListener {
            Tactile.tick(it)
            FocusTimerRepository.toggleMode(this@DeskHudActivity)
            bindTimer()
        }
        findViewById<View>(R.id.deskTimerAction).setOnClickListener {
            Tactile.tick(it)
            val st = FocusTimerRepository.snapshot(this@DeskHudActivity)
            if (st.running || st.isStopwatch) FocusTimerRepository.reset(this@DeskHudActivity)
            else FocusTimerRepository.cyclePresetOrAdd(this@DeskHudActivity)
            bindTimer()
        }
        findViewById<View?>(R.id.deskSoundscapeBtn)?.apply {
            Tactile.press(this)
            setOnClickListener {
                Tactile.confirm(it)
                val next = FocusSoundEngine.cycle()
                if (next != FocusSoundEngine.Profile.OFF) {
                    prefs.soundscapePreset = next.ordinal
                }
                if (Mode.fromPref(prefs.deskMode) == Mode.GLYPH) {
                    glyphCanvas.triggerRipple(0.5f, 0.5f)
                }
                bindSoundscape()
                bindMedia(MediaSessionRepository.state)
            }
        }
        val noteClick = View.OnClickListener {
            Tactile.tick(it)
            Sheets.showTextInput(
                this,
                getString(R.string.essential_note_title),
                getString(R.string.quick_note_hint),
                prefs.essentialNote,
                maxLength = 180
            ) { updated ->
                prefs.essentialNote = updated
                bindNote()
            }
        }
        findViewById<View?>(R.id.deskNoteCard)?.setOnClickListener(noteClick)
        findViewById<View>(R.id.deskNotePreview).setOnClickListener(noteClick)
    }

    private fun applyDeskMode(mode: Mode) {
        val cockpitStage = findViewById<View>(R.id.deskCockpitStage)
        val glyphStage = findViewById<View>(R.id.deskGlyphStage)
        cockpitStage.isVisible = mode != Mode.GLYPH
        glyphStage.isVisible = mode == Mode.GLYPH

        val d = resources.displayMetrics.density
        val leftCol = findViewById<View?>(R.id.deskLeftCol)
        val rightCol = findViewById<View?>(R.id.deskRightCol)
        val colSpacer = findViewById<View?>(R.id.deskColSpacer)
        val clockCard = findViewById<View>(R.id.deskClockCard)
        val weatherCard = findViewById<View>(R.id.deskWeatherCard)
        val batteryCard = findViewById<View>(R.id.deskBatteryCard)
        val midSpacer = findViewById<View>(R.id.deskMidSpacer)
        val timerCard = findViewById<View>(R.id.deskTimerCard)
        val mediaCard = findViewById<View>(R.id.deskMediaCard)
        val togglesRow = findViewById<View>(R.id.deskTogglesRow)
        val notePreview = findViewById<View>(R.id.deskNotePreview)

        if (leftCol != null && rightCol != null) {
            // Dual-column tablet / foldable configuration
            val leftBottom = findViewById<View>(R.id.deskLeftBottomRow)
            when (mode) {
                Mode.COCKPIT, Mode.GLYPH -> {
                    leftCol.isVisible = true
                    colSpacer?.isVisible = true
                    rightCol.isVisible = true
                    setHorizontalWeight(leftCol, 1.35f)
                    setHorizontalWeight(rightCol, 1f)
                    clockCard.isVisible = true
                    setVerticalWeight(clockCard, 1.5f)
                    leftBottom.isVisible = true
                    setVerticalWeight(leftBottom, 1f)
                    batteryCard.isVisible = true
                    midSpacer.isVisible = true
                    weatherCard.isVisible = true
                    setVerticalWeight(weatherCard, 0.85f)
                    timerCard.isVisible = true
                    setVerticalWeight(timerCard, 1.05f)
                    mediaCard.isVisible = true
                    togglesRow.isVisible = true
                    notePreview.isVisible = true
                }
                Mode.CLOCK -> {
                    leftCol.isVisible = true
                    setHorizontalWeight(leftCol, 1f)
                    colSpacer?.isVisible = false
                    rightCol.isVisible = false
                    clockCard.isVisible = true
                    setVerticalWeight(clockCard, 1f)
                    leftBottom.isVisible = false
                }
                Mode.FOCUS -> {
                    leftCol.isVisible = true
                    colSpacer?.isVisible = true
                    rightCol.isVisible = true
                    setHorizontalWeight(leftCol, 1f)
                    setHorizontalWeight(rightCol, 1.25f)
                    clockCard.isVisible = false
                    leftBottom.isVisible = true
                    setVerticalWeight(leftBottom, 1f)
                    batteryCard.isVisible = false
                    midSpacer.isVisible = false
                    weatherCard.isVisible = false
                    mediaCard.isVisible = false
                    timerCard.isVisible = true
                    setVerticalWeight(timerCard, 1f)
                    togglesRow.isVisible = true
                    notePreview.isVisible = true
                }
            }
        } else {
            // Single-column phone configuration
            val clockFrame = findViewById<View>(R.id.deskClockFrame)
            val midRow = findViewById<View>(R.id.deskMidRow)
            when (mode) {
                Mode.COCKPIT, Mode.GLYPH -> {
                    clockCard.isVisible = true
                    clockCard.layoutParams = (clockCard.layoutParams as LinearLayout.LayoutParams).apply {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                        weight = 0f
                    }
                    clockFrame.layoutParams = (clockFrame.layoutParams as LinearLayout.LayoutParams).apply {
                        height = (128f * d).toInt()
                        weight = 0f
                    }
                    weatherCard.isVisible = true
                    midRow.isVisible = true
                    midRow.layoutParams = (midRow.layoutParams as LinearLayout.LayoutParams).apply {
                        height = (176f * d).toInt()
                    }
                    batteryCard.isVisible = true
                    midSpacer.isVisible = true
                    timerCard.isVisible = true
                    mediaCard.isVisible = true
                    togglesRow.isVisible = true
                    notePreview.isVisible = true
                }
                Mode.CLOCK -> {
                    clockCard.isVisible = true
                    clockCard.layoutParams = (clockCard.layoutParams as LinearLayout.LayoutParams).apply {
                        height = 0
                        weight = 1f
                    }
                    clockFrame.layoutParams = (clockFrame.layoutParams as LinearLayout.LayoutParams).apply {
                        height = 0
                        weight = 1f
                    }
                    weatherCard.isVisible = false
                    midRow.isVisible = false
                    mediaCard.isVisible = false
                    togglesRow.isVisible = false
                    notePreview.isVisible = false
                }
                Mode.FOCUS -> {
                    clockCard.isVisible = false
                    weatherCard.isVisible = false
                    midRow.isVisible = true
                    midRow.layoutParams = (midRow.layoutParams as LinearLayout.LayoutParams).apply {
                        height = (264f * d).toInt()
                    }
                    batteryCard.isVisible = false
                    midSpacer.isVisible = false
                    timerCard.isVisible = true
                    mediaCard.isVisible = false
                    togglesRow.isVisible = true
                    notePreview.isVisible = true
                }
            }
        }
    }

    private fun setHorizontalWeight(view: View, weight: Float) {
        val lp = view.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.width = 0
        lp.weight = weight
        view.layoutParams = lp
    }

    private fun setVerticalWeight(view: View, weight: Float) {
        val lp = view.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.height = 0
        lp.weight = weight
        view.layoutParams = lp
    }

    private fun applyAwakeFlag() {
        if (prefs.deskKeepAwake || prefs.caffeineEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun bindAll() {
        val accent = AccentTheme.accentColor(this)
        AccentTheme.tintDot(findViewById(R.id.deskAccentDot), this)
        AccentTheme.tintDot(findViewById(R.id.deskMediaDot), this)
        AccentTheme.tintDot(findViewById(R.id.deskTimerDot), this)
        findViewById<View?>(R.id.deskNoteDot)?.let { AccentTheme.tintDot(it, this) }
        findViewById<View?>(R.id.deskGlyphDot)?.let { AccentTheme.tintDot(it, this) }
        clockView.accentColor = accent
        analogView.accentColor = accent
        wordView.accentColor = accent
        secondsView.accentColor = accent
        batteryView.accentColor = accent
        weatherView.accentColor = accent
        glyphCanvas.accentColor = accent

        bindTopBar()
        bindClockMeta(Calendar.getInstance())
        weatherView.bind(WeatherRepository.cached(prefs))
        bindMedia(MediaSessionRepository.state)
        bindToggles()
        bindTimer()
        bindSoundscape()
        bindNote()
    }

    private fun bindTopBar() {
        val activeAwake = prefs.deskKeepAwake
        awakeBtn.setBackgroundResource(if (activeAwake) R.drawable.bg_pill_white else R.drawable.bg_pill_outline)
        awakeBtn.setTextColor(getColor(if (activeAwake) R.color.pill_active_text else R.color.text_secondary))

        val currentMode = Mode.fromPref(prefs.deskMode)
        modeButtons.forEach { (mode, tv) ->
            val selected = mode == currentMode
            tv.setBackgroundResource(if (selected) R.drawable.bg_pill_white else R.drawable.bg_pill_outline)
            tv.setTextColor(getColor(if (selected) R.color.pill_active_text else R.color.text_secondary))
        }
    }

    private fun bindClockMeta(cal: Calendar) {
        val mode = prefs.clockFaceMode
        val worldTz = if (mode == 3) TimeZone.getTimeZone(prefs.worldClockZoneId) else null
        clockView.timeZone = worldTz
        clockView.isVisible = mode == 0 || mode == 3
        analogView.isVisible = mode == 1
        wordView.isVisible = mode == 2
        secondsView.isVisible = (mode == 0) && prefs.showSeconds

        val timePat = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        val timeText = DateFormat.format(timePat, cal).toString()
        findViewById<TextView?>(R.id.deskGlyphTime)?.text = timeText

        val minuteKey = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) + mode * 1440
        if (minuteKey != lastMetaMinute) {
            lastMetaMinute = minuteKey
            if (worldTz != null) {
                val fmt = java.text.SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).apply {
                    timeZone = worldTz
                }
                dateView.text = fmt.format(Calendar.getInstance(worldTz).time)
            } else {
                dateView.text = DateFormat.format(
                    DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), cal
                )
            }
            val alarm = (getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.nextAlarmClock
            cachedAlarmStr = alarm?.let {
                val pat = if (DateFormat.is24HourFormat(this)) "EEE HH:mm" else "EEE h:mm a"
                "ALARM ${DateFormat.format(pat, it.triggerTime)}"
            }
            cachedEventStr = HardwareControlRepository.queryNextCalendarEvent(this)?.let {
                "${it.timeLabel} · ${it.title}"
            }
        }
        when (mode) {
            0 -> modePill.text = if (DateFormat.is24HourFormat(this)) {
                getString(R.string.format_24h)
            } else {
                DateFormat.format("a", cal).toString().uppercase(Locale.getDefault())
            }
            1 -> modePill.setText(R.string.clock_mode_analog_short)
            2 -> {
                modePill.setText(R.string.clock_mode_word_short)
                val (l1, l2) = WordClockFormatter.format(cal)
                wordView.text = "$l1\n$l2"
            }
            else -> {
                val tz = worldTz ?: TimeZone.getTimeZone(prefs.worldClockZoneId)
                val city = tz.id.substringAfterLast('/').replace('_', ' ').uppercase(Locale.US)
                modePill.text = "$city · LOCAL $timeText"
            }
        }

        val bannerParts = mutableListOf<String>()
        if (Mode.fromPref(prefs.deskMode) == Mode.FOCUS) {
            bannerParts.add(timeText)
        }
        cachedEventStr?.let { bannerParts.add(it) }
        cachedAlarmStr?.let { bannerParts.add(it) }
        nextBanner.text = bannerParts.joinToString("   •   ").ifEmpty { getString(R.string.nt_desk_shift_note) }
    }

    private fun bindMedia(state: MediaSessionRepository.State) {
        val canControl = state.permissionGranted && state.hasSession
        val accent = AccentTheme.accentColor(this)
        val bars = findViewById<MediaDotsView>(R.id.deskMediaBars)
        val art = findViewById<MediaArtDotsView>(R.id.deskMediaArt)
        bars.accentColor = accent
        art.accentColor = accent
        bars.isPlaying = canControl && state.isPlaying
        glyphCanvas.isAudioActive = (canControl && state.isPlaying) || FocusSoundEngine.isPlaying()

        findViewById<TextView>(R.id.deskMediaRoute).apply {
            text = if (canControl) state.outputRoute else ""
            isVisible = canControl && state.outputRoute.isNotBlank()
        }
        findViewById<TextView>(R.id.deskMediaTitle).text = when {
            !state.permissionGranted -> getString(R.string.media_permission_needed)
            !state.hasSession -> getString(R.string.media_no_session)
            state.title.isBlank() -> getString(R.string.media_connect_hint)
            else -> state.title
        }
        findViewById<TextView>(R.id.deskMediaArtist).apply {
            text = if (canControl) state.artist.uppercase(Locale.getDefault()) else ""
            isVisible = canControl && state.artist.isNotBlank()
        }
        val showArt = VisualTier.current(this) != VisualTier.Tier.LITE && canControl && state.artHalftone != null
        art.isVisible = showArt
        if (showArt) art.matrix = state.artHalftone

        val playPause = findViewById<ImageView>(R.id.deskMediaPlayPause)
        playPause.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        val prev = findViewById<View>(R.id.deskMediaPrev)
        val next = findViewById<View>(R.id.deskMediaNext)
        listOf(playPause, prev, next).forEach {
            it.isEnabled = canControl
            it.alpha = if (canControl) 1f else 0.4f
        }

        findViewById<TextView?>(R.id.deskGlyphMediaPill)?.apply {
            text = if (canControl && state.title.isNotBlank()) {
                val glyph = if (state.isPlaying) "❚❚ " else "▶ "
                "$glyph${state.title.uppercase(Locale.getDefault())}"
            } else {
                getString(R.string.media_label)
            }
            setTextColor(getColor(if (canControl && state.isPlaying) R.color.text_primary else R.color.text_secondary))
        }
    }

    private fun bindToggles() {
        val accent = AccentTheme.accentColor(this)
        val normal = getColor(R.color.text_primary)
        val muted = getColor(R.color.text_secondary)

        fun style(id: Int, active: Boolean, labelOverride: Int? = null, textOverride: String? = null) {
            val tv = findViewById<TextView?>(id) ?: return
            tv.setBackgroundResource(if (active) R.drawable.bg_tile_active else R.drawable.bg_tile_dark)
            tv.setTextColor(if (active) getColor(R.color.pill_active_text) else muted)
            if (textOverride != null) {
                tv.text = textOverride
            } else if (labelOverride != null) {
                tv.setText(labelOverride)
            }
            tv.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(if (active) accent else normal)
        }

        style(R.id.deskTorchBtn, HardwareControlRepository.torchOn)
        style(R.id.deskFocusBtn, prefs.focusMode)
        val ringer = HardwareControlRepository.ringerMode(this)
        style(
            R.id.deskSoundBtn,
            ringer != AudioManager.RINGER_MODE_NORMAL,
            when (ringer) {
                AudioManager.RINGER_MODE_VIBRATE -> R.string.sound_vibrate
                AudioManager.RINGER_MODE_SILENT -> R.string.sound_silent
                else -> R.string.toggle_sound
            }
        )
        val habitDone = HabitMatrixWidget.isDoneToday(prefs)
        val streak = HabitMatrixWidget.computeStreak(prefs.habitDates)
        val habitLabel = if (habitDone) {
            if (streak > 0) "${getString(R.string.habit_done)} ${streak}D" else getString(R.string.habit_done)
        } else {
            getString(R.string.habit_log)
        }
        style(R.id.deskHabitBtn, habitDone, textOverride = habitLabel)
    }

    private fun bindTimer() {
        val st = FocusTimerRepository.snapshot(this)
        val ring = findViewById<DotRingGaugeView>(R.id.deskTimerRing)
        ring.accentColor = AccentTheme.accentColor(this)
        ring.highlightActive = st.running
        ring.progress01 = st.progress01
        ring.centerText = st.display
        ring.subtitle = when {
            st.finished -> getString(R.string.timer_done)
            st.running -> getString(R.string.timer_running)
            else -> getString(R.string.timer_tap)
        }
        glyphCanvas.timerProgress01 = st.progress01
        glyphCanvas.timerRunning = st.running

        findViewById<TextView>(R.id.deskTimerMode).setText(
            if (st.isStopwatch) R.string.stopwatch_label else R.string.timer_label
        )
        findViewById<TextView>(R.id.deskTimerAction).text = if (st.running || st.isStopwatch) {
            getString(R.string.timer_reset)
        } else {
            "${st.durationMinutes}M"
        }
        findViewById<TextView?>(R.id.deskGlyphTimerPill)?.apply {
            val prefix = if (st.running) "❚❚ " else "▶ "
            text = "$prefix${st.display}"
            setTextColor(getColor(if (st.running) R.color.text_primary else R.color.text_secondary))
        }
    }

    private fun bindSoundscape() {
        val btn = findViewById<TextView?>(R.id.deskSoundscapeBtn) ?: return
        val profile = FocusSoundEngine.current
        val active = profile != FocusSoundEngine.Profile.OFF
        btn.setText(profile.labelRes)
        btn.setBackgroundResource(if (active) R.drawable.bg_pill_white else R.drawable.bg_pill_outline)
        btn.setTextColor(getColor(if (active) R.color.pill_active_text else R.color.text_secondary))
    }

    private fun bindNote() {
        val note = prefs.essentialNote
        val tv = findViewById<TextView>(R.id.deskNotePreview)
        tv.text = if (note.isBlank()) getString(R.string.note_empty_hint) else note
        tv.setTextColor(getColor(if (note.isBlank()) R.color.text_secondary else R.color.text_primary))
    }

    companion object {
        fun launch(context: Context) {
            Intents.start(context, Intent(context, DeskHudActivity::class.java))
        }
    }
}
