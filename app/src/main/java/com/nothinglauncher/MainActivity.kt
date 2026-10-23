package com.nothinglauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.nothinglauncher.databinding.ActivityMainBinding
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

    private var appliedSettingsVersion = -1

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
        val id = pendingWidgetId
        val info = if (id != -1) appWidgetManager.getAppWidgetInfo(id) else null
        if (result.resultCode == Activity.RESULT_OK && info != null) {
            configureOrAddWidget(id, info)
        } else {
            discardPendingWidget()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        widgetHost = WidgetHostManager(this)
        appWidgetManager = AppWidgetManager.getInstance(this)
        popup = AppPopup(this)
        pendingWidgetId = savedInstanceState?.getInt(KEY_PENDING_WIDGET, -1) ?: -1

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
    }

    override fun onStop() {
        super.onStop()
        try {
            widgetHost.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (!isChangingConfigurations) {
            dismissPopup()
            if (drawerProgress > 0f) setDrawerProgressImmediately(0f)
        }
    }

    override fun onDestroy() {
        openDialog?.dismiss()
        openDialog = null
        popup.dismiss()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            dismissPopup()
            openDialog?.dismiss()
            if (drawerProgress > 0f) closeDrawer() else homeFragment?.scrollToTop()
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    popup.isShowing -> dismissPopup()
                    drawerProgress > 0f -> closeDrawer()
                    else -> homeFragment?.scrollToTop()
                }
            }
        })
    }

    // ---- Settings ------------------------------------------------------------------------

    private fun applyAllPrefs() {
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
                window.setBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))
                binding.root.background = ColorDrawable(0xFF000000.toInt())
            }
            LauncherPrefs.Background.DOTS -> {
                window.setFormat(PixelFormat.OPAQUE)
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))
                binding.root.background = DotGridDrawable(density)
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
            LauncherPrefs.SwipeDown.NOTIFICATIONS -> AppLauncher.expandNotifications(this)
            LauncherPrefs.SwipeDown.SEARCH -> openDrawer(focusSearch = true)
        }
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
                if (prefs.background != LauncherPrefs.Background.WALLPAPER) {
                    prefs.background = LauncherPrefs.Background.WALLPAPER
                    appliedSettingsVersion = prefs.settingsVersion
                    applyAllPrefs()
                }
                AppLauncher.openWallpaperPicker(this)
            },
            onSettings = { openSettings() }
        )
    }

    // ---- Elements & Widgets -----------------------------------------------------------------

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
            appWidgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
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
                discardPendingWidget()
                AppLauncher.toast(this, R.string.widget_failed)
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
        }
    }

    private fun addWidget(id: Int) {
        pendingWidgetId = -1
        val currentPage = homeFragment?.currentPageIndex ?: 0
        viewModel.addWidgetToPage(currentPage, id)
        homeFragment?.renderWidgets()
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
            widgetHost.createView(this, id, info)
        } catch (e: Exception) {
            null
        }
    }

    fun showWidgetPopup(id: Int, anchor: View) {
        val info = try { appWidgetManager.getAppWidgetInfo(id) } catch (e: Exception) { null }
        val label = info?.let { try { it.loadLabel(packageManager) } catch (e: Exception) { null } } ?: getString(R.string.widgets)
        popup.show(
            anchor = anchor,
            title = label,
            subtitle = info?.provider?.packageName,
            icon = null,
            shortcuts = emptyList(),
            actions = listOf(
                AppPopup.Action(R.drawable.ic_delete, getString(R.string.remove_widget), destructive = true) {
                    viewModel.removeWidget(id)
                    try { widgetHost.deleteAppWidgetId(id) } catch (_: Exception) {}
                    homeFragment?.renderWidgets()
                }
            )
        )
    }

    fun showElementPopup(pageIndex: Int, element: BespokeElement, anchor: View) {
        popup.show(
            anchor = anchor,
            title = getString(element.titleRes),
            subtitle = getString(element.subRes),
            icon = null,
            shortcuts = emptyList(),
            actions = listOf(
                AppPopup.Action(R.drawable.ic_delete, getString(R.string.remove_element), destructive = true) {
                    viewModel.removeElementFromPage(pageIndex, element.id)
                    homeFragment?.renderWidgets()
                }
            )
        )
    }

    companion object {
        private const val REQUEST_CONFIGURE_WIDGET = 2001
        private const val KEY_PENDING_WIDGET = "pending_widget"
    }
}
