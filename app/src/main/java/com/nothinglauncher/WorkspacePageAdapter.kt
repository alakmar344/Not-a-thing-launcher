package com.nothinglauncher

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
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
import androidx.recyclerview.widget.ItemTouchHelper
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
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.hypot

class WorkspacePageAdapter(
    private val host: MainActivity,
    private val viewModel: LauncherViewModel
) : RecyclerView.Adapter<WorkspacePageAdapter.PageViewHolder>() {

    private val pages = mutableListOf<WorkspacePage>()
    private var torchEnabled = false

    fun setPages(newPages: List<WorkspacePage>) {
        pages.clear()
        pages.addAll(newPages)
        notifyDataSetChanged()
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
        holder.bind(pages[position], position)
    }

    inner class PageViewHolder(val b: ItemWorkspacePageBinding) : RecyclerView.ViewHolder(b.root) {
        private lateinit var gridAdapter: HomeIconAdapter
        private var dragMoved = false

        fun bind(page: WorkspacePage, pageIndex: Int) {
            val ctx = b.root.context
            val prefs = viewModel.prefs
            val accent = prefs.accentColor()
            val density = ctx.resources.displayMetrics.density
            val allAppsMap = viewModel.allApps.value.orEmpty().associateBy { it.key }

            fun attachDragToView(view: View, getItem: () -> DragController.DragItem) {
                var downX = 0f
                var downY = 0f
                var downRawX = 0f
                var downRawY = 0f
                var longPressed = false
                val slop = ViewConfiguration.get(ctx).scaledTouchSlop
                val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

                val longPressRunnable = Runnable {
                    longPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }

                view.setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = ev.x
                            downY = ev.y
                            downRawX = ev.rawX
                            downRawY = ev.rawY
                            longPressed = false
                            v.postDelayed(longPressRunnable, longPressTimeout)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!longPressed && (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop)) {
                                v.removeCallbacks(longPressRunnable)
                            } else if (longPressed) {
                                val dx = abs(ev.rawX - downRawX)
                                val dy = abs(ev.rawY - downRawY)
                                if (dx > slop * 1.5f || dy > slop * 1.5f) {
                                    v.removeCallbacks(longPressRunnable)
                                    longPressed = false
                                    host.startDrag(getItem(), v, ev.rawX, ev.rawY)
                                    return@setOnTouchListener true
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.removeCallbacks(longPressRunnable)
                            longPressed = false
                        }
                    }
                    false
                }
            }

            // 1. Inflate Elements
            b.elementsContainer.removeAllViews()
            val inflater = LayoutInflater.from(ctx)

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
                        clockB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.CLOCK, it)
                            true
                        }
                        attachDragToView(clockB.root) { DragController.DragItem.Bespoke(BespokeElement.CLOCK, pageIndex) }
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
                        batB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.BATTERY_CALENDAR, it)
                            true
                        }
                        attachDragToView(batB.root) { DragController.DragItem.Bespoke(BespokeElement.BATTERY_CALENDAR, pageIndex) }

                        val calB = WidgetCalendarBinding.inflate(inflater, row, false)
                        calB.calDot.background.mutate().setTint(accent)
                        val now = Calendar.getInstance()
                        calB.calMonth.text = android.text.format.DateFormat.format("MMM", now).toString().uppercase()
                        calB.calDay.text = now.get(Calendar.DAY_OF_MONTH).toString()
                        calB.calWeekday.text = android.text.format.DateFormat.format("EEEE", now)
                        calB.root.setOnClickListener { AppLauncher.openCalendar(ctx) }
                        calB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.BATTERY_CALENDAR, it)
                            true
                        }
                        attachDragToView(calB.root) { DragController.DragItem.Bespoke(BespokeElement.BATTERY_CALENDAR, pageIndex) }

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
                        weatherB.root.setOnClickListener { AppLauncher.webSearch(ctx, "weather today") }
                        weatherB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.WEATHER, it)
                            true
                        }
                        attachDragToView(weatherB.root) { DragController.DragItem.Bespoke(BespokeElement.WEATHER, pageIndex) }
                        b.elementsContainer.addView(weatherB.root, elementLp(density))
                    }
                    BespokeElement.QUICK_TOGGLES.id -> {
                        val togB = WidgetQuickTogglesBinding.inflate(inflater, b.elementsContainer, false)
                        togB.togglesDot.background.mutate().setTint(accent)
                        togB.toggleWifi.setOnClickListener { AppLauncher.start(ctx, Intent(Settings.ACTION_WIFI_SETTINGS)) }
                        togB.toggleBt.setOnClickListener { AppLauncher.start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                        togB.toggleTorch.setOnClickListener { toggleTorch(ctx, togB) }
                        togB.toggleSound.setOnClickListener { cycleSoundMode(ctx, togB) }
                        togB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.QUICK_TOGGLES, it)
                            true
                        }
                        attachDragToView(togB.root) { DragController.DragItem.Bespoke(BespokeElement.QUICK_TOGGLES, pageIndex) }
                        b.elementsContainer.addView(togB.root, elementLp(density))
                    }
                    BespokeElement.PEDOMETER.id -> {
                        val pedB = WidgetPedometerBinding.inflate(inflater, b.elementsContainer, false)
                        pedB.pedometer.accentColor = accent
                        pedB.root.setOnClickListener { AppLauncher.start(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_FITNESS)) }
                        pedB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.PEDOMETER, it)
                            true
                        }
                        attachDragToView(pedB.root) { DragController.DragItem.Bespoke(BespokeElement.PEDOMETER, pageIndex) }
                        b.elementsContainer.addView(pedB.root, elementLp(density))
                    }
                    BespokeElement.COMPASS.id -> {
                        val compB = WidgetCompassBinding.inflate(inflater, b.elementsContainer, false)
                        compB.compass.accentColor = accent
                        compB.root.setOnClickListener { AppLauncher.start(ctx, Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q="))) }
                        compB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.COMPASS, it)
                            true
                        }
                        attachDragToView(compB.root) { DragController.DragItem.Bespoke(BespokeElement.COMPASS, pageIndex) }
                        b.elementsContainer.addView(compB.root, elementLp(density))
                    }
                    BespokeElement.SCREEN_TIME.id -> {
                        val stB = WidgetScreenTimeBinding.inflate(inflater, b.elementsContainer, false)
                        stB.screenTimeView.accentColor = accent
                        stB.root.setOnClickListener {
                            if (!AppLauncher.start(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), showError = false)) {
                                AppLauncher.start(ctx, Intent("android.settings.DIGITAL_WELLBEING"), showError = false)
                            }
                        }
                        stB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.SCREEN_TIME, it)
                            true
                        }
                        attachDragToView(stB.root) { DragController.DragItem.Bespoke(BespokeElement.SCREEN_TIME, pageIndex) }
                        b.elementsContainer.addView(stB.root, elementLp(density))
                    }
                    BespokeElement.STORAGE.id -> {
                        val storB = WidgetStorageBinding.inflate(inflater, b.elementsContainer, false)
                        storB.storageView.accentColor = accent
                        storB.root.setOnClickListener {
                            AppLauncher.start(ctx, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), showError = false)
                        }
                        storB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.STORAGE, it)
                            true
                        }
                        attachDragToView(storB.root) { DragController.DragItem.Bespoke(BespokeElement.STORAGE, pageIndex) }
                        b.elementsContainer.addView(storB.root, elementLp(density))
                    }
                    BespokeElement.WORLD_CLOCK.id -> {
                        val wcB = WidgetWorldClockBinding.inflate(inflater, b.elementsContainer, false)
                        wcB.worldClockView.accentColor = accent
                        wcB.worldClockView.updateTimes()
                        wcB.root.setOnClickListener { AppLauncher.openAlarms(ctx) }
                        wcB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.WORLD_CLOCK, it)
                            true
                        }
                        attachDragToView(wcB.root) { DragController.DragItem.Bespoke(BespokeElement.WORLD_CLOCK, pageIndex) }
                        b.elementsContainer.addView(wcB.root, elementLp(density))
                    }
                    BespokeElement.BATTERY_DOTS.id -> {
                        val batDotsB = WidgetBatteryDotsBinding.inflate(inflater, b.elementsContainer, false)
                        batDotsB.batteryDotsView.accentColor = accent
                        batDotsB.root.setOnClickListener { AppLauncher.openBatterySettings(ctx) }
                        batDotsB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.BATTERY_DOTS, it)
                            true
                        }
                        attachDragToView(batDotsB.root) { DragController.DragItem.Bespoke(BespokeElement.BATTERY_DOTS, pageIndex) }
                        b.elementsContainer.addView(batDotsB.root, elementLp(density))
                    }
                    BespokeElement.QUICK_NOTE.id -> {
                        val quoteB = WidgetQuoteBinding.inflate(inflater, b.elementsContainer, false)
                        quoteB.quoteDotsView.accentColor = accent
                        quoteB.quoteDotsView.quoteText = prefs.quickNote
                        quoteB.root.setOnClickListener { host.showEditNoteDialog() }
                        quoteB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.QUICK_NOTE, it)
                            true
                        }
                        attachDragToView(quoteB.root) { DragController.DragItem.Bespoke(BespokeElement.QUICK_NOTE, pageIndex) }
                        b.elementsContainer.addView(quoteB.root, elementLp(density))
                    }
                    BespokeElement.ANALOG_CLOCK.id -> {
                        val acB = WidgetAnalogClockBinding.inflate(inflater, b.elementsContainer, false)
                        acB.analogClock.accentColor = accent
                        acB.root.setOnClickListener { AppLauncher.openAlarms(ctx) }
                        acB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.ANALOG_CLOCK, it)
                            true
                        }
                        attachDragToView(acB.root) { DragController.DragItem.Bespoke(BespokeElement.ANALOG_CLOCK, pageIndex) }
                        b.elementsContainer.addView(acB.root, elementLp(density))
                    }
                    BespokeElement.MEDIA_PLAYER.id -> {
                        val medB = WidgetMediaPlayerBinding.inflate(inflater, b.elementsContainer, false)
                        medB.mediaDot.background.mutate().setTint(accent)
                        medB.mediaVisualizer.accentColor = accent
                        medB.mediaPlayPause.setOnClickListener {
                            val playing = !medB.mediaVisualizer.isPlaying
                            medB.mediaVisualizer.isPlaying = playing
                            medB.mediaPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        medB.mediaNext.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                        medB.mediaPrev.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                        medB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.MEDIA_PLAYER, it)
                            true
                        }
                        attachDragToView(medB.root) { DragController.DragItem.Bespoke(BespokeElement.MEDIA_PLAYER, pageIndex) }
                        b.elementsContainer.addView(medB.root, elementLp(density))
                    }
                    BespokeElement.STOPWATCH.id -> {
                        val swB = WidgetStopwatchBinding.inflate(inflater, b.elementsContainer, false)
                        swB.stopwatchDot.background.mutate().setTint(accent)
                        swB.stopwatchView.accentColor = accent
                        swB.btnStartStop.setOnClickListener {
                            val running = swB.stopwatchView.toggle()
                            swB.btnStartStop.text = if (running) ctx.getString(R.string.stopwatch_stop) else ctx.getString(R.string.stopwatch_start)
                            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        swB.btnReset.setOnClickListener {
                            swB.stopwatchView.reset()
                            swB.btnStartStop.text = ctx.getString(R.string.stopwatch_start)
                            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        swB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.STOPWATCH, it)
                            true
                        }
                        attachDragToView(swB.root) { DragController.DragItem.Bespoke(BespokeElement.STOPWATCH, pageIndex) }
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
                        monB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.SYSTEM_MONITOR, it)
                            true
                        }
                        attachDragToView(monB.root) { DragController.DragItem.Bespoke(BespokeElement.SYSTEM_MONITOR, pageIndex) }
                        b.elementsContainer.addView(monB.root, elementLp(density))
                    }
                    BespokeElement.HABIT_TRACKER.id -> {
                        val habB = WidgetHabitTrackerBinding.inflate(inflater, b.elementsContainer, false)
                        habB.habitDot.background.mutate().setTint(accent)
                        habB.habitTrackerView.accentColor = accent
                        habB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.HABIT_TRACKER, it)
                            true
                        }
                        attachDragToView(habB.root) { DragController.DragItem.Bespoke(BespokeElement.HABIT_TRACKER, pageIndex) }
                        b.elementsContainer.addView(habB.root, elementLp(density))
                    }
                    BespokeElement.COUNTDOWN.id -> {
                        val cdB = WidgetCountdownBinding.inflate(inflater, b.elementsContainer, false)
                        cdB.countdownDot.background.mutate().setTint(accent)
                        cdB.countdownView.accentColor = accent
                        cdB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.COUNTDOWN, it)
                            true
                        }
                        attachDragToView(cdB.root) { DragController.DragItem.Bespoke(BespokeElement.COUNTDOWN, pageIndex) }
                        b.elementsContainer.addView(cdB.root, elementLp(density))
                    }
                    BespokeElement.SOUND_RECORDER.id -> {
                        val vrB = WidgetVoiceRecorderBinding.inflate(inflater, b.elementsContainer, false)
                        vrB.recorderDot.background.mutate().setTint(accent)
                        vrB.voiceRecorderView.accentColor = accent
                        vrB.root.setOnClickListener {
                            vrB.voiceRecorderView.toggle()
                            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        vrB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.SOUND_RECORDER, it)
                            true
                        }
                        attachDragToView(vrB.root) { DragController.DragItem.Bespoke(BespokeElement.SOUND_RECORDER, pageIndex) }
                        b.elementsContainer.addView(vrB.root, elementLp(density))
                    }
                }
            }

            // 2. Inflate Third-party Widgets
            b.widgetContainer.removeAllViews()
            page.widgetIds.forEach { id ->
                val hostView = host.createWidgetView(id) ?: return@forEach
                val info = hostView.appWidgetInfo
                val height = (info?.minHeight ?: (120 * density).toInt())
                    .coerceIn((96 * density).toInt(), (360 * density).toInt())
                val card = FrameLayout(ctx).apply {
                    tag = id
                    setBackgroundResource(R.drawable.bg_tile_dark)
                    clipToOutline = true
                    val pad = (6 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                (hostView.parent as? ViewGroup)?.removeView(hostView)
                card.addView(hostView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
                b.widgetContainer.addView(card, elementLp(density))
                if (hostView is LauncherWidgetHostView) {
                    hostView.onLongPress = { host.showWidgetPopup(id, card) }
                    hostView.onDragStart = { v, rawX, rawY ->
                        host.startDrag(DragController.DragItem.Widget(id, pageIndex), card, rawX, rawY)
                    }
                }
                updateWidgetSize(hostView, height, density)
            }

            // 3. Max / Enlarged 2x2 Apps
            b.enlargedContainer.removeAllViews()
            page.enlargedApps.forEach { key ->
                val app = allAppsMap[key] ?: return@forEach
                val maxB = ItemMaxAppBinding.inflate(inflater, b.enlargedContainer, false)
                maxB.maxAppIcon.setImageDrawable(app.icon)
                maxB.maxAppLabel.text = app.label
                maxB.maxAppDot.background.mutate().setTint(accent)
                maxB.root.setOnClickListener { host.launchApp(app, it) }
                maxB.root.setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    host.showAppPopup(app, it, MainActivity.PopupOrigin.HOME)
                    true
                }
                attachDragToView(maxB.root) { DragController.DragItem.MaxApp(app, pageIndex) }
                b.enlargedContainer.addView(maxB.root)
            }

            // 4. App Folders
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
                            setMargins((1 * density).toInt(), (1 * density).toInt(), (1 * density).toInt(), (1 * density).toInt())
                        }
                    }
                    fTile.folderMiniGrid.addView(mini)
                }
                fTile.root.setOnClickListener {
                    host.showFolderSheet(pageIndex, folder)
                }
                fTile.root.setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    host.showFolderSheet(pageIndex, folder)
                    true
                }
                attachDragToView(fTile.root) { DragController.DragItem.Folder(folder, pageIndex) }
                b.foldersContainer.addView(fTile.root)
            }

            // 5. Setup App Grid
            gridAdapter = HomeIconAdapter(
                onAppClick = { app, v -> host.launchApp(app, v) },
                onAppLongClick = { app, v ->
                    dragMoved = false
                    host.showAppPopup(app, v, MainActivity.PopupOrigin.HOME)
                },
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
            gridAdapter.iconSizePx = (baseIconSize * scale).toInt()
            gridAdapter.showLabels = prefs.showHomeLabels
            gridAdapter.labelShadow = prefs.background == LauncherPrefs.Background.WALLPAPER

            b.homeGrid.layoutManager = GridLayoutManager(ctx, columns)
            b.homeGrid.adapter = gridAdapter
            gridAdapter.setItems(viewModel.getSlotItemsForPage(page, columns))

            // Attach touch helper for drag-and-drop within page
            val dragThreshold = 14 * density
            val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
            ) {
                override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION || gridAdapter.getItem(pos) == null) return 0
                    return ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                }

                override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = true

                override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    return gridAdapter.moveItem(vh.bindingAdapterPosition, target.bindingAdapterPosition).also {
                        if (it) dragMoved = true
                    }
                }
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                        b.homeGrid.parent?.requestDisallowInterceptTouchEvent(true)
                        viewHolder.itemView.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).start()
                    }
                }
                override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
                    super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                    if (isCurrentlyActive && hypot(dX, dY) > dragThreshold) host.dismissPopup()
                }
                override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                    super.clearView(rv, vh)
                    vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    if (dragMoved) {
                        dragMoved = false
                        viewModel.savePageSlots(pageIndex, gridAdapter.getSlotItems())
                    }
                }
                override fun isLongPressDragEnabled(): Boolean = true
            })
            touchHelper.attachToRecyclerView(b.homeGrid)

            // 6. Empty State
            val isEmpty = page.elements.isEmpty() && page.widgetIds.isEmpty() && page.appKeys.none { it.isNotEmpty() } && page.enlargedApps.isEmpty() && page.folders.isEmpty()
            b.homeEmpty.isVisible = isEmpty

            // Customize sheet on long press empty area
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

            val pageGestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (prefs.doubleTapToSleep) {
                        host.lockScreen()
                        return true
                    }
                    return false
                }
                override fun onLongPress(e: MotionEvent) {
                    if (!host.isPopupShowing() && !host.dragController.isDragging) {
                        b.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        host.showCustomizeSheet()
                    }
                }
            })
            b.pageScroll.setOnTouchListener { _, event ->
                pageGestureDetector.onTouchEvent(event)
                false
            }
        }

        private fun toggleTorch(context: Context, binding: WidgetQuickTogglesBinding) {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            val id = cm.cameraIdList.firstOrNull() ?: return
            torchEnabled = !torchEnabled
            try {
                cm.setTorchMode(id, torchEnabled)
                binding.torchLabel.text = if (torchEnabled) "ON" else "TORCH"
                binding.torchDisc.background.mutate().setTint(
                    if (torchEnabled) viewModel.prefs.accentColor() else ContextCompat.getColor(context, R.color.surface_raised)
                )
            } catch (e: Exception) {
                AppLauncher.start(context, Intent(Settings.ACTION_SETTINGS))
            }
        }

        private fun cycleSoundMode(context: Context, binding: WidgetQuickTogglesBinding) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> {
                    try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: Exception) {}
                    binding.soundLabel.text = context.getString(R.string.sound_vibrate)
                }
                AudioManager.RINGER_MODE_VIBRATE -> {
                    try { am.ringerMode = AudioManager.RINGER_MODE_SILENT } catch (_: Exception) {}
                    binding.soundLabel.text = context.getString(R.string.sound_silent)
                }
                else -> {
                    try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
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

        private fun updateWidgetSize(view: View, height: Int, density: Float) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val opts = Bundle().apply {
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, (height / density).toInt())
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, (height / density).toInt())
                }
                (view as? android.appwidget.AppWidgetHostView)?.updateAppWidgetOptions(opts)
            }
        }
    }
}
