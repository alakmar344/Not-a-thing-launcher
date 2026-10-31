package com.nothinglauncher

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        unbindAll()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun homeIconSizePx(ctx: Context, prefs: LauncherPrefs): Int {
        val base = ctx.resources.getDimensionPixelSize(
            if (prefs.gridColumns >= 5) R.dimen.icon_size_compact else R.dimen.icon_size
        )
        val scale = when (prefs.iconSize) {
            LauncherPrefs.IconSize.SMALL -> 0.82f
            LauncherPrefs.IconSize.MEDIUM -> 1.0f
            LauncherPrefs.IconSize.LARGE -> 1.18f
        }
        return (base * scale).toInt()
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
            b.cellLayout.onChildNeedsRows = { child, rows ->
                (child.tag as? String)?.let { id -> viewModel.ensureMinRows(pageIndex, id, rows) }
            }

            /**
             * Adds an item view to the grid. A rebind while that item is being dragged (page added
             * by an edge flip, widget refresh...) must keep the original hidden in place.
             */
            fun addItemView(view: View, lp: CellLayout.LayoutParams, itemId: String) {
                b.cellLayout.addView(view, lp)
                host.dragController.adoptHiddenSource(pageIndex, itemId, view)
            }

            page.items.forEach { item ->
                when (item.type) {
                    WorkspaceItem.Type.APP -> {
                        val app = allAppsMap[item.appKey] ?: return@forEach
                        val appB = ItemAppBinding.inflate(inflater, b.cellLayout, false)
                        appB.appIcon.setImageDrawable(app.icon)
                        appB.appLabel.text = app.label
                        appB.appLabel.isVisible = prefs.showHomeLabels
                        appB.appIcon.contentDescription = app.label
                        HomeIconAdapter.applyLabelShadow(appB, prefs.background == LauncherPrefs.Background.WALLPAPER)
                        val iconPx = homeIconSizePx(ctx, prefs)
                        appB.appIcon.layoutParams = appB.appIcon.layoutParams.apply { width = iconPx; height = iconPx }
                        val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                        attachTileGestures(
                            appB.root,
                            { DragController.DragItem.App(app, DragController.DragSource.HOME_PAGE, pageIndex, item.id) }
                        ) { host.showAppPopup(app, appB.root, MainActivity.PopupOrigin.HOME) }
                        appB.root.setOnClickListener { host.launchApp(app, it) }
                        addItemView(appB.root, lp, item.id)
                    }

                    WorkspaceItem.Type.FOLDER -> {
                        val folder = item.folder ?: return@forEach
                        val folderApps = folder.appKeys.mapNotNull { allAppsMap[it] }

                        if (folder.isEnlarged) {
                            // 2x2 Enlarged Folder
                            val f2B = ItemWorkspaceFolder2x2Binding.inflate(inflater, b.cellLayout, false)
                            f2B.folder2x2Name.text = folder.name
                            f2B.folder2x2Count.text = ctx.resources.getQuantityString(R.plurals.folder_app_count, folderApps.size, folderApps.size)
                            f2B.root.contentDescription = folder.name
                            f2B.folder2x2Dot.background.mutate().setTint(accent)

                            val slots = listOf(f2B.fSlot1, f2B.fSlot2, f2B.fSlot3, f2B.fSlot4)
                            val icons = listOf(f2B.fIcon1, f2B.fIcon2, f2B.fIcon3, f2B.fIcon4)

                            slots.forEachIndexed { idx, slot ->
                                val fApp = folderApps.getOrNull(idx)
                                if (fApp != null) {
                                    icons[idx].setImageDrawable(fApp.icon)
                                    icons[idx].isVisible = true
                                    slot.contentDescription = fApp.label
                                    LongPressDragHelper.attach(
                                        slot,
                                        onClick = { host.launchApp(fApp, it) },
                                        onLongPress = { host.showFolderPopup(pageIndex, folder, f2B.root) },
                                        onDragStart = { _, rawX, rawY ->
                                            host.startDrag(
                                                DragController.DragItem.Folder(folder, pageIndex, item.id),
                                                f2B.root, rawX, rawY
                                            )
                                        }
                                    )
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
                                { DragController.DragItem.Folder(folder, pageIndex, item.id) }
                            ) { host.showFolderPopup(pageIndex, folder, f2B.root) }
                            f2B.root.setOnClickListener { host.showFolderSheet(pageIndex, folder) }

                            val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                            addItemView(f2B.root, lp, item.id)
                        } else {
                            // 1x1 Folder Icon
                            val f1B = ItemWorkspaceFolder1x1Binding.inflate(inflater, b.cellLayout, false)
                            f1B.folderLabel.text = folder.name
                            f1B.folderLabel.isVisible = prefs.showHomeLabels
                            f1B.root.contentDescription = folder.name

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
                                { DragController.DragItem.Folder(folder, pageIndex, item.id) }
                            ) { host.showFolderPopup(pageIndex, folder, f1B.root) }
                            f1B.root.setOnClickListener { host.showFolderSheet(pageIndex, folder) }

                            val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                            addItemView(f1B.root, lp, item.id)
                        }
                    }

                    WorkspaceItem.Type.MAX_APP -> {
                        val app = allAppsMap[item.appKey] ?: return@forEach
                        val maxB = ItemMaxAppBinding.inflate(inflater, b.cellLayout, false)
                        maxB.maxAppIcon.setImageDrawable(app.icon)
                        maxB.maxAppLabel.text = app.label
                        maxB.root.contentDescription = app.label
                        maxB.maxAppDot.background.mutate().setTint(accent)
                        maxB.root.setOnClickListener { host.launchApp(app, it) }
                        attachTileGestures(
                            maxB.root,
                            { DragController.DragItem.MaxApp(app, pageIndex, item.id) }
                        ) { host.showAppPopup(app, maxB.root, MainActivity.PopupOrigin.HOME) }
                        val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                        addItemView(maxB.root, lp, item.id)
                    }

                    WorkspaceItem.Type.BESPOKE -> {
                        val elId = item.elementId
                        val view = buildBespokeView(ctx, inflater, elId, pageIndex, item.id, accent, prefs, density)
                        if (view != null) {
                            view.tag = item.id
                            val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                            lp.fitContent = true
                            addItemView(view, lp, item.id)
                        }
                    }

                    WorkspaceItem.Type.WIDGET -> {
                        val wId = item.widgetId
                        val hostView = host.createWidgetView(wId) ?: return@forEach
                        val card = FrameLayout(ctx).apply {
                            tag = item.id
                            setBackgroundResource(R.drawable.bg_tile_dark)
                            clipToOutline = true
                            val pad = (6 * density).toInt()
                            setPadding(pad, pad, pad, pad)
                        }
                        (hostView.parent as? ViewGroup)?.removeView(hostView)
                        card.addView(
                            hostView,
                            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        )
                        if (hostView is LauncherWidgetHostView) {
                            hostView.onLongPress = { host.showWidgetPopup(wId, pageIndex, card) }
                            hostView.onDragStart = { _, rawX, rawY ->
                                host.startDrag(DragController.DragItem.Widget(wId, pageIndex, item.id), card, rawX, rawY)
                            }
                            cleanups += {
                                hostView.onLongPress = null
                                hostView.onDragStart = null
                            }
                        }
                        // Tell the provider the real size it is drawn at (dp), like the stock host.
                        // Host views are cached across binds, so the listener must be removed again.
                        val sizeListener = View.OnLayoutChangeListener { v, l, t, r, btm, ol, ot, or, ob ->
                            if (r - l != or - ol || btm - t != ob - ot) {
                                updateWidgetSize(v, ((r - l) / density).toInt(), ((btm - t) / density).toInt())
                            }
                        }
                        hostView.addOnLayoutChangeListener(sizeListener)
                        cleanups += { hostView.removeOnLayoutChangeListener(sizeListener) }
                        val lp = CellLayout.LayoutParams(item.cellX, item.cellY, item.spanX, item.spanY)
                        addItemView(card, lp, item.id)
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
            itemId: String,
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
                        { DragController.DragItem.Bespoke(BespokeElement.CLOCK, pageIndex, itemId) }
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
                        { DragController.DragItem.Bespoke(BespokeElement.BATTERY_CALENDAR, pageIndex, itemId) },
                        row
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
                        { DragController.DragItem.Bespoke(BespokeElement.BATTERY_CALENDAR, pageIndex, itemId) },
                        row
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
                        { DragController.DragItem.Bespoke(BespokeElement.WEATHER, pageIndex, itemId) }
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
                    togB.soundLabel.text = soundLabel(ctx, audioManager?.ringerMode)
                    applyTorchState(ctx, togB)
                    attachTileGestures(
                        togB.root,
                        { DragController.DragItem.Bespoke(BespokeElement.QUICK_TOGGLES, pageIndex, itemId) }
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
                        { DragController.DragItem.Bespoke(BespokeElement.PEDOMETER, pageIndex, itemId) }
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
                        { DragController.DragItem.Bespoke(BespokeElement.COMPASS, pageIndex, itemId) }
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
                        { DragController.DragItem.Bespoke(BespokeElement.SCREEN_TIME, pageIndex, itemId) }
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
                        { DragController.DragItem.Bespoke(BespokeElement.STORAGE, pageIndex, itemId) }
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
                        { DragController.DragItem.Bespoke(BespokeElement.WORLD_CLOCK, pageIndex, itemId) }
                    ) { host.showElementPopup(pageIndex, BespokeElement.WORLD_CLOCK, wcB.root) }
                    wcB.root
                }
                BespokeElement.BATTERY_DOTS.id -> {
                    val batDotsB = WidgetBatteryDotsBinding.inflate(inflater, b.cellLayout, false)
                    batDotsB.batteryDotsView.accentColor = accent
                    batDotsB.root.setOnClickListener { AppLauncher.openBatterySettings(ctx) }
                    attachTileGestures(
                        batDotsB.root,
                        { DragController.DragItem.Bespoke(BespokeElement.BATTERY_DOTS, pageIndex, itemId) }
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
                        { DragController.DragItem.Bespoke(BespokeElement.QUICK_NOTE, pageIndex, itemId) }
                    ) { host.showElementPopup(pageIndex, BespokeElement.QUICK_NOTE, quoteB.root) }
                    quoteB.root
                }
                BespokeElement.ANALOG_CLOCK.id -> {
                    val acB = WidgetAnalogClockBinding.inflate(inflater, b.cellLayout, false)
                    acB.analogClock.accentColor = accent
                    acB.root.setOnClickListener { AppLauncher.openAlarms(ctx) }
                    attachTileGestures(
                        acB.root,
                        { DragController.DragItem.Bespoke(BespokeElement.ANALOG_CLOCK, pageIndex, itemId) }
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
                        { DragController.DragItem.Bespoke(BespokeElement.MEDIA_PLAYER, pageIndex, itemId) }
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
                        { DragController.DragItem.Bespoke(BespokeElement.STOPWATCH, pageIndex, itemId) }
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
                        { DragController.DragItem.Bespoke(BespokeElement.SYSTEM_MONITOR, pageIndex, itemId) }
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
                            DragController.DragItem.Bespoke(BespokeElement.HABIT_TRACKER, pageIndex, itemId),
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
                        { DragController.DragItem.Bespoke(BespokeElement.COUNTDOWN, pageIndex, itemId) }
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
                        { DragController.DragItem.Bespoke(BespokeElement.SOUND_RECORDER, pageIndex, itemId) }
                    ) { host.showElementPopup(pageIndex, BespokeElement.SOUND_RECORDER, vrB.root) }
                    vrB.root
                }
                else -> null
            }
        }

        private fun attachTileGestures(
            view: View,
            getItem: () -> DragController.DragItem,
            dragView: View? = null,
            onMenu: () -> Unit
        ) {
            LongPressDragHelper.attach(
                view,
                onClick = null,
                onLongPress = { onMenu() },
                onDragStart = { v, rawX, rawY -> host.startDrag(getItem(), dragView ?: v, rawX, rawY) }
            )
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

        private fun applyTorchState(context: Context, binding: WidgetQuickTogglesBinding) {
            binding.torchLabel.text = if (torchEnabled) {
                context.getString(R.string.torch_on)
            } else {
                context.getString(R.string.toggle_torch).uppercase()
            }
            binding.torchDisc.background.mutate().setTint(
                if (torchEnabled) {
                    viewModel.prefs.accentColor()
                } else {
                    ContextCompat.getColor(context, R.color.surface_raised)
                }
            )
        }

        private fun toggleTorch(context: Context, binding: WidgetQuickTogglesBinding) {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            try {
                // Use a camera that actually has a flash unit (the first id may not).
                val id = cm.cameraIdList.firstOrNull { camId ->
                    cm.getCameraCharacteristics(camId)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: run {
                    AppLauncher.toast(context, R.string.cannot_open)
                    return
                }
                cm.setTorchMode(id, !torchEnabled)
                torchEnabled = !torchEnabled
                applyTorchState(context, binding)
            } catch (e: Exception) {
                AppLauncher.toast(context, R.string.cannot_open)
            }
        }

        private fun soundLabel(context: Context, ringerMode: Int?): String = when (ringerMode) {
            AudioManager.RINGER_MODE_VIBRATE -> context.getString(R.string.sound_vibrate)
            AudioManager.RINGER_MODE_SILENT -> context.getString(R.string.sound_silent)
            else -> context.getString(R.string.sound_normal)
        }

        private fun cycleSoundMode(context: Context, binding: WidgetQuickTogglesBinding) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val next = when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            try {
                am.ringerMode = next
            } catch (_: SecurityException) {
                // Switching into/out of silent needs Do Not Disturb access on Android 7+.
                AppLauncher.start(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), showError = false)
            } catch (_: Exception) {
            }
            // Show the mode the system actually applied, not the one we asked for.
            binding.soundLabel.text = soundLabel(context, am.ringerMode)
        }

        private fun updateWidgetSize(view: View, widthDp: Int, heightDp: Int) {
            if (widthDp <= 0 || heightDp <= 0) return
            try {
                val opts = Bundle().apply {
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
                }
                (view as? android.appwidget.AppWidgetHostView)?.updateAppWidgetOptions(opts)
            } catch (_: Exception) {
            }
        }
    }

}
