package com.nothinglauncher

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
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
import com.nothinglauncher.databinding.WidgetBatteryBinding
import com.nothinglauncher.databinding.WidgetCalendarBinding
import com.nothinglauncher.databinding.WidgetClockBinding
import com.nothinglauncher.databinding.WidgetCompassBinding
import com.nothinglauncher.databinding.WidgetPedometerBinding
import com.nothinglauncher.databinding.WidgetQuickTogglesBinding
import com.nothinglauncher.databinding.WidgetScreenTimeBinding
import com.nothinglauncher.databinding.WidgetStorageBinding
import com.nothinglauncher.databinding.WidgetWeatherBinding
import android.view.GestureDetector
import android.view.MotionEvent
import com.nothinglauncher.databinding.WidgetBatteryDotsBinding
import com.nothinglauncher.databinding.WidgetQuoteBinding
import com.nothinglauncher.databinding.WidgetWorldClockBinding
import java.util.Calendar
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
                        b.elementsContainer.addView(weatherB.root, elementLp(density))
                    }
                    BespokeElement.QUICK_TOGGLES.id -> {
                        val togB = WidgetQuickTogglesBinding.inflate(inflater, b.elementsContainer, false)
                        togB.togglesDot.background.mutate().setTint(accent)
                        togB.toggleWifi.setOnClickListener { AppLauncher.start(ctx, Intent(Settings.ACTION_WIFI_SETTINGS)) }
                        togB.toggleBt.setOnClickListener { AppLauncher.start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                        togB.toggleTorch.setOnClickListener {
                            toggleTorch(ctx, togB)
                        }
                        togB.toggleSound.setOnClickListener {
                            cycleSoundMode(ctx, togB)
                        }
                        togB.root.setOnLongClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            host.showElementPopup(pageIndex, BespokeElement.QUICK_TOGGLES, it)
                            true
                        }
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
                        b.elementsContainer.addView(quoteB.root, elementLp(density))
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
                b.foldersContainer.addView(fTile.root)
            }

            // 5. Setup App Grid
            gridAdapter = HomeIconAdapter(
                onAppClick = { app, v -> host.launchApp(app, v) },
                onAppLongClick = { app, v ->
                    dragMoved = false
                    host.showAppPopup(app, v, MainActivity.PopupOrigin.HOME)
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
            gridAdapter.setItems(viewModel.getAppsForPage(page))

            // Attach touch helper for drag-and-drop
            val dragThreshold = 14 * density
            val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
            ) {
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
                        viewModel.savePageApps(pageIndex, gridAdapter.getItems())
                    }
                }
                override fun isLongPressDragEnabled(): Boolean = true
            })
            touchHelper.attachToRecyclerView(b.homeGrid)

            // 6. Empty State
            val isEmpty = page.elements.isEmpty() && page.widgetIds.isEmpty() && page.appKeys.isEmpty() && page.enlargedApps.isEmpty() && page.folders.isEmpty()
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
                    if (!host.isPopupShowing()) {
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
