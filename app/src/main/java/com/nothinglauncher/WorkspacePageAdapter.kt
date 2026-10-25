package com.nothinglauncher

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.ItemFolderTileBinding
import com.nothinglauncher.databinding.ItemMaxAppBinding
import com.nothinglauncher.databinding.ItemWorkspacePageBinding
import com.nothinglauncher.databinding.WidgetAnalogClockBinding
import com.nothinglauncher.databinding.WidgetBatteryBinding
import com.nothinglauncher.databinding.WidgetBatteryDotsBinding
import com.nothinglauncher.databinding.WidgetCalendarBinding
import com.nothinglauncher.databinding.WidgetClockBinding
import com.nothinglauncher.databinding.WidgetCompassBinding
import com.nothinglauncher.databinding.WidgetCountdownBinding
import com.nothinglauncher.databinding.WidgetHabitTrackerBinding
import com.nothinglauncher.databinding.WidgetMediaPlayerBinding
import com.nothinglauncher.databinding.WidgetPedometerBinding
import com.nothinglauncher.databinding.WidgetQuickTogglesBinding
import com.nothinglauncher.databinding.WidgetQuoteBinding
import com.nothinglauncher.databinding.WidgetScreenTimeBinding
import com.nothinglauncher.databinding.WidgetStopwatchBinding
import com.nothinglauncher.databinding.WidgetStorageBinding
import com.nothinglauncher.databinding.WidgetSystemMonitorBinding
import com.nothinglauncher.databinding.WidgetVoiceRecorderBinding
import com.nothinglauncher.databinding.WidgetWeatherBinding
import com.nothinglauncher.databinding.WidgetWorldClockBinding
import java.time.LocalDate
import java.util.Calendar
import kotlin.math.abs

/**
 * Renders one workspace page: bespoke Nothing elements, hosted app widgets, enlarged
 * 2x2 apps, folders and the pinned app grid.
 *
 * Every live source (weather, steps, compass, media, recorder) registers a listener while
 * the page is bound and removes it before the next bind, so scrolling or re-rendering can
 * never leak listeners or leave a stale callback pointed at a recycled view.
 */
