package com.nothinglauncher

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.appwidget.AppWidgetHostView
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.FragmentHomeBinding
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LauncherViewModel by activityViewModels()
    private val host: MainActivity? get() = activity as? MainActivity

    private lateinit var homeAdapter: HomeIconAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private var dragMoved = false
    private var receiversRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = bindBattery(intent)
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (_binding == null) return
            binding.clockTile.clock.tick()
            bindDate(Calendar.getInstance())
            bindAlarm()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupInsets()
        setupTiles()
        setupGrid()
        setupDock()
        setupBottomBar()
        setupGestures()
        observeViewModel()
        applyPrefs()
    }

    override fun onStart() {
        super.onStart()
        registerReceivers()
        bindDate(Calendar.getInstance())
        bindAlarm()
        renderWidgets()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceivers()
    }

    // ---- Setup ---------------------------------------------------------------------------

    private fun setupInsets() {
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.homeContent.updatePadding(top = bars.top + (12 * density).toInt())
            binding.bottomBar.updatePadding(bottom = bars.bottom + (12 * density).toInt())
            insets
        }
        ViewCompat.requestApplyInsets(binding.homeRoot)
    }

    private fun setupTiles() {
        val clock = binding.clockTile
        clock.root.setOnClickListener { AppLauncher.openAlarms(requireContext()) }
        clock.clock.onMinuteChanged = { cal -> bindDate(cal) }
        binding.batteryTile.root.setOnClickListener { AppLauncher.openBatterySettings(requireContext()) }
        binding.calendarTile.root.setOnClickListener { AppLauncher.openCalendar(requireContext()) }

        // Long-press anywhere that isn't an app → customise.
        val customize = View.OnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            host?.showCustomizeSheet()
            true
        }
        binding.homeContent.setOnLongClickListener(customize)
        binding.bottomBar.setOnLongClickListener(customize)
        clock.root.setOnLongClickListener(customize)
        binding.batteryTile.root.setOnLongClickListener(customize)
        binding.calendarTile.root.setOnLongClickListener(customize)
    }

    private fun setupGrid() {
        homeAdapter = HomeIconAdapter(
            onAppClick = { app, v -> host?.launchApp(app, v) },
            onAppLongClick = { app, v ->
                dragMoved = false
                host?.showAppPopup(app, v, MainActivity.PopupOrigin.HOME)
            }
        )
        gridLayoutManager = GridLayoutManager(requireContext(), 4)
        binding.homeGrid.layoutManager = gridLayoutManager
        binding.homeGrid.adapter = homeAdapter

        val dragThreshold = 14 * resources.displayMetrics.density
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return homeAdapter.moveItem(vh.bindingAdapterPosition, target.bindingAdapterPosition).also {
                    if (it) dragMoved = true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    binding.homeGrid.parent?.requestDisallowInterceptTouchEvent(true)
                    viewHolder.itemView.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).start()
                }
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                // Once the finger really moves, it's a drag — not a menu request.
                if (isCurrentlyActive && hypot(dX, dY) > dragThreshold) host?.dismissPopup()
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                if (dragMoved) {
                    dragMoved = false
                    viewModel.saveHome(homeAdapter.getItems())
                }
            }

            override fun isLongPressDragEnabled(): Boolean = true
        })
        touchHelper.attachToRecyclerView(binding.homeGrid)
    }

    private fun setupDock() {
        binding.dock.onAppClick = { app, v -> host?.launchApp(app, v) }
        binding.dock.onAppLongClick = { app, v -> host?.showAppPopup(app, v, MainActivity.PopupOrigin.DOCK) }
    }

    private fun setupBottomBar() {
        binding.searchPill.setOnClickListener { host?.openDrawer(focusSearch = true) }
        binding.drawerButton.setOnClickListener { host?.openDrawer(focusSearch = false) }
    }

    private fun setupGestures() {
        val density = resources.displayMetrics.density
        binding.homeRoot.listener = object : SwipeGestureLayout.Listener {
            override fun canStartDrag(direction: Int, downX: Float, downY: Float): Boolean {
                if (host?.isPopupShowing() == true) return false
                return if (direction == SwipeGestureLayout.UP) {
                    downY >= binding.bottomBar.top || !binding.homeScroll.canScrollVertically(1)
                } else {
                    !binding.homeScroll.canScrollVertically(-1)
                }
            }

            override fun onDragStart(direction: Int) {
                if (direction == SwipeGestureLayout.UP) host?.beginDrawerDrag()
            }

            override fun onDrag(direction: Int, totalDy: Float) {
                if (direction == SwipeGestureLayout.UP) host?.updateDrawerDrag(totalDy)
            }

            override fun onDragEnd(direction: Int, totalDy: Float, velocityY: Float) {
                if (direction == SwipeGestureLayout.UP) {
                    host?.endDrawerDrag(velocityY)
                } else if (totalDy > 56 * density || (totalDy > 16 * density && velocityY > 800)) {
                    host?.performSwipeDown()
                }
            }
        }
    }

    private fun observeViewModel() {
        viewModel.homeApps.observe(viewLifecycleOwner) { apps ->
            homeAdapter.setItems(apps)
            updateEmptyState()
        }
        viewModel.dockApps.observe(viewLifecycleOwner) { apps ->
            binding.dock.setApps(apps)
            binding.dock.isVisible = apps.isNotEmpty()
        }
        viewModel.loaded.observe(viewLifecycleOwner) { updateEmptyState() }
    }

    private fun updateEmptyState() {
        if (_binding == null) return
        val empty = viewModel.loaded.value == true && homeAdapter.itemCount == 0
        binding.homeEmpty.isVisible = empty
        binding.homeGrid.isVisible = !empty
    }

    // ---- Prefs ---------------------------------------------------------------------------

    fun applyPrefs() {
        if (_binding == null) return
        val prefs = viewModel.prefs
        val columns = prefs.gridColumns
        gridLayoutManager.spanCount = columns
        homeAdapter.iconSizePx = resources.getDimensionPixelSize(
            if (columns >= 5) R.dimen.icon_size_compact else R.dimen.icon_size
        )
        homeAdapter.showLabels = prefs.showHomeLabels
        homeAdapter.labelShadow = prefs.background == LauncherPrefs.Background.WALLPAPER

        val accent = prefs.accentColor()
        val clock = binding.clockTile
        clock.clock.accentColor = accent
        clock.secondsTicker.accentColor = accent
        clock.secondsTicker.isVisible = prefs.showSeconds
        clock.liveDot.background.mutate().setTint(accent)
        binding.calendarTile.calDot.background.mutate().setTint(accent)
        binding.batteryTile.battery.accentColor = accent
        clock.clock.tick()
        bindDate(Calendar.getInstance())
    }

    // ---- Live data for the tiles --------------------------------------------------------

    private fun bindDate(cal: Calendar) {
        if (_binding == null) return
        val locale = Locale.getDefault()
        val clock = binding.clockTile
        clock.clockDate.text = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), cal)
        clock.clockMeridiem.text = if (clock.clock.is24Hour()) {
            getString(R.string.format_24h)
        } else {
            DateFormat.format("a", cal).toString().uppercase(locale)
        }

        val cTile = binding.calendarTile
        cTile.calMonth.text = DateFormat.format("MMM", cal).toString().uppercase(locale)
        cTile.calDay.text = cal.get(Calendar.DAY_OF_MONTH).toString()
        cTile.calWeekday.text = DateFormat.format("EEEE", cal)
    }

    private fun bindAlarm() {
        if (_binding == null) return
        val ctx = context ?: return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val next = try { am?.nextAlarmClock } catch (e: Exception) { null }
        binding.calendarTile.calAlarm.text = if (next == null) {
            getString(R.string.no_alarm)
        } else {
            val cal = Calendar.getInstance().apply { timeInMillis = next.triggerTime }
            val pattern = if (DateFormat.is24HourFormat(ctx)) "EEE HH:mm" else "EEE h:mm a"
            DateFormat.format(pattern, cal).toString().uppercase(Locale.getDefault())
        }
    }

    private fun bindBattery(intent: Intent?) {
        if (_binding == null || intent == null) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level >= 0 && scale > 0) binding.batteryTile.battery.level = level * 100 / scale
        binding.batteryTile.battery.charging = charging
        binding.batteryTile.chargingBolt.isVisible = charging
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceivers() {
        if (receiversRegistered) return
        val ctx = requireContext()
        val timeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        val sticky = ContextCompat.registerReceiver(
            ctx, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(ctx, timeReceiver, timeFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiversRegistered = true
        bindBattery(sticky)
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        val ctx = context ?: return
        try { ctx.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { ctx.unregisterReceiver(timeReceiver) } catch (_: Exception) {}
        receiversRegistered = false
    }

    // ---- Widgets ---------------------------------------------------------------------------

    fun renderWidgets() {
        if (_binding == null) return
        val activity = host ?: return
        val container = binding.widgetContainer
        val ids = viewModel.prefs.widgetIds
        val existing = (0 until container.childCount).mapNotNull { container.getChildAt(it).tag as? Int }
        if (existing == ids) return
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val valid = mutableListOf<Int>()
        ids.forEach { id ->
            val hostView = activity.createWidgetView(id) ?: return@forEach
            valid += id
            val info = hostView.appWidgetInfo
            val height = (info?.minHeight ?: (120 * density).toInt())
                .coerceIn((96 * density).toInt(), (360 * density).toInt())
            val card = FrameLayout(requireContext()).apply {
                tag = id
                setBackgroundResource(R.drawable.bg_tile_dark)
                clipToOutline = true
                val pad = (6 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            (hostView.parent as? ViewGroup)?.removeView(hostView)
            card.addView(hostView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.tile_gap)
            }
            container.addView(card, lp)
            if (hostView is LauncherWidgetHostView) {
                hostView.onLongPress = { activity.showWidgetPopup(id, card) }
            }
            updateWidgetSize(hostView, height)
        }
        if (valid != ids) viewModel.prefs.widgetIds = valid
    }

    @Suppress("DEPRECATION")
    private fun updateWidgetSize(view: AppWidgetHostView, heightPx: Int) {
        view.post {
            val d = resources.displayMetrics.density
            val wDp = (view.width / d).toInt().coerceAtLeast(1)
            val hDp = (heightPx / d).toInt()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    view.updateAppWidgetSize(Bundle(), listOf(android.util.SizeF(wDp.toFloat(), hDp.toFloat())))
                } else {
                    view.updateAppWidgetSize(null, wDp, hDp, wDp, hDp)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun scrollToTop() {
        if (_binding == null) return
        if (abs(binding.homeScroll.scrollY) > 0) binding.homeScroll.smoothScrollTo(0, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
