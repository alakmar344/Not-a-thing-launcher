package com.nothinglauncher.space

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.android.launcher3.Launcher
import com.android.launcher3.Utilities
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayTouchProxy
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.DotGridDrawable
import com.nothinglauncher.DotMatrixView
import com.nothinglauncher.FocusSoundEngine
import com.nothinglauncher.FocusTimerRepository
import com.nothinglauncher.HardwareControlRepository
import com.nothinglauncher.Intents
import com.nothinglauncher.MediaArtDotsView
import com.nothinglauncher.MediaDotsView
import com.nothinglauncher.MediaSessionRepository
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.NotificationDigestRepository
import com.nothinglauncher.R
import com.nothinglauncher.Sheets
import com.nothinglauncher.Tactile
import com.nothinglauncher.VoiceMemoRepository
import com.nothinglauncher.WeatherRepository
import com.nothinglauncher.perf.VisualTier
import com.nothinglauncher.widgets.HabitMatrixWidget
import com.nothinglauncher.widgets.HabitPegboardView
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * In-process -1 "Essential Space" overlay wired directly into Launcher3's
 * [LauncherOverlayManager] + [LauncherOverlayTouchProxy] contract.
 *
 * Swiping right from page 0 reveals Essential Space 1:1 with the finger:
 * daily briefing, hardware switchboard (torch, focus mode, caffeine, ringer),
 * scratchpad note + voice memos, live focus timer, and device vitals.
 */
