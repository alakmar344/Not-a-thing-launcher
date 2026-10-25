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
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.ItemAppBinding
import com.nothinglauncher.databinding.ItemMaxAppBinding
import com.nothinglauncher.databinding.ItemWorkspaceFolder1x1Binding
import com.nothinglauncher.databinding.ItemWorkspaceFolder2x2Binding
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
 * Unified Workspace Page Adapter.
 * Hosts apps, folders (1x1 and 2x2), max 2x2 apps, bespoke Nothing elements, and AppWidgets
 * directly inside a native [CellLayout] grid without artificial container silos.
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
        private val cleanups = mutableListOf<() -> Unit>()

        fun unbind() {
            cleanups.forEach { cleanup ->
                try {
                    cleanup()
                } catch (_: Exception) {
                }
            }
            cleanups.clear()
            b.cellLayout.removeAllViews()
        }

        fun bind(page: WorkspacePage, pageIndex: Int) {
            unbind()
            val ctx = b.root.context
            val prefs = viewModel.prefs
            val accent = prefs.accentColor()
            val density = ctx.resources.displayMetrics.density
            val allAppsMap = viewModel.allApps.value.orEmpty().associateBy { it.key }
            val inflater = LayoutInflater.from(ctx)
            val columns = prefs.gridColumns.coerceAtLeast(1)

            b.cellLayout.countX = columns
            b.cellLayout.setAccentColor(accent)
            b.cellLayout.removeAllViews()

            page.items.forEach { item ->
                when (item.type) {
                    WorkspaceItem.Type.APP -> {
                        val app = allAppsMap[item.appKey] ?: return@forEach
                        val appB = ItemAppBinding.inflate(inflater, b.cellLayout, false)
                        appB.appIcon.setImageDrawable(app.icon)
                        appB.appLabel.text = app.label
                        appB.appLabel.isVisible = prefs.showHomeLabels
                        val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                        attachTileGestures(
                            appB.root,
                            { DragController.DragItem.App(app, DragController.DragSource.HOME_PAGE, pageIndex) }
                        ) { host.showAppPopup(app, appB.root, MainActivity.PopupOrigin.HOME) }
                        appB.root.setOnClickListener { host.launchApp(app, it) }
                        b.cellLayout.addView(appB.root, lp)
                    }

                    WorkspaceItem.Type.FOLDER -> {
                        val folder = item.folder ?: return@forEach
                        val folderApps = folder.appKeys.mapNotNull { allAppsMap[it] }

                        if (folder.isEnlarged) {
                            // 2x2 Enlarged Folder
                            val f2B = ItemWorkspaceFolder2x2Binding.inflate(inflater, b.cellLayout, false)
                            f2B.folder2x2Name.text = folder.name
                            f2B.folder2x2Count.text = "${folder.appKeys.size} APPS"
                            f2B.folder2x2Dot.background.mutate().setTint(accent)

                            val slots = listOf(f2B.fSlot1, f2B.fSlot2, f2B.fSlot3, f2B.fSlot4)
                            val icons = listOf(f2B.fIcon1, f2B.fIcon2, f2B.fIcon3, f2B.fIcon4)

                            slots.forEachIndexed { idx, slot ->
                                val fApp = folderApps.getOrNull(idx)
                                if (fApp != null) {
                                    icons[idx].setImageDrawable(fApp.icon)
                                    icons[idx].isVisible = true
                                    slot.setOnClickListener { host.launchApp(fApp, it) }
                                    slot.setOnLongClickListener {
                                        host.showFolderSheet(pageIndex, folder)
                                        true
                                    }
                                } else {
                                    icons[idx].setImageDrawable(null)
                                    icons[idx].isVisible = false
                                }
                            }

                            if (folderApps.size > 4) {
                                f2B.fMoreBadge.isVisible = true
                                f2B.fMoreBadge.text = "+${folderApps.size - 4}"
                            } else {
                                f2B.fMoreBadge.isVisible = false
                            }

                            attachTileGestures(
                                f2B.root,
                                { DragController.DragItem.Folder(folder, pageIndex) }
                            ) { host.showFolderPopup(pageIndex, folder, f2B.root) }
                            f2B.root.setOnClickListener { host.showFolderSheet(pageIndex, folder) }

                            val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                            b.cellLayout.addView(f2B.root, lp)
                        } else {
                            // 1x1 Folder Icon
                            val f1B = ItemWorkspaceFolder1x1Binding.inflate(inflater, b.cellLayout, false)
                            f1B.folderLabel.text = folder.name
                            f1B.folderLabel.isVisible = prefs.showHomeLabels

                            val miniIcons = listOf(f1B.miniIcon1, f1B.miniIcon2, f1B.miniIcon3, f1B.miniIcon4)
                            miniIcons.forEachIndexed { idx, iv ->
                                val fApp = folderApps.getOrNull(idx)
                                if (fApp != null) {
                                    iv.setImageDrawable(fApp.icon)
                                    iv.isVisible = true
                                } else {
                                    iv.setImageDrawable(null)
                                    iv.isVisible = false
                                }
                            }

                            attachTileGestures(
                                f1B.root,
                                { DragController.DragItem.Folder(folder, pageIndex) }
                            ) { host.showFolderPopup(pageIndex, folder, f1B.root) }
                            f1B.root.setOnClickListener { host.showFolderSheet(pageIndex, folder) }

                            val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                            b.cellLayout.addView(f1B.root, lp)
                        }
                    }

                    WorkspaceItem.Type.MAX_APP -> {
                        val app = allAppsMap[item.appKey] ?: return@forEach
                        val maxB = ItemMaxAppBinding.inflate(inflater, b.cellLayout, false)
                        maxB.maxAppIcon.setImageDrawable(app.icon)
                        maxB.maxAppLabel.text = app.label
                        maxB.maxAppDot.background.mutate().setTint(accent)
                        maxB.root.setOnClickListener { host.launchApp(app, it) }
                        attachTileGestures(
                            maxB.root,
                            { DragController.DragItem.MaxApp(app, pageIndex) }
                        ) { host.showAppPopup(app, maxB.root, MainActivity.PopupOrigin.HOME) }
                        val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                        b.cellLayout.addView(maxB.root, lp)
                    }

                    WorkspaceItem.Type.BESPOKE -> {
                        val elId = item.elementId
                        val view = buildBespokeView(ctx, inflater, elId, pageIndex, accent, prefs, density)
                        if (view != null) {
                            val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                            b.cellLayout.addView(view, lp)
                        }
                    }

                    WorkspaceItem.Type.WIDGET -> {
                        val wId = item.widgetId
                        val hostView = host.createWidgetView(wId) ?: return@forEach
                        val info = hostView.appWidgetInfo
                        val fallbackDp = (info?.minHeight ?: DEFAULT_WIDGET_HEIGHT_DP)
                            .coerceIn(LauncherPrefs.MIN_WIDGET_HEIGHT_DP, LauncherPrefs.MAX_WIDGET_HEIGHT_DP)
                        val heightDp = prefs.widgetHeight(wId, fallbackDp)
                        val heightPx = (heightDp * density).toInt()
                        val card = FrameLayout(ctx).apply {
                            tag = wId
                            setBackgroundResource(R.drawable.bg_tile_dark)
                            clipToOutline = true
                            val pad = (6 * density).toInt()
                            setPadding(pad, pad, pad, pad)
                        }
                        (hostView.parent as? ViewGroup)?.removeView(hostView)
                        card.addView(hostView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx))
                        if (hostView is LauncherWidgetHostView) {
                            hostView.onLongPress = { host.showWidgetPopup(wId, pageIndex, card) }
                            hostView.onDragStart = { v, rawX, rawY ->
                                host.startDrag(DragController.DragItem.Widget(wId, pageIndex), card, rawX, rawY)
                            }
                        }
                        updateWidgetSize(hostView, heightDp)
                        val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                        b.cellLayout.addView(card, lp)
                    }
                }
            }

            // Empty state and background gestures
            val isEmpty = page.items.isEmpty()
            b.homeEmpty.isVisible = isEmpty

            val onCustomize = View.OnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                host.showCustomizeSheet()
                true
            }
            b.homeEmpty.setOnLongClickListener(onCustomize)
            b.pageContent.setOnLongClickListener(onCustomize)
            b.pageScroll.setOnLongClickListener(onCustomize)
            b.cellLayout.setOnLongClickListener(onCustomize)
        }

        private fun buildBespokeView(
            ctx: Context,
            inflater: LayoutInflater,
            elId: String,
            pageIndex: Int,
            accent: Int,
            prefs: LauncherPrefs,
            density: Float
        ): View? {
            return when (elId) {
                BespokeElement.CLOCK.id -> {
                    val clockB = WidgetClockBinding.inflate(inflater, b.cellLayout, false)
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
                    clockB.root
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
                    row
                }
                BespokeElement.WEATHER.id -> {
                    val weatherB = WidgetWeatherBinding.inflate(inflater, b.cellLayout, false)
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
                    weatherB.root
                }
                BespokeElement.QUICK_TOGGLES.id -> {
                    val togB = WidgetQuickTogglesBinding.inflate(inflater, b.cellLayout, false)
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
                    togB.root
                }
                BespokeElement.PEDOMETER.id -> {
                    val pedB = WidgetPedometerBinding.inflate(inflater, b.cellLayout, false)
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
                    pedB.root
                }
                BespokeElement.COMPASS.id -> {
                    val compB = WidgetCompassBinding.inflate(inflater, b.cellLayout, false)
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
                    compB.root
                }
                BespokeElement.SCREEN_TIME.id -> {
                    val stB = WidgetScreenTimeBinding.inflate(inflater, b.cellLayout, false)
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
                    stB.root
                }
                BespokeElement.STORAGE.id -> {
                    val storB = WidgetStorageBinding.inflate(inflater, b.cellLayout, false)
                    storB.storageView.accentColor = accent
                    storB.root.setOnClickListener {
                        AppLauncher.start(ctx, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), showError = false)
                    }
                    attachTileGestures(
                        storB.root,
                        { DragController.DragItem.Bespoke(BespokeElement.STORAGE, pageIndex) }
                    ) { host.showElementPopup(pageIndex, BespokeElement.STORAGE, storB.root) }
                    storB.root
                }
                BespokeElement.WORLD_CLOCK.id -> {
                    val wcB = WidgetWorldClockBinding.inflate(inflater, b.cellLayout, false)
                    wcB.worldClockView.accentColor = accent
                    wcB.worldClockView.updateTimes()
                    wcB.root.setOnClickListener { AppLauncher.openAlarms(ctx) }
                    attachTileGestures(
                        wcB.root,
                        { DragController.DragItem.Bespoke(BespokeElement.WORLD_CLOCK, pageIndex) }
                    ) { host.showElementPopup(pageIndex, BespokeElement.WORLD_CLOCK, wcB.root) }
                    wcB.root
                }
                BespokeElement.BATTERY_DOTS.id -> {
                    val batDotsB = WidgetBatteryDotsBinding.inflate(inflater, b.cellLayout, false)
                    batDotsB.batteryDotsView.accentColor = accent
                    batDotsB.root.setOnClickListener { AppLauncher.openBatterySettings(ctx) }
                    attachTileGestures(
                        batDotsB.root,
                        { DragController.DragItem.Bespoke(BespokeElement.BATTERY_DOTS, pageIndex) }
                    ) { host.showElementPopup(pageIndex, BespokeElement.BATTERY_DOTS, batDotsB.root) }
                    batDotsB.root
                }
                BespokeElement.QUICK_NOTE.id -> {
                    val quoteB = WidgetQuoteBinding.inflate(inflater, b.cellLayout, false)
                    quoteB.quoteDotsView.accentColor = accent
                    quoteB.quoteDotsView.quoteText = prefs.quickNote
                    quoteB.root.setOnClickListener { host.showEditNoteDialog() }
                    attachTileGestures(
                        quoteB.root,
                        { DragController.DragItem.Bespoke(BespokeElement.QUICK_NOTE, pageIndex) }
                    ) { host.showElementPopup(pageIndex, BespokeElement.QUICK_NOTE, quoteB.root) }
                    quoteB.root
                }
                BespokeElement.ANALOG_CLOCK.id -> {
                    val acB = WidgetAnalogClockBinding.inflate(inflater, b.cellLayout, false)
                    acB.analogClock.accentColor = accent
                    acB.root.setOnClickListener { AppLauncher.openAlarms(ctx) }
                    attachTileGestures(
                        acB.root,
                        { DragController.DragItem.Bespoke(BespokeElement.ANALOG_CLOCK, pageIndex) }
                    ) { host.showElementPopup(pageIndex, BespokeElement.ANALOG_CLOCK, acB.root) }
                    acB.root
                }
                BespokeElement.MEDIA_PLAYER.id -> {
                    val medB = WidgetMediaPlayerBinding.inflate(inflater, b.cellLayout, false)
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
                    medB.root
                }
                BespokeElement.STOPWATCH.id -> {
                    val swB = WidgetStopwatchBinding.inflate(inflater, b.cellLayout, false)
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
                    swB.root
                }
                BespokeElement.SYSTEM_MONITOR.id -> {
                    val monB = WidgetSystemMonitorBinding.inflate(inflater, b.cellLayout, false)
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
                    monB.root
                }
                BespokeElement.HABIT_TRACKER.id -> {
                    val habB = WidgetHabitTrackerBinding.inflate(inflater, b.cellLayout, false)
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
                    habB.root
                }
                BespokeElement.COUNTDOWN.id -> {
                    val cdB = WidgetCountdownBinding.inflate(inflater, b.cellLayout, false)
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
                    cdB.root
                }
                BespokeElement.SOUND_RECORDER.id -> {
                    val vrB = WidgetVoiceRecorderBinding.inflate(inflater, b.cellLayout, false)
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
                    vrB.root
                }
                else -> null
            }
        }

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