class WorkspacePageAdapter(
    private val host: MainActivity,
    private val viewModel: LauncherViewModel
) : RecyclerView.Adapter<WorkspacePageAdapter.PageViewHolder>() {

    private val pages = mutableListOf<WorkspacePage>()
    private val boundHolders = mutableSetOf<PageViewHolder>()
    private var torchEnabled = false

    fun setPages(newPages: List<WorkspacePage>) {
        pages.clear()
        pages.addAll(newPages)
        notifyDataSetChanged()
    }

    /** Called by HomeFragment before the view hierarchy goes away. */
    fun unbindAll() {
        boundHolders.toList().forEach { it.unbind() }
        boundHolders.clear()
    }

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val b = ItemWorkspacePageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return PageViewHolder(b)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        boundHolders += holder
        holder.bind(pages[position], position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        boundHolders -= holder
        holder.unbind()
        super.onViewRecycled(holder)
    }

    inner class PageViewHolder(val b: ItemWorkspacePageBinding) : RecyclerView.ViewHolder(b.root) {
        private var gridAdapter: HomeIconAdapter? = null
        private val cleanups = mutableListOf<() -> Unit>()

        fun unbind() {
            cleanups.forEach { cleanup ->
                try {
                    cleanup()
                } catch (_: Exception) {
                }
            }
            cleanups.clear()
        }

        fun bind(page: WorkspacePage, pageIndex: Int) {
            unbind()
            val ctx = b.root.context
            val prefs = viewModel.prefs
            val accent = prefs.accentColor()
            val density = ctx.resources.displayMetrics.density
            val allAppsMap = viewModel.allApps.value.orEmpty().associateBy { it.key }
            val inflater = LayoutInflater.from(ctx)

            // 1. Bespoke elements ---------------------------------------------------------
            b.elementsContainer.removeAllViews()

            page.elements.forEach { elId ->
                when (elId) {
                    BespokeElement.CLOCK.id -> {
                        val clockB = WidgetClockBinding.inflate(inflater, b.elementsContainer, false)
                        clockB.clock.accentColor = accent
                        clockB.secondsTicker.accentColor = accent
                        clockB.secondsTicker.isVisible = prefs.showSeconds
                        clockB.liveDot.background.mutate().setTint(accent)
                        clockB.clock.tick()
                        clockB.root.setOnClickListener { AppLauncher.openAlarms(ctx) }
                        attachTileGestures(
                            clockB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.CLOCK, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.CLOCK, clockB.root) }
                        b.elementsContainer.addView(clockB.root, elementLp(density))
                    }
                    BespokeElement.BATTERY_CALENDAR.id -> {
                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            isBaselineAligned = false
                        }
                        val batB = WidgetBatteryBinding.inflate(inflater, row, false)
                        batB.battery.accentColor = accent
                        batB.root.setOnClickListener { AppLauncher.openBatterySettings(ctx) }
                        attachTileGestures(
                            batB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.BATTERY_CALENDAR, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.BATTERY_CALENDAR, batB.root) }

                        val calB = WidgetCalendarBinding.inflate(inflater, row, false)
                        calB.calDot.background.mutate().setTint(accent)
                        val now = Calendar.getInstance()
                        calB.calMonth.text = android.text.format.DateFormat.format("MMM", now).toString().uppercase()
                        calB.calDay.text = now.get(Calendar.DAY_OF_MONTH).toString()
                        calB.calWeekday.text = android.text.format.DateFormat.format("EEEE", now)
                        calB.root.setOnClickListener { AppLauncher.openCalendar(ctx) }
                        attachTileGestures(
                            calB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.BATTERY_CALENDAR, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.BATTERY_CALENDAR, calB.root) }

                        val halfLp1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = (6 * density).toInt()
                        }
                        val halfLp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = (6 * density).toInt()
                        }
                        row.addView(batB.root, halfLp1)
                        row.addView(calB.root, halfLp2)
                        b.elementsContainer.addView(row, elementLp(density))
                    }
                    BespokeElement.WEATHER.id -> {
                        val weatherB = WidgetWeatherBinding.inflate(inflater, b.elementsContainer, false)
                        weatherB.weatherView.accentColor = accent
                        val weatherListener: (WeatherRepository.Weather?) -> Unit = { weather ->
                            if (weatherB.root.isAttachedToWindow) weatherB.weatherView.bind(weather)
                        }
                        weatherB.weatherView.bind(WeatherRepository.addListener(prefs, weatherListener))
                        cleanups += { WeatherRepository.removeListener(weatherListener) }
                        weatherB.root.setOnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            WeatherRepository.refresh(ctx, prefs, force = true) { }
                        }
                        attachTileGestures(
                            weatherB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.WEATHER, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.WEATHER, weatherB.root) }
                        b.elementsContainer.addView(weatherB.root, elementLp(density))
                    }
                    BespokeElement.QUICK_TOGGLES.id -> {
                        val togB = WidgetQuickTogglesBinding.inflate(inflater, b.elementsContainer, false)
                        togB.togglesDot.background.mutate().setTint(accent)
                        togB.toggleWifi.setOnClickListener {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                AppLauncher.start(ctx, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                            } else {
                                AppLauncher.start(ctx, Intent(Settings.ACTION_WIFI_SETTINGS))
                            }
                        }
                        togB.toggleBt.setOnClickListener {
                            AppLauncher.start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                        togB.toggleTorch.setOnClickListener {
                            host.withCameraPermission { toggleTorch(ctx, togB) }
                        }
                        togB.toggleSound.setOnClickListener { cycleSoundMode(ctx, togB) }
                        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        togB.soundLabel.text = when (audioManager?.ringerMode) {
                            AudioManager.RINGER_MODE_VIBRATE -> ctx.getString(R.string.sound_vibrate)
                            AudioManager.RINGER_MODE_SILENT -> ctx.getString(R.string.sound_silent)
                            else -> ctx.getString(R.string.sound_normal)
                        }
                        togB.torchLabel.text = if (torchEnabled) "ON" else ctx.getString(R.string.toggle_torch).uppercase()
                        attachTileGestures(
                            togB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.QUICK_TOGGLES, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.QUICK_TOGGLES, togB.root) }
                        b.elementsContainer.addView(togB.root, elementLp(density))
                    }
                    BespokeElement.PEDOMETER.id -> {
                        val pedB = WidgetPedometerBinding.inflate(inflater, b.elementsContainer, false)
                        val pedView = pedB.pedometer
                        pedView.accentColor = accent
                        val stepListener: (StepRepository.State) -> Unit = { state ->
                            if (pedB.root.isAttachedToWindow) applyStepState(ctx, pedView, state)
                        }
                        applyStepState(ctx, pedView, StepRepository.addListener(ctx, stepListener))
                        cleanups += { StepRepository.removeListener(stepListener) }
                        pedB.root.setOnClickListener {
                            if (!LauncherSystem.hasActivityRecognition(ctx)) {
                                host.requestStepPermission()
                            } else {
                                AppLauncher.start(
                                    ctx,
                                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_FITNESS)
                                )
                            }
                        }
                        attachTileGestures(
                            pedB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.PEDOMETER, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.PEDOMETER, pedB.root) }
                        b.elementsContainer.addView(pedB.root, elementLp(density))
                    }
                    BespokeElement.COMPASS.id -> {
                        val compB = WidgetCompassBinding.inflate(inflater, b.elementsContainer, false)
                        val compassView = compB.compass
                        compassView.accentColor = accent
                        val compassListener: (CompassRepository.State) -> Unit = { state ->
                            if (compB.root.isAttachedToWindow) applyCompassState(ctx, compassView, state)
                        }
                        applyCompassState(ctx, compassView, CompassRepository.addListener(ctx, compassListener))
                        cleanups += { CompassRepository.removeListener(compassListener) }
                        compB.root.setOnClickListener {
                            AppLauncher.start(
                                ctx,
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=")),
                                showError = false
                            )
                        }
                        attachTileGestures(
                            compB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.COMPASS, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.COMPASS, compB.root) }
                        b.elementsContainer.addView(compB.root, elementLp(density))
                    }
                    BespokeElement.SCREEN_TIME.id -> {
                        val stB = WidgetScreenTimeBinding.inflate(inflater, b.elementsContainer, false)
                        stB.screenTimeView.accentColor = accent
                        stB.screenTimeView.refresh()
                        stB.root.setOnClickListener {
                            if (!LauncherSystem.hasUsageAccess(ctx)) {
                                LauncherSystem.openUsageAccessSettings(ctx)
                            } else if (!AppLauncher.start(
                                    ctx,
                                    Intent("android.settings.DIGITAL_WELLBEING"),
                                    showError = false
                                )
                            ) {
                                LauncherSystem.openUsageAccessSettings(ctx)
                            }
                        }
                        attachTileGestures(
                            stB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.SCREEN_TIME, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.SCREEN_TIME, stB.root) }
                        b.elementsContainer.addView(stB.root, elementLp(density))
                    }
                    BespokeElement.STORAGE.id -> {
                        val storB = WidgetStorageBinding.inflate(inflater, b.elementsContainer, false)
                        storB.storageView.accentColor = accent
                        storB.root.setOnClickListener {
                            AppLauncher.start(ctx, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), showError = false)
                        }
                        attachTileGestures(
                            storB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.STORAGE, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.STORAGE, storB.root) }
                        b.elementsContainer.addView(storB.root, elementLp(density))
                    }
                    BespokeElement.WORLD_CLOCK.id -> {
                        val wcB = WidgetWorldClockBinding.inflate(inflater, b.elementsContainer, false)
                        wcB.worldClockView.accentColor = accent
                        wcB.worldClockView.updateTimes()
                        wcB.root.setOnClickListener { AppLauncher.openAlarms(ctx) }
                        attachTileGestures(
                            wcB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.WORLD_CLOCK, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.WORLD_CLOCK, wcB.root) }
                        b.elementsContainer.addView(wcB.root, elementLp(density))
                    }
                    BespokeElement.BATTERY_DOTS.id -> {
                        val batDotsB = WidgetBatteryDotsBinding.inflate(inflater, b.elementsContainer, false)
                        batDotsB.batteryDotsView.accentColor = accent
                        batDotsB.root.setOnClickListener { AppLauncher.openBatterySettings(ctx) }
                        attachTileGestures(
                            batDotsB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.BATTERY_DOTS, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.BATTERY_DOTS, batDotsB.root) }
                        b.elementsContainer.addView(batDotsB.root, elementLp(density))
                    }
                    BespokeElement.QUICK_NOTE.id -> {
                        val quoteB = WidgetQuoteBinding.inflate(inflater, b.elementsContainer, false)
                        quoteB.quoteDotsView.accentColor = accent
                        quoteB.quoteDotsView.quoteText = prefs.quickNote
                        quoteB.root.setOnClickListener { host.showEditNoteDialog() }
                        attachTileGestures(
                            quoteB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.QUICK_NOTE, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.QUICK_NOTE, quoteB.root) }
                        b.elementsContainer.addView(quoteB.root, elementLp(density))
                    }
                    BespokeElement.ANALOG_CLOCK.id -> {
                        val acB = WidgetAnalogClockBinding.inflate(inflater, b.elementsContainer, false)
                        acB.analogClock.accentColor = accent
                        acB.root.setOnClickListener { AppLauncher.openAlarms(ctx) }
                        attachTileGestures(
                            acB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.ANALOG_CLOCK, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.ANALOG_CLOCK, acB.root) }
                        b.elementsContainer.addView(acB.root, elementLp(density))
                    }
                    BespokeElement.MEDIA_PLAYER.id -> {
                        val medB = WidgetMediaPlayerBinding.inflate(inflater, b.elementsContainer, false)
                        medB.mediaDot.background.mutate().setTint(accent)
                        medB.mediaVisualizer.accentColor = accent
                        MediaSessionRepository.start(ctx.applicationContext)

                        fun applyMediaState(state: MediaSessionRepository.State) {
                            val canControl = state.permissionGranted && state.hasSession
                            medB.mediaLabel.setText(
                                if (state.permissionGranted) R.string.media_label else R.string.media_label_empty
                            )
                            medB.mediaDot.alpha = if (state.isPlaying) 1f else 0.4f
                            medB.mediaTitle.text = when {
                                !state.permissionGranted -> ctx.getString(R.string.media_permission_needed)
                                !state.hasSession -> ctx.getString(R.string.media_no_session)
                                state.title.isBlank() -> ctx.getString(R.string.media_connect_hint)
                                else -> state.title
                            }
                            medB.mediaArtist.text = if (canControl) state.artist.uppercase() else ""
                            medB.mediaArtist.isVisible = canControl && state.artist.isNotBlank()
                            medB.mediaVisualizer.isPlaying = canControl && state.isPlaying
                            medB.mediaPlayPause.setImageResource(
                                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                            )
                            listOf(medB.mediaPlayPause, medB.mediaNext, medB.mediaPrev).forEach { control ->
                                control.isEnabled = canControl
                                control.alpha = if (canControl) 1f else 0.4f
                            }
                        }

                        val mediaListener: (MediaSessionRepository.State) -> Unit = { state ->
                            if (medB.root.isAttachedToWindow) applyMediaState(state)
                        }
                        applyMediaState(MediaSessionRepository.addListener(mediaListener))
                        cleanups += { MediaSessionRepository.removeListener(mediaListener) }

                        medB.root.setOnClickListener {
                            val state = MediaSessionRepository.state
                            when {
                                !state.permissionGranted -> LauncherSystem.openNotificationAccessSettings(ctx)
                                state.packageName.isNotBlank() -> AppLauncher.openMediaApp(ctx, state.packageName)
                            }
                        }
                        medB.mediaPlayPause.setOnClickListener { v ->
                            MediaSessionRepository.playPause()
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        medB.mediaNext.setOnClickListener { v ->
                            MediaSessionRepository.next()
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        medB.mediaPrev.setOnClickListener { v ->
                            MediaSessionRepository.previous()
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        attachTileGestures(
                            medB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.MEDIA_PLAYER, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.MEDIA_PLAYER, medB.root) }
                        b.elementsContainer.addView(medB.root, elementLp(density))
                    }
                    BespokeElement.STOPWATCH.id -> {
                        val swB = WidgetStopwatchBinding.inflate(inflater, b.elementsContainer, false)
                        swB.stopwatchDot.background.mutate().setTint(accent)
                        swB.stopwatchView.accentColor = accent
                        swB.btnStartStop.setOnClickListener {
                            val running = swB.stopwatchView.toggle()
                            swB.btnStartStop.text = if (running) {
                                ctx.getString(R.string.stopwatch_stop)
                            } else {
                                ctx.getString(R.string.stopwatch_start)
                            }
                            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        swB.btnReset.setOnClickListener {
                            swB.stopwatchView.reset()
                            swB.btnStartStop.text = ctx.getString(R.string.stopwatch_start)
                            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        attachTileGestures(
                            swB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.STOPWATCH, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.STOPWATCH, swB.root) }
                        b.elementsContainer.addView(swB.root, elementLp(density))
                    }
                    BespokeElement.SYSTEM_MONITOR.id -> {
                        val monB = WidgetSystemMonitorBinding.inflate(inflater, b.elementsContainer, false)
                        monB.monitorDot.background.mutate().setTint(accent)
                        monB.systemMonitorView.accentColor = accent
                        monB.systemMonitorView.updateStats()
                        monB.root.setOnClickListener {
                            AppLauncher.start(ctx, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), showError = false)
                        }
                        attachTileGestures(
                            monB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.SYSTEM_MONITOR, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.SYSTEM_MONITOR, monB.root) }
                        b.elementsContainer.addView(monB.root, elementLp(density))
                    }
                    BespokeElement.HABIT_TRACKER.id -> {
                        val habB = WidgetHabitTrackerBinding.inflate(inflater, b.elementsContainer, false)
                        habB.habitDot.background.mutate().setTint(accent)
                        val habitView = habB.habitTrackerView
                        habitView.accentColor = accent
                        habitView.count = prefs.habitCount
                        habitView.onCountChanged = { count -> prefs.habitCount = count }
                        habitView.onLongPress = {
                            host.showElementPopup(pageIndex, BespokeElement.HABIT_TRACKER, habB.root)
                        }
                        habitView.onDragStart = { rawX, rawY ->
                            host.startDrag(
                                DragController.DragItem.Bespoke(BespokeElement.HABIT_TRACKER, pageIndex),
                                habB.root,
                                rawX,
                                rawY
                            )
                        }
                        b.elementsContainer.addView(habB.root, elementLp(density))
                    }
                    BespokeElement.COUNTDOWN.id -> {
                        val cdB = WidgetCountdownBinding.inflate(inflater, b.elementsContainer, false)
                        cdB.countdownDot.background.mutate().setTint(accent)
                        val countdownView = cdB.countdownView
                        countdownView.accentColor = accent
                        val epochDay = prefs.countdownEpochDay
                        val title = prefs.countdownTitle.ifBlank { ctx.getString(R.string.countdown_default_title) }
                        if (epochDay > 0L) {
                            val remaining = (epochDay - LocalDate.now().toEpochDay()).toInt()
                            if (remaining >= 0) {
                                countdownView.daysRemaining = remaining
                                countdownView.eventTitle = title
                                countdownView.targetActive = true
                            } else {
                                countdownView.daysRemaining = 0
                                countdownView.eventTitle = ctx.getString(R.string.countdown_passed, -remaining)
                                countdownView.targetActive = false
                            }
                        } else {
                            countdownView.daysRemaining = 0
                            countdownView.eventTitle = ctx.getString(R.string.countdown_no_target)
                            countdownView.targetActive = false
                        }
                        cdB.root.setOnClickListener { host.showCountdownEditor() }
                        attachTileGestures(
                            cdB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.COUNTDOWN, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.COUNTDOWN, cdB.root) }
                        b.elementsContainer.addView(cdB.root, elementLp(density))
                    }
                    BespokeElement.SOUND_RECORDER.id -> {
                        val vrB = WidgetVoiceRecorderBinding.inflate(inflater, b.elementsContainer, false)
                        vrB.recorderDot.background.mutate().setTint(accent)
                        vrB.recorderDot.alpha = if (host.voiceRecorder.isRecording) 1f else 0.4f
                        vrB.voiceRecorderView.accentColor = accent
                        vrB.voiceRecorderView.setRecording(
                            host.voiceRecorder.isRecording,
                            host.voiceRecorder.recordingStartedAt
                        )
                        vrB.root.setOnClickListener { host.toggleVoiceMemo() }
                        attachTileGestures(
                            vrB.root,
                            { DragController.DragItem.Bespoke(BespokeElement.SOUND_RECORDER, pageIndex) }
                        ) { host.showElementPopup(pageIndex, BespokeElement.SOUND_RECORDER, vrB.root) }
                        b.elementsContainer.addView(vrB.root, elementLp(density))
                    }
                }
            }

            // 2. Third-party app widgets --------------------------------------------------
            b.widgetContainer.removeAllViews()
            page.widgetIds.forEach { id ->
                val hostView = host.createWidgetView(id) ?: return@forEach
                val info = hostView.appWidgetInfo
                // AppWidgetProviderInfo heights are dp, not pixels.
                val fallbackDp = (info?.minHeight ?: DEFAULT_WIDGET_HEIGHT_DP)
                    .coerceIn(LauncherPrefs.MIN_WIDGET_HEIGHT_DP, LauncherPrefs.MAX_WIDGET_HEIGHT_DP)
                val heightDp = prefs.widgetHeight(id, fallbackDp)
                val heightPx = (heightDp * density).toInt()
                val card = FrameLayout(ctx).apply {
                    tag = id
                    setBackgroundResource(R.drawable.bg_tile_dark)
                    clipToOutline = true
                    val pad = (6 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                (hostView.parent as? ViewGroup)?.removeView(hostView)
                card.addView(hostView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx))
                b.widgetContainer.addView(card, elementLp(density))
                if (hostView is LauncherWidgetHostView) {
                    hostView.onLongPress = { host.showWidgetPopup(id, pageIndex, card) }
                    hostView.onDragStart = { v, rawX, rawY ->
                        host.startDrag(DragController.DragItem.Widget(id, pageIndex), card, rawX, rawY)
                    }
                }
                updateWidgetSize(hostView, heightDp)
            }

            // 3. Max / enlarged 2x2 apps --------------------------------------------------
            b.enlargedContainer.removeAllViews()
            page.enlargedApps.forEach { key ->
                val app = allAppsMap[key] ?: return@forEach
                val maxB = ItemMaxAppBinding.inflate(inflater, b.enlargedContainer, false)
                maxB.maxAppIcon.setImageDrawable(app.icon)
                maxB.maxAppLabel.text = app.label
                maxB.maxAppDot.background.mutate().setTint(accent)
                maxB.root.setOnClickListener { host.launchApp(app, it) }
                attachTileGestures(
                    maxB.root,
                    { DragController.DragItem.MaxApp(app, pageIndex) }
                ) { host.showAppPopup(app, maxB.root, MainActivity.PopupOrigin.HOME) }
                b.enlargedContainer.addView(maxB.root)
            }

            // 4. Folders ------------------------------------------------------------------
            b.foldersContainer.removeAllViews()
            page.folders.forEach { folder ->
                val fTile = ItemFolderTileBinding.inflate(inflater, b.foldersContainer, false)
                fTile.folderTileName.text = folder.name
                fTile.folderTileCount.text = "${folder.appKeys.size} APPS"
                fTile.folderMiniGrid.removeAllViews()
                val folderApps = folder.appKeys.mapNotNull { allAppsMap[it] }
                folderApps.take(4).forEach { fApp ->
                    val mini = ImageView(ctx).apply {
                        setImageDrawable(fApp.icon)
                        val sz = (20 * density).toInt()
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = sz
                            height = sz
                            setMargins(
                                (1 * density).toInt(),
                                (1 * density).toInt(),
                                (1 * density).toInt(),
                                (1 * density).toInt()
                            )
                        }
                    }
                    fTile.folderMiniGrid.addView(mini)
                }
                fTile.root.setOnClickListener { host.showFolderSheet(pageIndex, folder) }
                attachTileGestures(
                    fTile.root,
                    { DragController.DragItem.Folder(folder, pageIndex) }
                ) { host.showFolderPopup(pageIndex, folder, fTile.root) }
                b.foldersContainer.addView(fTile.root)
            }

            // 5. Pinned app grid ----------------------------------------------------------
            gridAdapter = HomeIconAdapter(
                onAppClick = { app, v -> host.launchApp(app, v) },
                onAppLongClick = { app, v -> host.showAppPopup(app, v, MainActivity.PopupOrigin.HOME) },
                onAppDragStart = { app, v, rawX, rawY ->
                    host.startDrag(
                        DragController.DragItem.App(app, DragController.DragSource.HOME_PAGE, pageIndex),
                        v,
                        rawX,
                        rawY
                    )
                }
            )
            val columns = prefs.gridColumns
            val baseIconSize = ctx.resources.getDimensionPixelSize(
                if (columns >= 5) R.dimen.icon_size_compact else R.dimen.icon_size
            )
            val scale = when (prefs.iconSize) {
                LauncherPrefs.IconSize.SMALL -> 0.82f
                LauncherPrefs.IconSize.MEDIUM -> 1.0f
                LauncherPrefs.IconSize.LARGE -> 1.18f
            }
            gridAdapter?.iconSizePx = (baseIconSize * scale).toInt()
            gridAdapter?.showLabels = prefs.showHomeLabels
            gridAdapter?.labelShadow = prefs.background == LauncherPrefs.Background.WALLPAPER

            b.homeGrid.layoutManager = GridLayoutManager(ctx, columns)
            b.homeGrid.adapter = gridAdapter
            gridAdapter?.setItems(viewModel.getSlotItemsForPage(page, columns))

            // 6. Empty state and blank-area gestures --------------------------------------
            val isEmpty = page.elements.isEmpty() && page.widgetIds.isEmpty() &&
                page.appKeys.none { it.isNotEmpty() } && page.enlargedApps.isEmpty() && page.folders.isEmpty()
            b.homeEmpty.isVisible = isEmpty

            val onCustomize = View.OnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                host.showCustomizeSheet()
                true
            }
            b.homeEmpty.setOnLongClickListener(onCustomize)
            b.pageContent.setOnLongClickListener(onCustomize)
            b.pageScroll.setOnLongClickListener(onCustomize)
            b.homeGrid.setOnLongClickListener(onCustomize)
            b.elementsContainer.setOnLongClickListener(onCustomize)
            b.widgetContainer.setOnLongClickListener(onCustomize)
            b.enlargedContainer.setOnLongClickListener(onCustomize)
            b.foldersContainer.setOnLongClickListener(onCustomize)
        }

        /**
         * A stock-launcher gesture: a long press arms the tile, moving starts a drag and
         * releasing without moving opens the menu. Consuming the stream avoids the
         * focusable popup stealing subsequent MOVE events from the drag controller.
         */
        private fun attachTileGestures(
            view: View,
            getItem: () -> DragController.DragItem,
            onMenu: () -> Unit
        ) {
            val ctx = view.context
            val slop = ViewConfiguration.get(ctx).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var downRawX = 0f
            var downRawY = 0f
            var longPressed = false
            var moved = false
            val longPressRunnable = Runnable {
                if (!view.isAttachedToWindow) return@Runnable
                longPressed = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        longPressed = false
                        moved = false
                        v.isPressed = true
                        v.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!longPressed) {
                            if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                                moved = true
                                v.removeCallbacks(longPressRunnable)
                            }
                        } else if (abs(event.rawX - downRawX) > slop * 1.5f ||
                            abs(event.rawY - downRawY) > slop * 1.5f
                        ) {
                            v.removeCallbacks(longPressRunnable)
                            longPressed = false
                            v.isPressed = false
                            host.startDrag(getItem(), v, event.rawX, event.rawY)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.removeCallbacks(longPressRunnable)
                        v.isPressed = false
                        if (longPressed) {
                            longPressed = false
                            onMenu()
                        } else if (!moved) {
                            v.performClick()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(longPressRunnable)
                        v.isPressed = false
                        longPressed = false
                        moved = false
                    }
                }
                true
            }
        }

        private fun applyStepState(ctx: Context, view: PedometerDotsView, state: StepRepository.State) {
            view.steps = state.steps
            view.goal = state.goal
            view.statusText = when {
                !state.sensorAvailable -> ctx.getString(R.string.pedometer_no_sensor)
                !state.permissionGranted -> ctx.getString(R.string.pedometer_no_permission)
                else -> null
            }
        }

        private fun applyCompassState(ctx: Context, view: CompassDotsView, state: CompassRepository.State) {
            view.headingDegrees = state.heading
            view.directionText = state.direction
            view.statusText = when {
                !state.sensorAvailable -> ctx.getString(R.string.compass_no_sensor)
                state.calibrating -> ctx.getString(R.string.compass_calibrating)
                else -> null
            }
        }

        private fun toggleTorch(context: Context, binding: WidgetQuickTogglesBinding) {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            val id = cm.cameraIdList.firstOrNull() ?: return
            torchEnabled = !torchEnabled
            try {
                cm.setTorchMode(id, torchEnabled)
                binding.torchLabel.text = if (torchEnabled) "ON" else context.getString(R.string.toggle_torch).uppercase()
                binding.torchDisc.background.mutate().setTint(
                    if (torchEnabled) {
                        viewModel.prefs.accentColor()
                    } else {
                        ContextCompat.getColor(context, R.color.surface_raised)
                    }
                )
            } catch (e: Exception) {
                torchEnabled = !torchEnabled
                AppLauncher.toast(context, R.string.cannot_open)
            }
        }

        private fun cycleSoundMode(context: Context, binding: WidgetQuickTogglesBinding) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> {
                    try {
                        am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    } catch (_: Exception) {
                    }
                    binding.soundLabel.text = context.getString(R.string.sound_vibrate)
                }
                AudioManager.RINGER_MODE_VIBRATE -> {
                    try {
                        am.ringerMode = AudioManager.RINGER_MODE_SILENT
                    } catch (_: Exception) {
                    }
                    binding.soundLabel.text = context.getString(R.string.sound_silent)
                }
                else -> {
                    try {
                        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    } catch (_: Exception) {
                    }
                    binding.soundLabel.text = context.getString(R.string.sound_normal)
                }
            }
        }

        private fun elementLp(density: Float): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (10 * density).toInt()
            }

        private fun updateWidgetSize(view: View, heightDp: Int) {
            try {
                val opts = Bundle().apply {
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
                }
                (view as? android.appwidget.AppWidgetHostView)?.updateAppWidgetOptions(opts)
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val DEFAULT_WIDGET_HEIGHT_DP = 120
    }
}