class EssentialSpaceOverlay(
    private val launcher: Launcher,
    private val onRequestRecordPermission: () -> Unit = {},
    private val onFocusModeToggled: () -> Unit = {}
) : LauncherOverlayManager, LauncherOverlayTouchProxy {

    private var callbacks: LauncherOverlayCallbacks? = null
    private var rootView: View? = null
    private var progress: Float = 0f
    private var flingVelocity: Float = 0f
    private var settleAnimator: ValueAnimator? = null
    private var attached = false
    private val isRtl get() = Utilities.isRtl(launcher.resources)

    private val handler = Handler(Looper.getMainLooper())
    private var tickerRunning = false
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!tickerRunning || progress <= 0f) return
            refreshDynamicCounters()
            handler.postDelayed(this, 500L)
        }
    }

    private val torchListener: (Boolean) -> Unit = { bindHardwarePills() }
    private val memoListener: () -> Unit = { bindMemoRow() }
    private val timerListener: () -> Unit = { refreshDynamicCounters() }
    private val weatherListener: (WeatherRepository.Weather?) -> Unit = { bindBriefing() }
    private val mediaListener: (MediaSessionRepository.State) -> Unit = { bindMedia(it) }
    private val digestListener: (NotificationDigestRepository.Snapshot) -> Unit = { bindDigest(it) }
    private val soundscapeListener: (FocusSoundEngine.Profile) -> Unit = { bindSoundscape() }
    private var listenersRegistered = false

    val isShown: Boolean get() = progress > 0.01f

    override fun onAttachedToWindow() {
        attached = true
        launcher.workspace?.setLauncherOverlay(this)
        ensureViewMounted()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stopTickers()
        unregisterListeners()
        settleAnimator?.cancel()
        launcher.workspace?.setLauncherOverlay(null)
        rootView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        rootView = null
    }

    override fun setOverlayCallbacks(callbacks: LauncherOverlayCallbacks?) {
        this.callbacks = callbacks
        callbacks?.onOverlayScrollChanged(progress)
    }

    override fun onFlingVelocity(velocity: Float) {
        flingVelocity = velocity
    }

    override fun onOverlayMotionEvent(ev: MotionEvent, scrollProgress: Float) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settleAnimator?.cancel()
                flingVelocity = 0f
                ensureViewMounted()
            }
            MotionEvent.ACTION_MOVE -> {
                applyProgress(scrollProgress.coerceIn(0f, 1f))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val open = shouldSettleOpen(progress, flingVelocity)
                animateTo(if (open) 1f else 0f)
            }
        }
    }

    override fun openOverlay() {
        ensureViewMounted()
        animateTo(1f)
    }

    override fun hideOverlay(duration: Int) {
        if (progress <= 0f && rootView?.visibility != View.VISIBLE) return
        if (duration <= 0) {
            settleAnimator?.cancel()
            applyProgress(0f)
        } else {
            animateTo(0f, duration.toLong())
        }
    }

    override fun onActivityResumed() {
        applyCaffeineFlag()
        if (progress > 0f) {
            registerListeners()
            bindAll()
            startTickers()
        }
    }

    override fun onActivityPaused() {
        stopTickers()
        unregisterListeners()
        VoiceMemoRepository.releaseAll()
    }

    override fun onActivityDestroyed() {
        stopTickers()
        VoiceMemoRepository.releaseAll()
        unregisterListeners()
    }

    private fun registerListeners() {
        if (listenersRegistered) return
        listenersRegistered = true
        HardwareControlRepository.addTorchListener(launcher, torchListener)
        VoiceMemoRepository.addListener(memoListener)
        FocusTimerRepository.addListener(timerListener)
        WeatherRepository.addListener(NothingPrefs.get(launcher), weatherListener)
        if (rootView?.findViewById<View?>(R.id.spaceMediaCard) != null) {
            MediaSessionRepository.start(launcher.applicationContext)
            MediaSessionRepository.addListener(mediaListener)
        }
        FocusSoundEngine.addListener(soundscapeListener)
        NotificationDigestRepository.addListener(launcher, digestListener)
    }

    private fun unregisterListeners() {
        if (!listenersRegistered) return
        listenersRegistered = false
        HardwareControlRepository.removeTorchListener(torchListener)
        VoiceMemoRepository.removeListener(memoListener)
        FocusTimerRepository.removeListener(timerListener)
        WeatherRepository.removeListener(weatherListener)
        MediaSessionRepository.removeListener(mediaListener)
        FocusSoundEngine.removeListener(soundscapeListener)
        NotificationDigestRepository.removeListener(digestListener)
    }

    private fun shouldSettleOpen(p: Float, velocity: Float): Boolean {
        val threshold = ViewConfiguration.get(launcher).scaledMinimumFlingVelocity * 1.4f
        val towardOpen = if (isRtl) velocity < -threshold else velocity > threshold
        val towardClose = if (isRtl) velocity > threshold else velocity < -threshold
        return when {
            towardOpen -> true
            towardClose -> false
            else -> p >= 0.30f
        }
    }

    private fun animateTo(target: Float, durationMs: Long = 260L) {
        settleAnimator?.cancel()
        val start = progress
        if (start == target) {
            applyProgress(target)
            return
        }
        settleAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(2.1f)
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    applyProgress(target)
                }
            })
            start()
        }
    }

    private fun ensureViewMounted(): View {
        rootView?.let { return it }
        val dragLayer = launcher.dragLayer
        val slop = ViewConfiguration.get(launcher).scaledTouchSlop
        val wrapper = object : FrameLayout(launcher) {
            private var downX = 0f
            private var downY = 0f
            private var dragging = false
            private var tracker: VelocityTracker? = null

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        dragging = false
                        tracker?.recycle()
                        tracker = VelocityTracker.obtain().apply { addMovement(ev) }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (dragging) return true
                        tracker?.addMovement(ev)
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        val closingDrag = if (isRtl) dx > slop else dx < -slop
                        if (closingDrag && abs(dx) > abs(dy) * 1.4f) {
                            dragging = true
                            settleAnimator?.cancel()
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!dragging) {
                            tracker?.recycle()
                            tracker = null
                        }
                    }
                }
                return super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        dragging = false
                        tracker?.recycle()
                        tracker = VelocityTracker.obtain().apply { addMovement(ev) }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        tracker?.addMovement(ev)
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        val closingDrag = if (isRtl) dx > slop else dx < -slop
                        if (!dragging && closingDrag && abs(dx) > abs(dy) * 1.4f) {
                            dragging = true
                            settleAnimator?.cancel()
                        }
                        if (dragging) {
                            val w = width.coerceAtLeast(1).toFloat()
                            val fraction = (if (isRtl) 1f - dx / w else 1f + dx / w).coerceIn(0f, 1f)
                            applyProgress(fraction)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = dragging
                        val vx = if (wasDragging) {
                            tracker?.computeCurrentVelocity(1000)
                            tracker?.xVelocity ?: 0f
                        } else 0f
                        tracker?.recycle()
                        tracker = null
                        dragging = false
                        if (wasDragging) {
                            val keepOpen = shouldSettleOpen(progress, vx)
                            animateTo(if (keepOpen) 1f else 0f)
                            return true
                        }
                    }
                }
                return super.onTouchEvent(ev)
            }
        }
        val content = LayoutInflater.from(launcher).inflate(R.layout.essential_space_overlay, wrapper, false)
        wrapper.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        wrapper.background = DotGridDrawable.forTier(launcher)
        wrapper.visibility = View.GONE
        setupInsets(wrapper)
        setupButtons(wrapper)
        com.nothinglauncher.BigScreenUi.applyMaxWidth(wrapper.findViewById(R.id.essentialColumn), 980)
        dragLayer.addView(
            wrapper,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        rootView = wrapper
        return wrapper
    }

    private fun setupInsets(view: View) {
        val column = view.findViewById<View>(R.id.essentialColumn)
        val baseTop = column.paddingTop
        val baseBottom = column.paddingBottom
        val baseLeft = column.paddingLeft
        val baseRight = column.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            column.updatePadding(
                left = baseLeft + bars.left,
                top = baseTop + bars.top,
                right = baseRight + bars.right,
                bottom = baseBottom + bars.bottom
            )
            insets
        }
    }

    private fun setupButtons(view: View) {
        view.findViewById<View>(R.id.essentialCloseBtn).setOnClickListener {
            Tactile.tick(it)
            hideOverlay(260)
        }
        view.findViewById<View>(R.id.essentialDeskBtn)?.apply {
            Tactile.press(this)
            setOnClickListener {
                Tactile.confirm(it)
                hideOverlay(0)
                com.nothinglauncher.DeskHudActivity.launch(launcher)
            }
        }
        val prefs = NothingPrefs.get(launcher)

        val torch = view.findViewById<View>(R.id.spaceTorchBtn)
        val focus = view.findViewById<View>(R.id.spaceFocusBtn)
        val caffeine = view.findViewById<View>(R.id.spaceCaffeineBtn)
        val sound = view.findViewById<View>(R.id.spaceSoundBtn)
        listOf(torch, focus, caffeine, sound).forEach { Tactile.press(it) }

        torch.setOnClickListener {
            Tactile.confirm(it)
            HardwareControlRepository.toggleTorch(launcher)
            bindHardwarePills()
        }
        focus.setOnClickListener {
            Tactile.confirm(it)
            prefs.focusMode = !prefs.focusMode
            bindHardwarePills()
            onFocusModeToggled()
            bindDigest(NotificationDigestRepository.current(launcher))
        }
        caffeine.setOnClickListener {
            Tactile.confirm(it)
            prefs.caffeineEnabled = !prefs.caffeineEnabled
            applyCaffeineFlag()
            bindHardwarePills()
        }
        sound.setOnClickListener {
            Tactile.confirm(it)
            HardwareControlRepository.cycleRingerOrOpenSettings(launcher)
            bindHardwarePills()
        }

        view.findViewById<View>(R.id.spaceNoteBody).setOnClickListener {
            Tactile.tick(it)
            Sheets.showTextInput(
                launcher,
                launcher.getString(R.string.essential_note_title),
                launcher.getString(R.string.quick_note_hint),
                prefs.essentialNote,
                maxLength = 180
            ) { updated ->
                prefs.essentialNote = updated
                bindNote()
            }
        }

        view.findViewById<View>(R.id.spaceVoiceBtn).setOnClickListener {
            Tactile.confirm(it)
            if (VoiceMemoRepository.isRecording) {
                VoiceMemoRepository.stopRecording()
            } else if (!VoiceMemoRepository.hasRecordPermission(launcher)) {
                onRequestRecordPermission()
            } else {
                VoiceMemoRepository.startRecording(launcher)
            }
            bindMemoRow()
        }

        view.findViewById<View>(R.id.spaceMemoPlay).setOnClickListener {
            val latest = VoiceMemoRepository.listMemos(launcher).firstOrNull() ?: return@setOnClickListener
            Tactile.tick(it)
            VoiceMemoRepository.togglePlayback(latest)
            bindMemoRow()
        }

        view.findViewById<View>(R.id.spaceMemoDelete).setOnClickListener {
            val latest = VoiceMemoRepository.listMemos(launcher).firstOrNull() ?: return@setOnClickListener
            Tactile.confirm(it)
            VoiceMemoRepository.deleteMemo(latest)
            bindMemoRow()
        }

        view.findViewById<View>(R.id.spaceTimerMode).setOnClickListener {
            Tactile.tick(it)
            FocusTimerRepository.toggleMode(launcher)
            refreshDynamicCounters()
        }
        view.findViewById<View>(R.id.spaceTimerReset).setOnClickListener {
            Tactile.tick(it)
            FocusTimerRepository.reset(launcher)
            refreshDynamicCounters()
        }
        view.findViewById<View>(R.id.spaceTimerToggle).setOnClickListener {
            Tactile.confirm(it)
            FocusTimerRepository.toggleStartPause(launcher)
            refreshDynamicCounters()
        }
        view.findViewById<View?>(R.id.spaceSoundscapeBtn)?.apply {
            Tactile.press(this)
            setOnClickListener {
                Tactile.confirm(it)
                val next = FocusSoundEngine.cycle()
                if (next != FocusSoundEngine.Profile.OFF) {
                    prefs.soundscapePreset = next.ordinal
                }
                bindSoundscape()
            }
        }

        // Optional Big-Screen Bento Cards (Habit Matrix Check-in + Media Deck)
        val habitAction = View.OnClickListener {
            Tactile.confirm(it)
            HabitMatrixWidget.toggleToday(prefs)
            bindHabit()
        }
        view.findViewById<View?>(R.id.spaceHabitToggle)?.apply {
            Tactile.press(this)
            setOnClickListener(habitAction)
        }
        view.findViewById<View?>(R.id.spaceHabitGrid)?.setOnClickListener(habitAction)
        view.findViewById<View?>(R.id.spaceHabitTitle)?.setOnClickListener {
            Tactile.tick(it)
            Sheets.showTextInput(
                launcher,
                launcher.getString(R.string.habit_rename_title),
                launcher.getString(R.string.habit_rename_hint),
                prefs.habitTitle,
                maxLength = 20
            ) { name ->
                if (name.isNotBlank()) prefs.habitTitle = name.uppercase(Locale.getDefault())
                bindHabit()
            }
        }
        view.findViewById<View?>(R.id.spaceMediaPrev)?.setOnClickListener {
            Tactile.tick(it)
            MediaSessionRepository.previous()
        }
        view.findViewById<View?>(R.id.spaceMediaPlayPause)?.setOnClickListener {
            Tactile.confirm(it)
            MediaSessionRepository.playPause()
        }
        view.findViewById<View?>(R.id.spaceMediaNext)?.setOnClickListener {
            Tactile.tick(it)
            MediaSessionRepository.next()
        }
    }

    private fun applyProgress(newProgress: Float) {
        val clamped = newProgress.coerceIn(0f, 1f)
        val wasOpen = progress > 0f
        progress = clamped
        callbacks?.onOverlayScrollChanged(clamped)

        val view = rootView ?: return
        val w = (launcher.dragLayer?.width ?: launcher.resources.displayMetrics.widthPixels).coerceAtLeast(1)
        if (clamped <= 0f) {
            view.visibility = View.GONE
            view.translationX = if (isRtl) w.toFloat() else -w.toFloat()
            launcher.workspace?.apply { scaleX = 1f; scaleY = 1f; alpha = 1f }
            launcher.hotseat?.apply { alpha = 1f }
            stopTickers()
            unregisterListeners()
            return
        }

        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
            bindAll()
            startTickers()
        }
        view.translationX = if (isRtl) w * (1f - clamped) else -w * (1f - clamped)
        val wsScale = 1f - 0.04f * clamped
        launcher.workspace?.apply {
            scaleX = wsScale
            scaleY = wsScale
            alpha = 1f - 0.55f * clamped
        }
        launcher.hotseat?.alpha = 1f - 0.85f * clamped
        if (!wasOpen && clamped > 0f) {
            registerListeners()
        }
    }

    private fun startTickers() {
        if (tickerRunning) return
        tickerRunning = true
        handler.post(tickRunnable)
    }

    private fun stopTickers() {
        tickerRunning = false
        handler.removeCallbacks(tickRunnable)
    }

    private fun applyCaffeineFlag() {
        val keepOn = NothingPrefs.get(launcher).caffeineEnabled
        if (keepOn) {
            launcher.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            launcher.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun refreshAfterPermission() {
        if (progress > 0f) bindAll()
    }

    fun bindAll() {
        val view = rootView ?: return
        val accent = AccentTheme.accentColor(launcher)
        AccentTheme.tintDot(view.findViewById(R.id.essentialBriefingDot), launcher)
        AccentTheme.tintDot(view.findViewById(R.id.spaceRecDot), launcher)
        view.findViewById<View?>(R.id.spaceDigestDot)?.let { AccentTheme.tintDot(it, launcher) }
        view.findViewById<DotMatrixView>(R.id.spaceTimerDots).accentColor = accent
        bindBriefing()
        bindHardwarePills()
        bindNote()
        bindMemoRow()
        refreshDynamicCounters()
        bindSoundscape()
        bindDigest(NotificationDigestRepository.current(launcher))
        bindVitals()
        bindHabit()
        bindMedia(MediaSessionRepository.state)
    }

    private fun bindBriefing() {
        val view = rootView ?: return
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> launcher.getString(R.string.greeting_morning)
            hour < 17 -> launcher.getString(R.string.greeting_afternoon)
            else -> launcher.getString(R.string.greeting_evening)
        }
        view.findViewById<TextView>(R.id.essentialGreeting).text =
            DateFormat.format("EEEE, MMM d", cal).toString().uppercase(Locale.getDefault())
        view.findViewById<TextView>(R.id.essentialBriefingHeadline).text = greeting

        val weather = WeatherRepository.cached(NothingPrefs.get(launcher))
        view.findViewById<TextView>(R.id.essentialWeatherPill).text =
            if (weather != null) "${weather.temperature}\u00B0 ${weather.city.uppercase()}" else ""

        val alarm = (launcher.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.nextAlarmClock
        val alarmStr = alarm?.let {
            val pattern = if (DateFormat.is24HourFormat(launcher)) "EEE HH:mm" else "EEE h:mm a"
            "Next alarm ${DateFormat.format(pattern, it.triggerTime)}"
        }
        val event = HardwareControlRepository.queryNextCalendarEvent(launcher)
        val eventStr = event?.let { "${it.timeLabel} · ${it.title}" }
        val sub = listOfNotNull(eventStr, alarmStr).joinToString("   •   ")
            .ifEmpty { launcher.getString(R.string.essential_briefing_empty) }
        view.findViewById<TextView>(R.id.essentialBriefingSub).text = sub
    }

    private fun bindHardwarePills() {
        val view = rootView ?: return
        val prefs = NothingPrefs.get(launcher)
        val accent = AccentTheme.accentColor(launcher)
        val normalIcon = launcher.getColor(R.color.text_primary)
        val dimText = launcher.getColor(R.color.text_secondary)

        fun styleTile(id: Int, iconId: Int, labelId: Int, active: Boolean) {
            val tile = view.findViewById<View>(id)
            val icon = view.findViewById<ImageView>(iconId)
            val label = view.findViewById<TextView>(labelId)
            tile.setBackgroundResource(if (active) R.drawable.bg_tile_active else R.drawable.bg_tile_dark)
            val tint = if (active) accent else normalIcon
            icon.setColorFilter(tint)
            label.setTextColor(if (active) launcher.getColor(R.color.pill_active_text) else dimText)
        }

        styleTile(R.id.spaceTorchBtn, R.id.spaceTorchIcon, R.id.spaceTorchLabel, HardwareControlRepository.torchOn)
        styleTile(R.id.spaceFocusBtn, R.id.spaceFocusIcon, R.id.spaceFocusLabel, prefs.focusMode)
        styleTile(R.id.spaceCaffeineBtn, R.id.spaceCaffeineIcon, R.id.spaceCaffeineLabel, prefs.caffeineEnabled)
        val ringer = HardwareControlRepository.ringerMode(launcher)
        val silentOrVibrate = ringer != AudioManager.RINGER_MODE_NORMAL
        styleTile(R.id.spaceSoundBtn, R.id.spaceSoundIcon, R.id.spaceSoundLabel, silentOrVibrate)
        view.findViewById<TextView>(R.id.spaceSoundLabel).setText(
            when (ringer) {
                AudioManager.RINGER_MODE_VIBRATE -> R.string.sound_vibrate
                AudioManager.RINGER_MODE_SILENT -> R.string.sound_silent
                else -> R.string.toggle_sound
            }
        )
    }

    private fun bindNote() {
        val view = rootView ?: return
        val note = NothingPrefs.get(launcher).essentialNote
        val tv = view.findViewById<TextView>(R.id.spaceNoteBody)
        tv.text = if (note.isBlank()) launcher.getString(R.string.note_empty_hint) else note
        tv.setTextColor(launcher.getColor(if (note.isBlank()) R.color.text_secondary else R.color.text_primary))
    }

    private fun bindMemoRow() {
        val view = rootView ?: return
        val voiceBtn = view.findViewById<TextView>(R.id.spaceVoiceBtn)
        val memoRow = view.findViewById<View>(R.id.spaceMemoRow)
        val playBtn = view.findViewById<ImageView>(R.id.spaceMemoPlay)
        val memoLabel = view.findViewById<TextView>(R.id.spaceMemoLabel)

        val rec = VoiceMemoRepository.isRecording
        voiceBtn.setText(if (rec) R.string.memo_stop else R.string.memo_record)
        voiceBtn.setTextColor(if (rec) AccentTheme.accentColor(launcher) else launcher.getColor(R.color.text_primary))

        val latest = VoiceMemoRepository.listMemos(launcher).firstOrNull()
        memoRow.isVisible = latest != null && !rec
        if (latest != null) {
            val playing = VoiceMemoRepository.activePlayingPath == latest.file.absolutePath
            playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            memoLabel.text = "VOICE MEMO · ${latest.label}"
        }
    }

    private fun refreshDynamicCounters() {
        val view = rootView ?: return
        val timer = FocusTimerRepository.snapshot(launcher)
        view.findViewById<TextView>(R.id.spaceTimerMode).setText(
            if (timer.isStopwatch) R.string.stopwatch_label else R.string.timer_label
        )
        view.findViewById<DotMatrixView>(R.id.spaceTimerDots).text = timer.display
        view.findViewById<TextView>(R.id.spaceTimerToggle).setText(
            if (timer.running) R.string.timer_pause else R.string.timer_start
        )
        if (VoiceMemoRepository.isRecording) {
            val bars = ((VoiceMemoRepository.amplitude01() * 6).toInt()).coerceIn(1, 6)
            view.findViewById<TextView>(R.id.spaceVoiceBtn).text =
                "REC " + "●".repeat(bars)
        }
    }

    private fun bindVitals() {
        val view = rootView ?: return
        val v = HardwareControlRepository.readVitals(launcher)
        val storage = String.format(Locale.US, "STORAGE %.1f / %.0f GB", v.freeStorageGb, v.totalStorageGb)
        val ram = String.format(Locale.US, "RAM %.1f / %.1f GB", v.availRamGb, v.totalRamGb)
        val screen = v.screenTimeMinutesToday?.let { mins ->
            val h = mins / 60
            val m = mins % 60
            "   •   SCREEN ${h}H ${m}M"
        } ?: ""
        view.findViewById<TextView>(R.id.spaceVitalsText).text = "$storage   •   $ram$screen"
    }

    private fun bindHabit() {
        val view = rootView ?: return
        val grid = view.findViewById<HabitPegboardView?>(R.id.spaceHabitGrid) ?: return
        val prefs = NothingPrefs.get(launcher)
        val accent = AccentTheme.accentColor(launcher)
        view.findViewById<View?>(R.id.spaceHabitDot)?.let { AccentTheme.tintDot(it, launcher) }
        grid.accentColor = accent
        val dates = prefs.habitDates
        val streak = HabitMatrixWidget.computeStreak(dates)
        val doneToday = HabitMatrixWidget.isDoneToday(prefs)
        view.findViewById<TextView?>(R.id.spaceHabitTitle)?.text =
            prefs.habitTitle.uppercase(Locale.getDefault())
        view.findViewById<TextView?>(R.id.spaceHabitStreak)?.text = "${streak}D"
        view.findViewById<TextView?>(R.id.spaceHabitToggle)?.apply {
            setText(if (doneToday) R.string.habit_done else R.string.habit_log)
            setBackgroundResource(if (doneToday) R.drawable.bg_pill_white else R.drawable.bg_pill_outline)
            setTextColor(
                launcher.getColor(if (doneToday) R.color.pill_active_text else R.color.text_primary)
            )
        }
        grid.completedDates = dates
    }

    private fun bindMedia(state: MediaSessionRepository.State) {
        val view = rootView ?: return
        if (view.findViewById<View?>(R.id.spaceMediaCard) == null) return
        val canControl = state.permissionGranted && state.hasSession
        val accent = AccentTheme.accentColor(launcher)
        view.findViewById<View?>(R.id.spaceMediaDot)?.let { AccentTheme.tintDot(it, launcher) }

        view.findViewById<MediaDotsView?>(R.id.spaceMediaBars)?.apply {
            accentColor = accent
            isPlaying = canControl && state.isPlaying
        }
        val art = view.findViewById<MediaArtDotsView?>(R.id.spaceMediaArt)
        val showArt = VisualTier.current(launcher) != VisualTier.Tier.LITE &&
            canControl && state.artHalftone != null
        art?.apply {
            accentColor = accent
            isVisible = showArt
            if (showArt) matrix = state.artHalftone
        }
        view.findViewById<TextView?>(R.id.spaceMediaRoute)?.apply {
            text = if (canControl) state.outputRoute else ""
            isVisible = canControl && state.outputRoute.isNotBlank()
        }
        view.findViewById<TextView?>(R.id.spaceMediaTitle)?.text = when {
            !state.permissionGranted -> launcher.getString(R.string.media_permission_needed)
            !state.hasSession -> launcher.getString(R.string.media_no_session)
            state.title.isBlank() -> launcher.getString(R.string.media_connect_hint)
            else -> state.title
        }
        view.findViewById<TextView?>(R.id.spaceMediaArtist)?.apply {
            text = if (canControl) state.artist.uppercase(Locale.getDefault()) else ""
            isVisible = canControl && state.artist.isNotBlank()
        }

        val playPause = view.findViewById<ImageView?>(R.id.spaceMediaPlayPause)
        playPause?.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        val prev = view.findViewById<View?>(R.id.spaceMediaPrev)
        val next = view.findViewById<View?>(R.id.spaceMediaNext)
        listOfNotNull(playPause, prev, next).forEach {
            it.isEnabled = canControl
            it.alpha = if (canControl) 1f else 0.4f
        }
    }

    private fun bindSoundscape() {
        val btn = rootView?.findViewById<TextView?>(R.id.spaceSoundscapeBtn) ?: return
        val profile = FocusSoundEngine.current
        val active = profile != FocusSoundEngine.Profile.OFF
        btn.setText(profile.labelRes)
        btn.setBackgroundResource(if (active) R.drawable.bg_pill_white else R.drawable.bg_pill_outline)
        btn.setTextColor(
            launcher.getColor(if (active) R.color.pill_active_text else R.color.text_secondary)
        )
    }

    private fun bindDigest(snap: NotificationDigestRepository.Snapshot) {
        val view = rootView ?: return
        view.findViewById<View?>(R.id.spaceDigestDot)?.let { AccentTheme.tintDot(it, launcher) }
        val countView = view.findViewById<TextView?>(R.id.spaceDigestCount)
        val container = view.findViewById<LinearLayout?>(R.id.spaceDigestContainer) ?: return
        container.removeAllViews()

        val prefs = NothingPrefs.get(launcher)
        val accent = AccentTheme.accentColor(launcher)
        val d = launcher.resources.displayMetrics.density

        if (!snap.permissionGranted) {
            countView?.text = ""
            val row = LinearLayout(launcher).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setBackgroundResource(R.drawable.fg_ripple_card)
                setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
                addView(TextView(launcher).apply {
                    text = launcher.getString(R.string.nt_digest_grant)
                    textSize = 11.5f
                    setTextColor(launcher.getColor(R.color.text_primary))
                    letterSpacing = 0.08f
                })
                addView(TextView(launcher).apply {
                    text = launcher.getString(R.string.nt_digest_grant_sub)
                    textSize = 11f
                    setTextColor(launcher.getColor(R.color.text_secondary))
                })
                setOnClickListener {
                    Tactile.tick(it)
                    Intents.openNotificationAccess(launcher, it)
                }
            }
            container.addView(row)
            return
        }

        val visibleEntries = if (prefs.focusMode) {
            snap.entries.filter { it.isPriority }
        } else {
            snap.entries
        }
        countView?.text = when {
            prefs.focusMode -> launcher.getString(R.string.nt_digest_shielded, snap.totalCount)
            snap.totalCount > 0 -> "${snap.totalCount} ACTIVE"
            else -> "0"
        }

        if (visibleEntries.isEmpty()) {
            container.addView(TextView(launcher).apply {
                text = if (prefs.focusMode && snap.totalCount > 0) {
                    launcher.getString(R.string.nt_digest_shielded, snap.totalCount)
                } else {
                    launcher.getString(R.string.nt_digest_empty)
                }
                textSize = 11.5f
                setTextColor(launcher.getColor(R.color.text_secondary))
            })
            return
        }

        visibleEntries.take(4).forEachIndexed { idx, entry ->
            val row = LinearLayout(launcher).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setBackgroundResource(R.drawable.fg_ripple_card)
                setPadding(0, if (idx == 0) 0 else (6 * d).toInt(), 0, (6 * d).toInt())
            }
            val marker = View(launcher).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 3f * d
                    setColor(if (entry.isPriority) accent else launcher.getColor(R.color.text_secondary))
                }
            }
            row.addView(marker, LinearLayout.LayoutParams((6 * d).toInt(), (6 * d).toInt()))

            val col = LinearLayout(launcher).apply {
                orientation = LinearLayout.VERTICAL
            }
            val meta = if (entry.countInPkg > 1) {
                "${entry.appLabel.uppercase(Locale.getDefault())} · ${entry.countInPkg}"
            } else {
                entry.appLabel.uppercase(Locale.getDefault())
            }
            col.addView(TextView(launcher).apply {
                text = meta
                textSize = 9.5f
                letterSpacing = 0.08f
                setTextColor(if (entry.isPriority) accent else launcher.getColor(R.color.text_secondary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val body = listOf(entry.title, entry.text).filter { it.isNotBlank() }.joinToString(" — ")
                .ifEmpty { entry.appLabel }
            col.addView(TextView(launcher).apply {
                text = body
                textSize = 12f
                setTextColor(launcher.getColor(R.color.text_primary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (10 * d).toInt()
            })
            row.setOnClickListener { clicked ->
                Tactile.tick(clicked)
                val pi = entry.contentIntent
                val sent = try {
                    if (pi != null) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val opts = android.app.ActivityOptions.makeBasic().apply {
                                pendingIntentBackgroundActivityStartMode =
                                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                            }
                            pi.send(launcher, 0, null, null, null, null, opts.toBundle())
                        } else {
                            pi.send()
                        }
                        true
                    } else {
                        false
                    }
                } catch (_: Throwable) {
                    false
                }
                if (!sent) Intents.openApp(launcher, entry.packageName, clicked)
            }
            container.addView(row)
        }
    }
}
