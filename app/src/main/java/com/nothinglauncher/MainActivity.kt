package com.nothinglauncher

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.nothinglauncher.databinding.ActivityMainBinding
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    enum class PopupOrigin { HOME, DOCK, DRAWER }

    private lateinit var binding: ActivityMainBinding
    val viewModel: LauncherViewModel by viewModels()
    val prefs: LauncherPrefs get() = viewModel.prefs

    private lateinit var widgetHost: WidgetHostManager
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var popup: AppPopup
    private var openDialog: Dialog? = null

    /** Real microphone recorder shared by every Voice Memo element on screen. */
    lateinit var voiceRecorder: VoiceRecorder
        private set
    private var memoPlayer: MediaPlayer? = null
    private var recordAfterPermission = false
    private var pendingTorchAction: (() -> Unit)? = null

    private var appliedSettingsVersion = -1

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingTorchAction
        pendingTorchAction = null
        if (granted) {
            action?.invoke()
        } else {
            AppLauncher.toast(this, R.string.camera_denied)
        }
    }

    private val stepPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) AppLauncher.toast(this, R.string.pedometer_permission_denied)
        StepRepository.refresh()
        homeFragment?.renderWidgets()
    }

    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (recordAfterPermission) toggleVoiceMemo()
        } else {
            AppLauncher.toast(this, R.string.recorder_mic_denied)
        }
        recordAfterPermission = false
        homeFragment?.renderWidgets()
    }

    val dragController: DragController by lazy { DragController(this, binding) }
    val currentPageIndex: Int get() = homeFragment?.currentPageIndex ?: 0

    // ---- Drawer state ------------------------------------------------------------------
    private var drawerProgress = 0f
    private var dragStartProgress = 0f
    private var drawerAnimator: ValueAnimator? = null
    private var pendingFocusSearch = false
    private var focusRequested = false
    private val settleInterpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    val isDrawerOpen: Boolean get() = drawerProgress > 0.5f

    private val homeFragment: HomeFragment?
        get() = supportFragmentManager.findFragmentById(R.id.homeContainer) as? HomeFragment
    private val drawerFragment: AppDrawerFragment?
        get() = supportFragmentManager.findFragmentById(R.id.drawerContainer) as? AppDrawerFragment

    // ---- Widget binding ------------------------------------------------------------------
    private var pendingWidgetId = -1

    private val bindWidgetLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
        val info = if (id != -1) appWidgetManager.getAppWidgetInfo(id) else null
        if (result.resultCode == Activity.RESULT_OK && info != null) {
            configureOrAddWidget(id, info)
        } else {
            discardPendingWidget()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeMode()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        widgetHost = WidgetHostManager(this)
        appWidgetManager = AppWidgetManager.getInstance(this)
        MediaSessionRepository.start(applicationContext)
        voiceRecorder = VoiceRecorder(applicationContext)
        popup = AppPopup(this)
        pendingWidgetId = savedInstanceState?.getInt(KEY_PENDING_WIDGET, -1) ?: -1

        // First run: the welcome tour also offers the "set as default launcher" flow.
        // Existing installs migrated by LauncherPrefs skip it and keep their layout.
        if (!prefs.onboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
            return
        }

        if (supportFragmentManager.findFragmentById(R.id.homeContainer) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.homeContainer, HomeFragment())
                .replace(R.id.drawerContainer, AppDrawerFragment())
                .commitNow()
        }

        binding.root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) applyDrawerProgress(drawerProgress)
        }
        applyDrawerProgress(0f)
        applyBackground()
        setupBackHandler()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.dropZoneInclude.dropZoneBar) { v, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top
            (v.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                val wanted = top + (8 * resources.displayMetrics.density).toInt()
                if (lp.topMargin != wanted) {
                    lp.topMargin = wanted
                    v.layoutParams = lp
                }
            }
            insets
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PENDING_WIDGET, pendingWidgetId)
    }

    override fun onStart() {
        super.onStart()
        try {
            widgetHost.startListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        if (appliedSettingsVersion != prefs.settingsVersion) {
            appliedSettingsVersion = prefs.settingsVersion
            applyAllPrefs()
        }
        // Re-sync the media and weather elements after returning from permission screens
        // or a long time in the background.
        MediaSessionRepository.refresh()
        if (viewModel.workspacePages.value.orEmpty().any { BespokeElement.WEATHER.id in it.elements }) {
            WeatherRepository.refresh(this, prefs) { }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            widgetHost.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (!isChangingConfigurations) {
            dragController.cancel()
            dismissPopup()
            if (drawerProgress > 0f) setDrawerProgressImmediately(0f)
            // Android silences the microphone for background apps; finish the memo now so
            // it is saved instead of recording silence.
            if (::voiceRecorder.isInitialized && voiceRecorder.isRecording) {
                try { voiceRecorder.stop() } catch (_: Exception) {}
            }
            stopMemoPlayback()
        }
    }

    override fun onDestroy() {
        openDialog?.dismiss()
        openDialog = null
        popup.dismiss()
        if (::voiceRecorder.isInitialized) {
            try { voiceRecorder.cancel() } catch (_: Exception) {}
        }
        memoPlayer?.release()
        memoPlayer = null
        widgetHost.clearViews()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            dragController.cancel()
            dismissPopup()
            openDialog?.dismiss()
            if (drawerProgress > 0f) closeDrawer() else homeFragment?.scrollToTop()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (dragController.isDragging) {
            if (dragController.needsHierarchyCancel) {
                // The drag owns the gesture now: let every view reset its pressed/long-press
                // state exactly once, like Launcher3's DragLayer does.
                dragController.needsHierarchyCancel = false
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            if (dragController.onTouchEvent(ev)) return true
        }
        return super.dispatchTouchEvent(ev)
    }

    fun startDrag(item: DragController.DragItem, view: View, rawX: Float, rawY: Float) {
        dragController.startDrag(item, view, rawX, rawY)
    }

    /** Flips one page during a drag. Returns the index of a page created for it, or -1. */
    fun performPageFlip(direction: Int): Int {
        val current = currentPageIndex
        val pages = viewModel.workspacePages.value.orEmpty()
        val target = current + direction
        if (target in pages.indices) {
            homeFragment?.scrollToPage(target)
        } else if (direction > 0 && target == pages.size && pages.lastOrNull()?.items?.isNotEmpty() == true) {
            // Stock: dragging past the last non-empty page offers a fresh empty page.
            val newIdx = viewModel.addPage()
            homeFragment?.scrollToPage(newIdx)
            return newIdx
        }
        return -1
    }

    /** Shifts the workspace grid below [topPx] while a drag is in progress (spring-loaded). */
    fun setSpringLoaded(enabled: Boolean, topPx: Int) {
        homeFragment?.setSpringLoaded(enabled, topPx)
    }

    fun isPointOverDock(rawX: Float, rawY: Float): Boolean =
        homeFragment?.isPointOverDock(rawX, rawY) ?: false

    fun showDockDropTarget(rawX: Float) {
        homeFragment?.showDockDropTarget(rawX)
    }

    fun clearDockDropTarget() {
        homeFragment?.clearDockDropTarget()
    }

    fun handleDockDrop(
        app: AppInfo,
        fromSource: DragController.DragSource,
        fromPage: Int,
        rawX: Float
    ): Boolean = homeFragment?.handleDockDrop(app, fromSource, fromPage, rawX) ?: false

    fun removeWidgetHost(id: Int) {
        viewModel.removeWidget(id)
        try { widgetHost.deleteAppWidgetId(id) } catch (_: Exception) {}
        homeFragment?.renderWidgets()
    }

    fun performHapticFeedback(feedbackConstant: Int) {
        binding.root.performHapticFeedback(feedbackConstant)
    }

    // ---- Element permissions ----------------------------------------------------------------

    /** Runs [action] once CAMERA is granted; the torch toggle on a fresh install needs it. */
    fun withCameraPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingTorchAction = action
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Requests ACTIVITY_RECOGNITION so the pedometer element counts real steps. */
    fun requestStepPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || LauncherSystem.hasActivityRecognition(this)) {
            StepRepository.refresh()
            homeFragment?.renderWidgets()
            return
        }
        stepPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    fun handleWorkspaceDrop(
        item: DragController.DragItem,
        centerX: Float,
        centerY: Float,
        rawX: Float,
        rawY: Float
    ): Boolean = homeFragment?.handleWorkspaceDrop(item, centerX, centerY, rawX, rawY) ?: false

    fun updateWorkspaceDropHover(item: DragController.DragItem, centerX: Float, centerY: Float, rawX: Float, rawY: Float) {
        homeFragment?.updateDropHover(item, centerX, centerY, rawX, rawY)
    }

    fun clearWorkspaceDropTarget() {
        homeFragment?.clearWorkspaceDropTarget()
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    dragController.isDragging -> dragController.cancel()
                    popup.isShowing -> dismissPopup()
                    drawerProgress > 0f -> closeDrawer()
                    else -> homeFragment?.scrollToTop()
                }
            }
        })
    }

    // ---- Settings ------------------------------------------------------------------------

    private fun applyThemeMode() {
        val target = when (prefs.themeMode) {
            LauncherPrefs.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            LauncherPrefs.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            LauncherPrefs.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    private fun applyAllPrefs() {
        applyThemeMode()
        viewModel.onSettingsChanged()
        applyBackground()
        homeFragment?.applyPrefs()
        homeFragment?.renderWidgets()
        drawerFragment?.applyPrefs()
    }

    private fun applyBackground() {
        val density = resources.displayMetrics.density
        when (prefs.background) {
            LauncherPrefs.Background.WALLPAPER -> {
                window.setFormat(PixelFormat.TRANSLUCENT)
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setBackgroundDrawable(ColorDrawable(0))
                binding.root.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        ContextCompat.getColor(this, R.color.wallpaper_scrim_top),
                        0x00000000,
                        0x00000000,
                        ContextCompat.getColor(this, R.color.wallpaper_scrim_bottom)
                    )
                )
            }
            LauncherPrefs.Background.BLACK -> {
                window.setFormat(PixelFormat.OPAQUE)
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                val bg = ContextCompat.getColor(this, R.color.nothing_bg)
                window.setBackgroundDrawable(ColorDrawable(bg))
                binding.root.background = ColorDrawable(bg)
            }
            LauncherPrefs.Background.DOTS -> {
                window.setFormat(PixelFormat.OPAQUE)
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                val bg = ContextCompat.getColor(this, R.color.nothing_bg)
                val dot = ContextCompat.getColor(this, R.color.dot_off)
                window.setBackgroundDrawable(ColorDrawable(bg))
                binding.root.background = DotGridDrawable(density, backgroundColor = bg, dotColor = dot)
            }
        }
    }

    fun openSettings() {
        dismissPopup()
        AppLauncher.start(this, Intent(this, SettingsActivity::class.java))
    }

    // ---- Drawer ----------------------------------------------------------------------------

    fun openDrawer(focusSearch: Boolean) {
        dismissPopup()
        pendingFocusSearch = focusSearch || prefs.autoKeyboard
        focusRequested = false
        animateDrawerTo(1f, 0f)
    }

    fun closeDrawer() {
        drawerFragment?.hideKeyboard()
        animateDrawerTo(0f, 0f)
    }

    fun beginDrawerDrag() {
        dismissPopup()
        drawerAnimator?.cancel()
        dragStartProgress = drawerProgress
    }

    fun updateDrawerDrag(totalDy: Float) {
        val h = binding.root.height.takeIf { it > 0 } ?: return
        applyDrawerProgress(dragStartProgress - totalDy / (h * 0.9f))
    }

    fun endDrawerDrag(velocityY: Float) {
        val flingThreshold = 900 * resources.displayMetrics.density / 2.6f
        val opening = dragStartProgress < 0.5f
        val target = when {
            velocityY < -flingThreshold -> 1f
            velocityY > flingThreshold -> 0f
            opening -> if (drawerProgress > 0.3f) 1f else 0f
            else -> if (drawerProgress < 0.7f) 0f else 1f
        }
        if (target == 1f && dragStartProgress < 0.5f) {
            pendingFocusSearch = prefs.autoKeyboard
            focusRequested = false
        }
        animateDrawerTo(target, velocityY)
    }

    private fun animateDrawerTo(target: Float, velocityY: Float) {
        drawerAnimator?.cancel()
        val start = drawerProgress
        if (start == target) {
            applyDrawerProgress(target)
            onDrawerSettled(target)
            return
        }
        val distance = abs(target - start)
        val speedFactor = (abs(velocityY) / 6000f).coerceIn(0f, 0.5f)
        drawerAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = ((180 + 240 * distance) * (1f - speedFactor)).toLong().coerceAtLeast(120)
            interpolator = settleInterpolator
            addUpdateListener { applyDrawerProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onDrawerSettled(target)
                }
            })
            start()
        }
    }

    private fun setDrawerProgressImmediately(p: Float) {
        drawerAnimator?.cancel()
        applyDrawerProgress(p)
        onDrawerSettled(p)
    }

    private fun onDrawerSettled(p: Float) {
        drawerAnimator = null
        if (p == 0f) {
            drawerFragment?.onDrawerClosed()
            pendingFocusSearch = false
            focusRequested = false
        }
    }

    private fun applyDrawerProgress(p: Float) {
        drawerProgress = p.coerceIn(0f, 1f)
        val h = binding.root.height.toFloat().takeIf { it > 0f } ?: resources.displayMetrics.heightPixels.toFloat()
        val drawer = binding.drawerContainer
        val home = binding.homeContainer
        drawer.translationY = (1f - drawerProgress) * h * 0.92f
        drawer.alpha = (drawerProgress * 1.6f).coerceAtMost(1f)
        drawer.visibility = if (drawerProgress > 0f) View.VISIBLE else View.INVISIBLE

        if (pendingFocusSearch && !focusRequested && drawerProgress > 0f) {
            focusRequested = true
            drawerFragment?.onDrawerOpening(true)
        }

        val scale = 1f - 0.06f * drawerProgress
        home.scaleX = scale
        home.scaleY = scale
        home.alpha = 1f - 0.75f * drawerProgress
        home.importantForAccessibility = if (drawerProgress > 0.5f)
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = 28f * resources.displayMetrics.density * drawerProgress
            home.setRenderEffect(
                if (radius >= 1f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
            )
        }
    }

    // ---- App actions -----------------------------------------------------------------------

    fun launchApp(app: AppInfo, source: View) {
        dismissPopup()
        if (AppLauncher.launch(this, app, source)) {
            binding.root.postDelayed({ viewModel.recordLaunch(app) }, 450)
        }
    }

    fun isPopupShowing() = popup.isShowing

    fun dismissPopup() = popup.dismiss()

    fun showAppPopup(app: AppInfo, anchor: View, origin: PopupOrigin) {
        val actions = mutableListOf<AppPopup.Action>()
        if (viewModel.isOnHome(app)) {
            val pageIdx = viewModel.pageIndexForApp(app)
            val pagesCount = viewModel.workspacePages.value?.size ?: 1

            // Max Icon Enlarge / Shrink
            val isEnlarged = viewModel.isAppEnlarged(pageIdx, app.key)
            if (isEnlarged) {
                actions += AppPopup.Action(R.drawable.ic_minimize, getString(R.string.action_shrink)) {
                    viewModel.toggleEnlargeApp(pageIdx, app.key)
                }
            } else {
                actions += AppPopup.Action(R.drawable.ic_maximize, getString(R.string.action_enlarge)) {
                    viewModel.toggleEnlargeApp(pageIdx, app.key)
                }
            }

            // Add to folder
            actions += AppPopup.Action(R.drawable.ic_folder, getString(R.string.action_add_to_folder)) {
                showAddToFolderDialog(pageIdx, app)
            }

            if (pageIdx > 0) {
                actions += AppPopup.Action(R.drawable.ic_back, getString(R.string.move_to_prev_page)) {
                    viewModel.moveAppBetweenPages(app, pageIdx, pageIdx - 1)
                    homeFragment?.scrollToPage(pageIdx - 1)
                }
            }
            if (pageIdx in 0 until pagesCount - 1) {
                actions += AppPopup.Action(R.drawable.ic_chevron_right, getString(R.string.move_to_next_page)) {
                    viewModel.moveAppBetweenPages(app, pageIdx, pageIdx + 1)
                    homeFragment?.scrollToPage(pageIdx + 1)
                }
            }
            actions += AppPopup.Action(R.drawable.ic_unpin, getString(R.string.remove_from_home)) {
                viewModel.removeFromHome(app)
            }
        } else {
            actions += AppPopup.Action(R.drawable.ic_pin, getString(R.string.pin_to_home)) {
                val page = homeFragment?.currentPageIndex ?: 0
                viewModel.pinToPage(page, app)
            }
        }

        if (viewModel.isInDock(app)) {
            actions += AppPopup.Action(R.drawable.ic_undock, getString(R.string.remove_from_dock)) { viewModel.removeFromDock(app) }
        } else {
            actions += AppPopup.Action(R.drawable.ic_dock, getString(R.string.add_to_dock)) {
                if (!viewModel.addToDock(app)) AppLauncher.toast(this, R.string.dock_full)
            }
        }
        actions += AppPopup.Action(R.drawable.ic_info, getString(R.string.app_info)) { AppLauncher.openAppInfo(this, app, anchor) }
        if (origin == PopupOrigin.DRAWER) {
            actions += AppPopup.Action(R.drawable.ic_hide, getString(R.string.hide_app)) {
                viewModel.hide(app)
                appliedSettingsVersion = prefs.settingsVersion
                android.widget.Toast.makeText(this, getString(R.string.app_hidden, app.label), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        if (!app.isSystemApp) {
            actions += AppPopup.Action(R.drawable.ic_delete, getString(R.string.uninstall), destructive = true) {
                AppLauncher.uninstall(this, app)
            }
        }
        popup.show(
            anchor = anchor,
            title = app.label,
            subtitle = app.packageName,
            icon = app.icon,
            shortcuts = AppLauncher.shortcuts(this, app),
            actions = actions,
            onShortcut = { s, v -> AppLauncher.startShortcut(this, s, v) }
        )
    }

    fun performSwipeDown() {
        when (prefs.swipeDown) {
            LauncherPrefs.SwipeDown.NOTIFICATIONS -> {
                if (!AppLauncher.expandNotifications(this)) {
                    AppLauncher.toast(this, R.string.swipe_needs_accessibility)
                }
            }
            LauncherPrefs.SwipeDown.SEARCH -> openDrawer(focusSearch = true)
        }
    }

    fun lockScreen() {
        if (!prefs.doubleTapToSleep) return
        // Global action through the optional accessibility service is the only native,
        // USER-present path to lock the screen. Instruct instead of failing silently.
        if (!LauncherSystem.lockScreen()) {
            AppLauncher.toast(this, R.string.double_tap_needs_accessibility)
        }
    }

    // ---- Voice memo element ----------------------------------------------------------------

    /** Tap on a Voice Memo tile: start or finish a real microphone recording. */
    fun toggleVoiceMemo() {
        if (!::voiceRecorder.isInitialized) {
            voiceRecorder = VoiceRecorder(applicationContext)
        }
        val recorder = voiceRecorder
        if (recorder.isRecording) {
            val saved = try { recorder.stop() } catch (_: Exception) { null }
            if (saved != null) {
                AppLauncher.toast(this, R.string.recorder_saved)
            } else {
                AppLauncher.toast(this, R.string.recorder_stopped)
            }
        } else {
            if (LauncherSystem.hasRecordAudio(this)) {
                val started = try { recorder.start() } catch (_: Throwable) { false }
                if (!started) AppLauncher.toast(this, R.string.recorder_failed)
            } else {
                recordAfterPermission = true
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        homeFragment?.renderWidgets()
    }

    /** Plays the last saved memo. Failures surface honestly (no silent no-ops). */
    fun playLastMemo() {
        val path = prefs.lastRecordingPath
        val file = path?.let { java.io.File(it) }
        if (file == null || !file.exists() || file.length() <= 0L) {
            AppLauncher.toast(this, R.string.recorder_no_recording)
            return
        }
        stopMemoPlayback()
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } else {
                @Suppress("DEPRECATION")
                player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
            }
            player.setOnCompletionListener { finished ->
                try { finished.release() } catch (_: Exception) {}
                if (memoPlayer === finished) memoPlayer = null
            }
            player.prepare()
            player.start()
            memoPlayer = player
        } catch (_: Exception) {
            try { player.release() } catch (_: Exception) {}
            memoPlayer = null
            AppLauncher.toast(this, R.string.recorder_failed)
        }
    }

    /** Stops and releases the memo player; safe to call when nothing is playing. */
    fun stopMemoPlayback() {
        val player = memoPlayer
        memoPlayer = null
        if (player != null) {
            try { if (player.isPlaying) player.stop() } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
        }
    }

    fun hasLastMemo(): Boolean {
        val path = prefs.lastRecordingPath ?: return false
        val file = java.io.File(path)
        return file.exists() && file.length() > 0L
    }

    fun showFolderSheet(pageIndex: Int, folder: WorkspaceFolder) {
        dismissPopup()
        openDialog?.dismiss()
        val allAppsMap = viewModel.allApps.value.orEmpty().associateBy { it.key }
        val dialogView = layoutInflater.inflate(R.layout.dialog_folder, null)
        var folderDialog: Dialog? = null
        val dialog = Sheets.create(
            this,
            dialogView,
            maxHeightFraction = 0.82f,
            onTouch = { event ->
                if (!dragController.isDragging) {
                    false
                } else {
                    // The folder lives in its own window, so continue feeding the drag
                    // controller until the finger is released over the workspace.
                    dragController.onTouchEvent(event)
                    if (event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL
                    ) {
                        folderDialog?.dismiss()
                    }
                    true
                }
            }
        )
        folderDialog = dialog

        val folderTitle = dialogView.findViewById<TextView>(R.id.folderTitle)
        val folderDot = dialogView.findViewById<View>(R.id.folderDot)
        val folderClose = dialogView.findViewById<View>(R.id.folderClose)
        val folderRename = dialogView.findViewById<View>(R.id.folderRename)
        val folderRecycler = dialogView.findViewById<RecyclerView>(R.id.folderRecycler)
        val folderAddApp = dialogView.findViewById<View>(R.id.folderAddApp)
        val folderDissolve = dialogView.findViewById<View>(R.id.folderDissolve)

        folderDot.background.mutate().setTint(prefs.accentColor())
        folderTitle.text = folder.name
        folderClose.setOnClickListener { dialog.dismiss() }
        folderRename.setOnClickListener {
            Sheets.showRename(this, folder.name) { newName ->
                val sanitized = newName.ifBlank { folder.name }
                viewModel.renameFolder(pageIndex, folder.id, sanitized)
                AppLauncher.toast(this, R.string.folder_renamed)
            }
        }

        val folderAdapter = HomeIconAdapter(
            onAppClick = { app, v -> dialog.dismiss(); launchApp(app, v) },
            onAppLongClick = { app, v ->
                // Shown on top of the folder while the finger is still down, so moving on
                // drags the app out of the folder (stock behaviour).
                showFolderAppPopup(pageIndex, folder, app, v, onBeforeAction = { dialog.dismiss() })
            },
            onAppDragStart = { app, v, rawX, rawY ->
                // Keep the dialog window alive but out of the way: it owns the current
                // gesture stream, and its touch interceptor forwards MOVEs to the drag.
                dialog.window?.setDimAmount(0f)
                dialogView.animate()
                    .translationY(dialogView.height.toFloat())
                    .alpha(0f)
                    .setDuration(140)
                    .start()
                startDrag(
                    DragController.DragItem.App(app, DragController.DragSource.FOLDER, pageIndex),
                    v,
                    rawX,
                    rawY
                )
            }
        )
        val columns = prefs.gridColumns
        folderAdapter.iconSizePx = resources.getDimensionPixelSize(
            if (columns >= 5) R.dimen.icon_size_compact else R.dimen.icon_size
        )
        folderAdapter.showLabels = true
        folderRecycler.layoutManager = GridLayoutManager(this, columns)
        folderRecycler.adapter = folderAdapter
        folderAdapter.setItems(folder.appKeys.mapNotNull { allAppsMap[it] })
        dialog.setOnDismissListener { if (openDialog === dialog) openDialog = null }

        folderAddApp.setOnClickListener {
            dialog.dismiss()
            showAddAppToFolderPicker(pageIndex, folder)
        }

        folderDissolve.setOnClickListener {
            dialog.dismiss()
            viewModel.dissolveFolder(pageIndex, folder.id)
            AppLauncher.toast(this, R.string.folder_ungroup)
        }

        openDialog = dialog
        dialog.show()
    }

    private fun showAddAppToFolderPicker(pageIndex: Int, folder: WorkspaceFolder) {
        val current = viewModel.folderById(pageIndex, folder.id) ?: folder
        val available = viewModel.allApps.value.orEmpty().filterNot { it.key in current.appKeys }
        if (available.isEmpty()) {
            AppLauncher.toast(this, R.string.folder_no_apps)
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_list, null)
        val dialog = Sheets.create(this, dialogView, maxHeightFraction = 0.75f)
        dialogView.findViewById<DotMatrixView>(R.id.listTitle).text = getString(R.string.folder_add_apps)
        dialogView.findViewById<View>(R.id.listClose).setOnClickListener { dialog.dismiss() }
        val recycler = dialogView.findViewById<RecyclerView>(R.id.listRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = available.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = layoutInflater.inflate(R.layout.item_app_row, parent, false)
                return object : RecyclerView.ViewHolder(row) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val app = available[position]
                holder.itemView.findViewById<TextView>(R.id.appLabel).text = app.label
                holder.itemView.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.icon)
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    viewModel.addAppToFolder(pageIndex, folder.id, app.key)
                }
            }
        }
        openDialog = dialog
        dialog.show()
    }

    /** Long-press menu on a folder tile: open, add apps, rename or dissolve. */
    fun showFolderPopup(pageIndex: Int, folder: WorkspaceFolder, anchor: View) {
        dismissPopup()
        val actions = mutableListOf(
            AppPopup.Action(R.drawable.ic_folder, getString(R.string.folder_open)) {
                showFolderSheet(pageIndex, folder)
            },
            AppPopup.Action(
                if (viewModel.isFolderEnlarged(pageIndex, folder.id)) R.drawable.ic_minimize else R.drawable.ic_maximize,
                getString(if (viewModel.isFolderEnlarged(pageIndex, folder.id)) R.string.action_shrink else R.string.action_enlarge)
            ) {
                viewModel.toggleEnlargeFolder(pageIndex, folder.id)
            },
            AppPopup.Action(R.drawable.ic_add, getString(R.string.folder_add_apps)) {
                showAddAppToFolderPicker(pageIndex, folder)
            },
            AppPopup.Action(R.drawable.ic_info, getString(R.string.folder_rename_title)) {
                Sheets.showRename(this, folder.name) { newName ->
                    val sanitized = newName.ifBlank { folder.name }
                    viewModel.renameFolder(pageIndex, folder.id, sanitized)
                    AppLauncher.toast(this, R.string.folder_renamed)
                }
            }
        )
        actions += AppPopup.Action(
            R.drawable.ic_delete,
            getString(R.string.folder_ungroup),
            destructive = true
        ) {
            viewModel.dissolveFolder(pageIndex, folder.id)
            AppLauncher.toast(this, R.string.folder_ungroup)
        }
        popup.show(
            anchor = anchor,
            title = folder.name,
            subtitle = "${folder.appKeys.size} APPS",
            icon = null,
            shortcuts = emptyList(),
            actions = actions
        )
    }

    /** Long-press menu for an app inside a folder; moving it out never leaves a duplicate. */
    fun showFolderAppPopup(
        pageIndex: Int,
        folder: WorkspaceFolder,
        app: AppInfo,
        anchor: View,
        anchorBounds: Rect? = null,
        onBeforeAction: () -> Unit = {}
    ) {
        dismissPopup()
        val actions = mutableListOf(
            AppPopup.Action(R.drawable.ic_arrow_up_right, getString(R.string.open)) {
                launchApp(app, anchor)
            },
            AppPopup.Action(R.drawable.ic_unpin, getString(R.string.action_remove_folder_app)) {
                viewModel.removeAppFromFolder(pageIndex, folder.id, app.key)
                AppLauncher.toast(this, R.string.folder_app_removed)
            },
            AppPopup.Action(R.drawable.ic_maximize, getString(R.string.action_enlarge)) {
                viewModel.toggleEnlargeApp(pageIndex, app.key)
            }
        )
        if (viewModel.isInDock(app)) {
            actions += AppPopup.Action(R.drawable.ic_undock, getString(R.string.remove_from_dock)) {
                viewModel.removeFromDock(app)
            }
        } else {
            actions += AppPopup.Action(R.drawable.ic_dock, getString(R.string.add_to_dock)) {
                if (!viewModel.addToDock(app)) AppLauncher.toast(this, R.string.dock_full)
            }
        }
        actions += AppPopup.Action(R.drawable.ic_info, getString(R.string.app_info)) {
            AppLauncher.openAppInfo(this, app, anchor)
        }
        if (!app.isSystemApp) {
            actions += AppPopup.Action(R.drawable.ic_delete, getString(R.string.uninstall), destructive = true) {
                AppLauncher.uninstall(this, app)
            }
        }
        popup.show(
            anchor = anchor,
            title = app.label,
            subtitle = app.packageName,
            icon = app.icon,
            shortcuts = AppLauncher.shortcuts(this, app),
            actions = actions.map { a -> a.copy(onClick = { onBeforeAction(); a.onClick() }) },
            onShortcut = { s, v -> onBeforeAction(); AppLauncher.startShortcut(this, s, v) },
            anchorBounds = anchorBounds
        )
    }

    private fun showAddToFolderDialog(pageIndex: Int, app: AppInfo) {
        val page = viewModel.workspacePages.value?.getOrNull(pageIndex) ?: return
        if (page.folders.isEmpty()) {
            viewModel.createFolder(pageIndex, "FOLDER", app.key)
            AppLauncher.toast(this, R.string.action_new_folder)
            return
        }
        val options = listOf(getString(R.string.action_new_folder)) + page.folders.map { it.name }
        val dialogView = layoutInflater.inflate(R.layout.dialog_list, null)
        val dialog = Sheets.create(this, dialogView, maxHeightFraction = 0.6f)
        dialogView.findViewById<DotMatrixView>(R.id.listTitle).text = getString(R.string.action_add_to_folder)
        dialogView.findViewById<View>(R.id.listClose).setOnClickListener { dialog.dismiss() }
        val recycler = dialogView.findViewById<RecyclerView>(R.id.listRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = options.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = layoutInflater.inflate(R.layout.item_app_row, parent, false)
                return object : RecyclerView.ViewHolder(row) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val label = options[position]
                holder.itemView.findViewById<TextView>(R.id.appLabel).text = label
                val iconView = holder.itemView.findViewById<ImageView>(R.id.appIcon)
                if (position == 0) {
                    iconView.setImageResource(R.drawable.ic_add)
                } else {
                    iconView.setImageResource(R.drawable.ic_folder)
                }
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    if (position == 0) {
                        viewModel.createFolder(pageIndex, "FOLDER", app.key)
                    } else {
                        val folder = page.folders[position - 1]
                        viewModel.addAppToFolder(pageIndex, folder.id, app.key)
                    }
                }
            }
        }
        openDialog = dialog
        dialog.show()
    }

    fun showCustomizeSheet() {
        dismissPopup()
        openDialog?.dismiss()
        val pagesCount = viewModel.workspacePages.value?.size ?: 1
        openDialog = Sheets.showCustomize(
            this,
            onWidgets = { showElementAndWidgetPicker() },
            onAddPage = {
                val newIdx = viewModel.addPage()
                homeFragment?.scrollToPage(newIdx)
                AppLauncher.toast(this, R.string.page_added)
            },
            onRemovePage = {
                val current = homeFragment?.currentPageIndex ?: 0
                if (viewModel.removePage(current)) {
                    val target = (current - 1).coerceAtLeast(0)
                    homeFragment?.scrollToPage(target)
                    AppLauncher.toast(this, R.string.page_removed)
                }
            },
            canRemovePage = pagesCount > 1,
            onWallpaper = {
                showWallpaperStyleSheet()
            },
            onSettings = { openSettings() }
        )
    }

    fun showWallpaperStyleSheet() {
        openDialog?.dismiss()
        openDialog = Sheets.showWallpaperStyle(
            this,
            prefs,
            onWallpaperPicker = {
                if (prefs.background != LauncherPrefs.Background.WALLPAPER) {
                    prefs.background = LauncherPrefs.Background.WALLPAPER
                    appliedSettingsVersion = prefs.settingsVersion
                    applyAllPrefs()
                }
                AppLauncher.openWallpaperPicker(this)
            },
            onChanged = {
                appliedSettingsVersion = prefs.settingsVersion
                applyThemeMode()
                applyAllPrefs()
            }
        )
    }

    fun showEditNoteDialog() {
        openDialog?.dismiss()
        openDialog = Sheets.showEditNote(this, prefs.quickNote) { newNote ->
            prefs.quickNote = newNote
            homeFragment?.renderWidgets()
        }
    }

    // ---- Elements & Widgets -----------------------------------------------------------------

    fun showCountdownEditor() {
        openDialog?.dismiss()
        openDialog = Sheets.showCountdown(this, prefs) {
            homeFragment?.renderWidgets()
        }
    }

    /** Manual weather city; an empty value returns to automatic network location. */
    fun showWeatherCityDialog() {
        openDialog?.dismiss()
        openDialog = Sheets.showTextInput(
            this,
            getString(R.string.weather_city_title),
            getString(R.string.weather_city_hint),
            prefs.weatherCity,
            maxLength = 64
        ) { city ->
            WeatherRepository.setCity(this, prefs, city) { }
            AppLauncher.toast(this, R.string.weather_city_saved)
            homeFragment?.renderWidgets()
        }
    }

    fun showStepGoalDialog() {
        openDialog?.dismiss()
        openDialog = Sheets.showTextInput(
            this,
            getString(R.string.setting_step_goal),
            getString(R.string.step_goal_hint),
            prefs.stepGoal.toString(),
            maxLength = 6,
            numeric = true
        ) { raw ->
            val value = raw.replace(",", "").trim().toIntOrNull()
            if (value == null || value !in 1000..100000) {
                AppLauncher.toast(this, R.string.step_goal_invalid)
                return@showTextInput
            }
            prefs.stepGoal = value
            StepRepository.refresh()
            AppLauncher.toast(this, R.string.step_goal_saved)
            homeFragment?.renderWidgets()
        }
    }

    private fun showElementAndWidgetPicker() {
        openDialog?.dismiss()
        val currentPage = homeFragment?.currentPageIndex ?: 0
        openDialog = WidgetPickerDialog.show(
            this,
            lifecycleScope,
            onPickElement = { element ->
                viewModel.addElementToPage(currentPage, element.id)
                homeFragment?.renderWidgets()
            },
            onPickWidget = { info ->
                requestAddWidget(info)
            }
        )
    }

    private fun requestAddWidget(info: AppWidgetProviderInfo) {
        val id = try { widgetHost.allocateAppWidgetId() } catch (e: Exception) { -1 }
        if (id == -1) {
            AppLauncher.toast(this, R.string.widget_failed)
            return
        }
        pendingWidgetId = id
        val bound = try {
            appWidgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, Bundle())
        } catch (e: Exception) {
            false
        }
        if (bound) {
            configureOrAddWidget(id, info)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            }
            try {
                bindWidgetLauncher.launch(intent)
            } catch (e: Exception) {
                discardPendingWidget()
                AppLauncher.toast(this, R.string.widget_failed)
            }
        }
    }

    private fun configureOrAddWidget(id: Int, info: AppWidgetProviderInfo) {
        val optional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info.configure != null && !optional) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE_WIDGET, null)
                return
            } catch (e: Exception) {
                // If configure activity fails to start, fallback to adding widget directly
                addWidget(id)
                return
            }
        }
        addWidget(id)
    }

    @Deprecated("Widget configuration still reports back through onActivityResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONFIGURE_WIDGET) {
            val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
            if (resultCode == Activity.RESULT_OK && id != -1) addWidget(id) else discardPendingWidget()
        } else if (requestCode == REQUEST_RECONFIGURE_WIDGET) {
            // The provider owns the new options; re-bind the host view so it picks them up.
            homeFragment?.renderWidgets()
        }
    }

    private fun addWidget(id: Int) {
        pendingWidgetId = -1
        val currentPage = homeFragment?.currentPageIndex ?: 0
        val info = try { appWidgetManager.getAppWidgetInfo(id) } catch (e: Exception) { null }
        val (sx, sy) = widgetSpans(info)
        viewModel.addWidgetToPage(currentPage, id, sx, sy)
        homeFragment?.renderWidgets()
    }

    /** Grid cell size in px for the current column count (matches CellLayout's maths). */
    private fun cellSizePx(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val cols = prefs.gridColumns.coerceAtLeast(1)
        val margin = resources.getDimensionPixelSize(R.dimen.page_margin)
        val gap = (6 * dm.density).toInt()
        val w = ((dm.widthPixels - 2 * margin - (cols - 1) * gap) / cols).coerceAtLeast(1)
        val h = (w * 1.18f).toInt().coerceAtLeast((72 * dm.density).toInt())
        return w to h
    }

    /** Launcher3-style span calculation from the provider's declared size. */
    private fun widgetSpans(info: AppWidgetProviderInfo?): Pair<Int, Int> {
        val cols = prefs.gridColumns.coerceAtLeast(1)
        if (info == null) return cols to 2
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceIn(1, cols) to info.targetCellHeight.coerceIn(1, LauncherViewModel.MAX_WIDGET_SPAN_Y)
        }
        val (cw, ch) = cellSizePx()
        val gap = (6 * resources.displayMetrics.density).toInt()
        val sx = ((info.minWidth + gap + cw - 1) / (cw + gap)).coerceIn(1, cols)
        val sy = ((info.minHeight + gap + ch - 1) / (ch + gap)).coerceIn(1, LauncherViewModel.MAX_WIDGET_SPAN_Y)
        return sx to sy
    }

    private fun minWidgetSpans(info: AppWidgetProviderInfo?): Pair<Int, Int> {
        if (info == null) return 1 to 1
        val (cw, ch) = cellSizePx()
        val gap = (6 * resources.displayMetrics.density).toInt()
        val mw = if (info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
        val mh = if (info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        return ((mw + gap + cw - 1) / (cw + gap)).coerceAtLeast(1) to ((mh + gap + ch - 1) / (ch + gap)).coerceAtLeast(1)
    }

    private fun discardPendingWidget() {
        if (pendingWidgetId != -1) {
            try { widgetHost.deleteAppWidgetId(pendingWidgetId) } catch (_: Exception) {}
        }
        pendingWidgetId = -1
    }

    fun createWidgetView(id: Int): AppWidgetHostView? {
        val info = try { appWidgetManager.getAppWidgetInfo(id) } catch (e: Exception) { null } ?: return null
        return try {
            widgetHost.getOrCreateView(this, id, info)
        } catch (e: Exception) {
            null
        }
    }

    fun showWidgetPopup(id: Int, pageIndex: Int, anchor: View) {
        val info = try { appWidgetManager.getAppWidgetInfo(id) } catch (e: Exception) { null }
        val label = info?.let { try { it.loadLabel(packageManager) } catch (e: Exception) { null } } ?: getString(R.string.widgets)
        val actions = mutableListOf<AppPopup.Action>()

        if (info?.configure != null) {
            actions += AppPopup.Action(R.drawable.ic_tune, getString(R.string.widget_configure)) {
                configureWidget(id)
            }
        }

        // Resize in grid cells, respecting the provider's resize mode and minimum size.
        val item = viewModel.workspacePages.value?.getOrNull(pageIndex)?.items
            ?.firstOrNull { it.type == WorkspaceItem.Type.WIDGET && it.widgetId == id }
        if (item != null) {
            val cols = prefs.gridColumns
            val (minX, minY) = minWidgetSpans(info)
            val mode = info?.resizeMode ?: (AppWidgetProviderInfo.RESIZE_BOTH)
            val canV = (mode and AppWidgetProviderInfo.RESIZE_VERTICAL) != 0
            val canH = (mode and AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0
            fun resize(sx: Int, sy: Int) {
                viewModel.resizeItem(pageIndex, item.id, sx, sy)
            }
            if (canV && item.spanY < LauncherViewModel.MAX_WIDGET_SPAN_Y) {
                actions += AppPopup.Action(R.drawable.ic_move_down, getString(R.string.widget_taller)) { resize(item.spanX, item.spanY + 1) }
            }
            if (canV && item.spanY - 1 >= maxOf(1, minY)) {
                actions += AppPopup.Action(R.drawable.ic_move_up, getString(R.string.widget_shorter)) { resize(item.spanX, item.spanY - 1) }
            }
            if (canH && item.spanX < cols) {
                actions += AppPopup.Action(R.drawable.ic_maximize, getString(R.string.widget_wider)) { resize(item.spanX + 1, item.spanY) }
            }
            if (canH && item.spanX - 1 >= maxOf(1, minX)) {
                actions += AppPopup.Action(R.drawable.ic_minimize, getString(R.string.widget_narrower)) { resize(item.spanX - 1, item.spanY) }
            }
        }
        actions += AppPopup.Action(R.drawable.ic_delete, getString(R.string.remove_widget), destructive = true) {
            viewModel.removeWidget(id)
            try { widgetHost.deleteAppWidgetId(id) } catch (_: Exception) {}
            homeFragment?.renderWidgets()
        }
        popup.show(
            anchor = anchor,
            title = label,
            subtitle = info?.provider?.packageName,
            icon = null,
            shortcuts = emptyList(),
            actions = actions
        )
    }

    private fun configureWidget(id: Int) {
        val info = try { appWidgetManager.getAppWidgetInfo(id) } catch (e: Exception) { null }
        if (info?.configure == null) {
            AppLauncher.toast(this, R.string.widget_no_configure)
            return
        }
        try {
            widgetHost.startAppWidgetConfigureActivityForResult(
                this,
                id,
                0,
                REQUEST_RECONFIGURE_WIDGET,
                null
            )
        } catch (e: Exception) {
            AppLauncher.toast(this, R.string.widget_no_configure)
        }
    }

    fun showElementPopup(pageIndex: Int, element: BespokeElement, anchor: View) {
        val actions = mutableListOf<AppPopup.Action>()

        // Every element exposes the action that makes sense for its live source instead of
        // only generic reorder/remove controls.
        when (element) {
            BespokeElement.WEATHER -> {
                actions += AppPopup.Action(R.drawable.ic_refresh, getString(R.string.weather_refresh)) {
                    WeatherRepository.refresh(this, prefs, force = true) { weather ->
                        AppLauncher.toast(
                            this,
                            if (weather != null) R.string.weather_updated else R.string.weather_unavailable
                        )
                    }
                }
                actions += AppPopup.Action(R.drawable.ic_globe, getString(R.string.weather_set_city)) {
                    showWeatherCityDialog()
                }
            }
            BespokeElement.PEDOMETER -> {
                if (!LauncherSystem.hasActivityRecognition(this)) {
                    actions += AppPopup.Action(R.drawable.ic_walk, getString(R.string.grant_motion_access)) {
                        requestStepPermission()
                    }
                }
                actions += AppPopup.Action(R.drawable.ic_walk, getString(R.string.setting_step_goal)) {
                    showStepGoalDialog()
                }
            }
            BespokeElement.SCREEN_TIME -> {
                if (!LauncherSystem.hasUsageAccess(this)) {
                    actions += AppPopup.Action(R.drawable.ic_time, getString(R.string.grant_usage_access)) {
                        LauncherSystem.openUsageAccessSettings(this)
                    }
                } else {
                    actions += AppPopup.Action(R.drawable.ic_time, getString(R.string.action_open_wellbeing)) {
                        if (!AppLauncher.start(
                                this,
                                Intent("android.settings.DIGITAL_WELLBEING"),
                                showError = false
                            )
                        ) {
                            LauncherSystem.openUsageAccessSettings(this)
                        }
                    }
                }
            }
            BespokeElement.MEDIA_PLAYER -> {
                if (!LauncherSystem.hasNotificationAccess(this)) {
                    actions += AppPopup.Action(R.drawable.ic_play, getString(R.string.media_grant_access)) {
                        AppLauncher.toast(this, R.string.media_permission_toast)
                        LauncherSystem.openNotificationAccessSettings(this)
                    }
                } else {
                    actions += AppPopup.Action(R.drawable.ic_refresh, getString(R.string.media_refresh)) {
                        MediaSessionRepository.refresh()
                        AppLauncher.toast(this, R.string.media_refreshed)
                    }
                    val packageName = MediaSessionRepository.state.packageName
                    if (packageName.isNotBlank()) {
                        actions += AppPopup.Action(R.drawable.ic_play, getString(R.string.media_open_player)) {
                            AppLauncher.openMediaApp(this, packageName)
                        }
                    }
                }
            }
            BespokeElement.HABIT_TRACKER -> {
                actions += AppPopup.Action(R.drawable.ic_refresh, getString(R.string.habit_reset)) {
                    prefs.habitCount = 0
                    homeFragment?.renderWidgets()
                }
            }
            BespokeElement.COUNTDOWN -> {
                actions += AppPopup.Action(R.drawable.ic_calendar_event, getString(R.string.countdown_set_date)) {
                    showCountdownEditor()
                }
                if (prefs.countdownEpochDay > 0L) {
                    actions += AppPopup.Action(R.drawable.ic_delete, getString(R.string.countdown_clear), destructive = true) {
                        prefs.countdownEpochDay = 0L
                        prefs.countdownTitle = ""
                        AppLauncher.toast(this, R.string.countdown_cleared)
                        homeFragment?.renderWidgets()
                    }
                }
            }
            BespokeElement.SOUND_RECORDER -> {
                actions += AppPopup.Action(
                    R.drawable.ic_mic,
                    getString(
                        if (voiceRecorder.isRecording) R.string.action_stop_recording
                        else R.string.action_record_memo
                    )
                ) {
                    toggleVoiceMemo()
                }
                if (hasLastMemo()) {
                    actions += AppPopup.Action(R.drawable.ic_play, getString(R.string.recorder_play_last)) {
                        playLastMemo()
                    }
                }
                if (memoPlayer?.isPlaying == true) {
                    actions += AppPopup.Action(R.drawable.ic_close, getString(R.string.recorder_stop_playback)) {
                        stopMemoPlayback()
                    }
                }
            }
            BespokeElement.QUICK_NOTE -> {
                actions += AppPopup.Action(R.drawable.ic_pin, getString(R.string.action_edit_note)) {
                    showEditNoteDialog()
                }
            }
            BespokeElement.CLOCK, BespokeElement.WORLD_CLOCK, BespokeElement.ANALOG_CLOCK -> {
                actions += AppPopup.Action(R.drawable.ic_alarm, getString(R.string.action_open_alarms)) {
                    AppLauncher.openAlarms(this)
                }
            }
            BespokeElement.BATTERY_CALENDAR -> {
                actions += AppPopup.Action(R.drawable.ic_bolt, getString(R.string.action_battery_settings)) {
                    AppLauncher.openBatterySettings(this)
                }
                actions += AppPopup.Action(R.drawable.ic_calendar_event, getString(R.string.action_open_calendar)) {
                    AppLauncher.openCalendar(this)
                }
            }
            BespokeElement.BATTERY_DOTS -> {
                actions += AppPopup.Action(R.drawable.ic_bolt, getString(R.string.action_battery_settings)) {
                    AppLauncher.openBatterySettings(this)
                }
            }
            BespokeElement.STORAGE -> {
                actions += AppPopup.Action(R.drawable.ic_storage, getString(R.string.action_storage_settings)) {
                    AppLauncher.start(this, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), showError = false)
                }
            }
            BespokeElement.SYSTEM_MONITOR -> {
                actions += AppPopup.Action(R.drawable.ic_memory, getString(R.string.action_device_info)) {
                    AppLauncher.start(this, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), showError = false)
                }
            }
            BespokeElement.QUICK_TOGGLES -> {
                actions += AppPopup.Action(R.drawable.ic_tune, getString(R.string.action_open_quick_settings)) {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    } else {
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                    }
                    AppLauncher.start(this, intent)
                }
            }
            BespokeElement.COMPASS, BespokeElement.STOPWATCH -> Unit
        }

        actions += AppPopup.Action(R.drawable.ic_delete, getString(R.string.remove_element), destructive = true) {
            viewModel.removeElementFromPage(pageIndex, element.id)
            homeFragment?.renderWidgets()
        }

        popup.show(
            anchor = anchor,
            title = getString(element.titleRes),
            subtitle = getString(element.subRes),
            icon = null,
            shortcuts = emptyList(),
            actions = actions
        )
    }

    companion object {
        private const val REQUEST_CONFIGURE_WIDGET = 2001
        private const val REQUEST_RECONFIGURE_WIDGET = 2002
        private const val KEY_PENDING_WIDGET = "pending_widget"
        private const val DEFAULT_WIDGET_HEIGHT_DP = 120
        private const val WIDGET_HEIGHT_STEP_DP = 40
    }
}
