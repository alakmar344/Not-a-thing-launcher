package com.nothinglauncher.home

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings
import com.android.launcher3.LauncherState
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.popup.SystemShortcut
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.util.IntSet
import com.android.launcher3.util.RunnableList
import com.android.launcher3.util.TouchController
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.launcher3.widget.PendingAddWidgetInfo
import com.android.launcher3.widget.custom.CustomWidgetManager
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.DeskHudActivity
import com.nothinglauncher.DotGridDrawable
import com.nothinglauncher.DotMatrixClockView
import com.nothinglauncher.Intents
import com.nothinglauncher.MediaSessionRepository
import com.nothinglauncher.NothingLockAccessibilityService
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Sheets
import com.nothinglauncher.Tactile
import com.nothinglauncher.WeatherRepository
import com.nothinglauncher.drawer.DrawerHeader
import com.nothinglauncher.drawer.HiddenVaultSheet
import com.nothinglauncher.drawer.NothingAllAppsContainerView
import com.nothinglauncher.icons.NothingIconLogic
import com.nothinglauncher.perf.VisualTier
import com.nothinglauncher.space.EssentialSpaceOverlay
import com.nothinglauncher.widgets.BatteryCalendarWidget
import com.nothinglauncher.widgets.ClockWidget
import com.nothinglauncher.widgets.DeskBriefingWidget
import com.nothinglauncher.widgets.WeatherWidget
import java.util.stream.Stream
import kotlin.math.abs

/**
 * Stock Launcher3 with the Not a Thing v13 surface: interactive dot-grid parallax & ripples,
 * hardware accent palettes, in-process -1 Essential Space overlay, configurable home gestures,
 * long-press Vault/Rename shortcuts, and the v10/v13 customize sheet.
 */
class NothingLauncher : Launcher() {

    private companion object {
        /** Away from home at least this long -> the clock plays its power-on sweep. */
        const val POWER_ON_AFTER_MS = 5 * 60_000L
        const val REQ_RECORD_AUDIO = 1305
    }

    private val prefs by lazy { NothingPrefs.get(this) }
    private var dotGrid: DotGridDrawable? = null
    private var essentialOverlay: EssentialSpaceOverlay? = null
    private var appliedAccent: AccentTheme.Palette? = null
    private var appliedNightDim: Boolean? = null
    private var appliedFocus: Boolean? = null
    private var appliedDrawerMode: NothingPrefs.DrawerLayoutMode? = null
    private var drawerProgress = 0f

    override fun getDefaultOverlay(): LauncherOverlayManager {
        return EssentialSpaceOverlay(
            this,
            onRequestRecordPermission = {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            },
            onFocusModeToggled = {
                applyBackdrop()
                applyCaffeineFlag()
                refreshHiddenApps()
            }
        ).also { essentialOverlay = it }
    }

    fun openEssentialSpace() {
        essentialOverlay?.openOverlay()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_SLASH || keyCode == KeyEvent.KEYCODE_SEARCH) &&
            isInState(LauncherState.NORMAL) &&
            AbstractFloatingView.getTopOpenView(this) == null &&
            essentialOverlay?.isShown != true
        ) {
            executeGestureAction(NothingPrefs.GestureAction.SEARCH)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStateBack() {
        val overlay = essentialOverlay
        if (overlay != null && overlay.isShown) {
            overlay.hideOverlay(260)
            return
        }
        super.onStateBack()
    }

    /**
     * Launcher3 picks a theme from the wallpaper colours. Unless the user shows the wallpaper,
     * the backdrop is our own, so pick the variant whose text reads on it instead.
     */
    override fun setTheme(resid: Int) {
        super.setTheme(mapTheme(resid))
    }

    private fun mapTheme(resid: Int): Int {
        if (NothingPrefs.get(this).background == NothingPrefs.Background.WALLPAPER) return resid
        return if (NothingIconLogic.isNight(this) == 1) R.style.NtTheme_Backdrop_Dark
        else R.style.NtTheme_Backdrop_Light
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBackdrop()
        applyCaffeineFlag()
        wireWorkspaceInteractions()
        // Icons are rendered for light or dark mode; re-render them after a switch.
        val night = NothingIconLogic.isNight(this)
        if (prefs.iconsRenderedNight != night) {
            val firstRender = prefs.iconsRenderedNight == -1
            prefs.iconsRenderedNight = night
            if (!firstRender) NothingIconLogic.refreshAll(this)
        }
    }

    private var appliedBackground: NothingPrefs.Background? = null
    private var appliedTier: VisualTier.Tier? = null

    override fun onResume() {
        super.onResume()
        // Re-create when backdrop/accent/tier changes so all window paints match cleanly.
        val themeChanged = (appliedBackground != null &&
            (appliedBackground != prefs.background || appliedTier != VisualTier.current(this))) ||
            (appliedAccent != null && appliedAccent != prefs.accentPalette) ||
            (appliedNightDim != null && appliedNightDim != prefs.nightDimDots) ||
            (appliedFocus != null && appliedFocus != prefs.focusMode)
        if (themeChanged) {
            recreate()
            return
        }
        applyCaffeineFlag()
        refreshSearchPillVisibility()
        applyHomeLabelsVisibility()
        if (appliedDrawerMode != null && appliedDrawerMode != prefs.drawerLayoutMode) {
            (appsView as? NothingAllAppsContainerView)?.refreshDrawerLayoutMode()
        }
        appliedDrawerMode = prefs.drawerLayoutMode
        refreshHiddenApps()
        refreshDrawerHeader()
        // Workspace views stay attached while home is in the background, so live elements are
        // refreshed here: media after notification access was granted, weather when stale.
        // Weather only if an element is on a home screen: otherwise this sent the user's IP to
        // a geolocation service every half hour of home use without anything showing it.
        MediaSessionRepository.refresh()
        if (WeatherRepository.hasListeners()) WeatherRepository.refresh(this, prefs) { }
        // Coming back to home after a while (or on first start) lights the LED clock up with a
        // short scan. Not on every app switch: that would get old fast. Posted so the
        // workspace has been laid out and the clock is actually on screen.
        val now = SystemClock.elapsedRealtime()
        if (pausedAt == 0L || now - pausedAt >= POWER_ON_AFTER_MS) {
            if (VisualTier.motionAllowed(this)) workspace?.post { DotMatrixClockView.playPowerOn() }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                essentialOverlay?.onActivityResumed()
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                android.widget.Toast.makeText(this, R.string.nt_mic_permission_denied, android.widget.Toast.LENGTH_SHORT).show()
                val appDetails = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)
                )
                Intents.start(this, appDetails)
            } else {
                android.widget.Toast.makeText(this, R.string.nt_mic_permission_denied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var pausedAt = 0L

    override fun onPause() {
        super.onPause()
        pausedAt = SystemClock.elapsedRealtime()
    }

    /**
     * When home goes to the background, close the widget picker: its previews are the
     * largest bitmaps Launcher3 holds for us and are rebuilt in a moment when reopened. Lite
     * devices do it as soon as home is hidden, the rest only once the system asks for memory
     * back (BACKGROUND, or Android 13's RUNNING_LOW signals).
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val lite = VisualTier.current(this) == VisualTier.Tier.LITE
        val release = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            (lite && level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        if (release && !isStarted) {
            AbstractFloatingView.closeOpenViews(this, false, AbstractFloatingView.TYPE_WIDGETS_FULL_SHEET)
        }
    }

    /**
     * A config change this Activity doesn't list in android:configChanges (e.g. system
     * dark/light mode switching, display size/font scale) destroys and recreates it even while
     * the Customize sheet is open. Dismiss any open sheet so its window doesn't outlive the
     * Activity token it was shown on ("WindowLeaked").
     */
    override fun onDestroy() {
        CustomizeSheet.dismissActive()
        super.onDestroy()
    }

    /**
     * Wallpaper colours only matter when the wallpaper is visible; otherwise a new wallpaper
     * would needlessly rebuild home (closing the drawer and folders).
     */
    override fun onColorHintsChanged(colorHints: Int) {
        if (prefs.background == NothingPrefs.Background.WALLPAPER) super.onColorHintsChanged(colorHints)
    }

    private fun applyBackdrop() {
        appliedBackground = prefs.background
        appliedTier = VisualTier.current(this)
        appliedAccent = prefs.accentPalette
        appliedNightDim = prefs.nightDimDots
        appliedFocus = prefs.focusMode
        val bg = ContextCompat.getColor(this, R.color.nothing_bg)
        when (prefs.background) {
            NothingPrefs.Background.DOTS -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                // Lite: flat grid. Balanced: + vignette. Rich: + the top "light pool" & ripples.
                dotGrid = DotGridDrawable.forTier(this)
                window.setBackgroundDrawable(dotGrid)
            }
            NothingPrefs.Background.BLACK -> {
                dotGrid = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setBackgroundDrawable(ColorDrawable(bg))
            }
            NothingPrefs.Background.WALLPAPER -> {
                dotGrid = null
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setBackgroundDrawable(ColorDrawable(0))
            }
        }
    }

    private fun applyCaffeineFlag() {
        if (prefs.caffeineEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun wireWorkspaceInteractions() {
        workspace?.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            updateBackdropParallax(scrollX)
        }
        stateManager.addStateListener(object : StateManager.StateListener<LauncherState> {
            override fun onStateTransitionStart(toState: LauncherState) {
                drawerProgress = if (toState == LauncherState.ALL_APPS) 1f else 0f
                updateBackdropParallax(workspace?.scrollX ?: 0)
            }

            override fun onStateTransitionComplete(finalState: LauncherState) {
                drawerProgress = if (finalState == LauncherState.ALL_APPS) 1f else 0f
                updateBackdropParallax(workspace?.scrollX ?: 0)
                if (finalState == LauncherState.NORMAL) {
                    applyHomeLabelsVisibility()
                    refreshSearchPillVisibility()
                } else if (finalState == LauncherState.ALL_APPS) {
                    refreshDrawerHeader()
                    refreshHiddenApps()
                }
            }
        })
    }

    private fun updateBackdropParallax(scrollX: Int) {
        val grid = dotGrid ?: return
        val ws = workspace ?: return
        val pages = (ws.childCount - 1).coerceAtLeast(1)
        val totalW = (ws.width * pages).coerceAtLeast(1)
        val fractionX = ((scrollX.toFloat() / totalW) * 2f - 1f).coerceIn(-1f, 1f)
        grid.setParallax(fractionX, drawerProgress)
    }

    fun refreshHiddenApps() {
        (appsView as? NothingAllAppsContainerView)?.applyHiddenFilter()
    }

    private fun refreshDrawerHeader() {
        val av = appsView ?: return
        findFirstView(av, DrawerHeader::class.java)?.refresh()
    }

    private fun refreshSearchPillVisibility() {
        val hs = hotseat ?: return
        findFirstView(hs, HotseatSearchPill::class.java)?.refreshVisibility()
    }

    private fun applyHomeLabelsVisibility() {
        val visible = !prefs.hideHomeLabels
        fun applyContainer(vg: ViewGroup?) {
            if (vg == null) return
            for (i in 0 until vg.childCount) {
                when (val child = vg.getChildAt(i)) {
                    is BubbleTextView -> child.setTextVisibility(visible)
                    is FolderIcon -> child.setTextVisible(visible)
                }
            }
        }
        hotseat?.shortcutsAndWidgets?.let { applyContainer(it) }
        val ws = workspace ?: return
        for (p in 0 until ws.childCount) {
            val cell = ws.getChildAt(p) as? ViewGroup ?: continue
            for (c in 0 until cell.childCount) {
                val shortcuts = cell.getChildAt(c) as? ViewGroup ?: continue
                applyContainer(shortcuts)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findFirstView(root: View, cls: Class<T>): T? {
        if (cls.isInstance(root)) return root as T
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            val found = findFirstView(root.getChildAt(i), cls)
            if (found != null) return found
        }
        return null
    }

    override fun createTouchControllers(): Array<TouchController> {
        val base = super.createTouchControllers()
        if (base.isEmpty()) return arrayOf(HomeGestureController())
        return Array(base.size + 1) { i ->
            when {
                i == 0 -> base[0]
                i == 1 -> HomeGestureController()
                else -> base[i - 1]
            }
        }
    }

    override fun getSupportedShortcuts(): Stream<SystemShortcut.Factory<*>> {
        @Suppress("UNCHECKED_CAST")
        return Stream.concat(
            super.getSupportedShortcuts() as Stream<SystemShortcut.Factory<*>>,
            Stream.of(NothingShortcuts.HIDE_IN_VAULT, NothingShortcuts.RENAME_APP)
        )
    }

    override fun startActivitySafely(v: View?, intent: Intent?, item: ItemInfo?): RunnableList? {
        val list = super.startActivitySafely(v, intent, item)
        if (list != null) {
            val comp = intent?.component ?: item?.targetComponent
            if (comp != null && comp.packageName != packageName) {
                prefs.recordAppLaunch(comp.flattenToString())
                refreshDrawerHeader()
            }
        }
        return list
    }

    /** Long-press on empty home: the v10 "Customize" sheet instead of the stock menu. */
    override fun showDefaultOptions(x: Float, y: Float) {
        CustomizeSheet.show(this)
    }

    override fun finishBindingItems(pagesBoundFirst: IntSet?) {
        super.finishBindingItems(pagesBoundFirst)
        if (!prefs.defaultTilesPlaced && placeDefaultTiles()) prefs.defaultTilesPlaced = true
        applyHomeLabelsVisibility()
        refreshSearchPillVisibility()
        refreshHiddenApps()
    }

    private fun executeGestureAction(action: NothingPrefs.GestureAction) {
        when (action) {
            NothingPrefs.GestureAction.NONE -> Unit
            NothingPrefs.GestureAction.NOTIFICATIONS -> Intents.expandNotifications(this)
            NothingPrefs.GestureAction.SEARCH -> {
                stateManager.goToState(LauncherState.ALL_APPS, true)
                appsView?.post { appsView?.searchUiManager?.editText?.requestFocus() }
            }
            NothingPrefs.GestureAction.ESSENTIAL_SPACE -> openEssentialSpace()
            NothingPrefs.GestureAction.DESK_HUD -> DeskHudActivity.launch(this)
            NothingPrefs.GestureAction.CUSTOMIZE -> CustomizeSheet.show(this)
            NothingPrefs.GestureAction.VAULT -> HiddenVaultSheet.openWithAuth(this) { refreshHiddenApps() }
            NothingPrefs.GestureAction.LOCK_SCREEN -> {
                if (!NothingLockAccessibilityService.lockScreenNow()) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.nt_lock_permission_title)
                        .setMessage(R.string.nt_lock_permission_body)
                        .setPositiveButton(R.string.nt_status_enable) { _, _ ->
                            Intents.openAccessibilitySettings(this)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private inner class HomeGestureController : TouchController {

        private val slop = ViewConfiguration.get(this@NothingLauncher).scaledTouchSlop.toFloat()
        private var downX = 0f
        private var downY = 0f
        private var intercepted = false
        private var activeAction: NothingPrefs.GestureAction? = null

        private val gestureDetector = GestureDetector(
            this@NothingLauncher,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (isHomeReady() && isEmptyWorkspacePoint(e.rawX.toInt(), e.rawY.toInt())) {
                        dotGrid?.triggerRipple(e.rawX, e.rawY)
                    }
                    return false
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val act = prefs.doubleTapAction
                    if (act != NothingPrefs.GestureAction.NONE &&
                        isHomeReady() &&
                        isEmptyWorkspacePoint(e.rawX.toInt(), e.rawY.toInt())
                    ) {
                        Tactile.tick(dragLayer)
                        executeGestureAction(act)
                        return true
                    }
                    return false
                }
            }
        )

        private val scaleDetector = ScaleGestureDetector(
            this@NothingLauncher,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val act = prefs.pinchAction
                    if (act != NothingPrefs.GestureAction.NONE &&
                        isHomeReady() &&
                        detector.scaleFactor < 0.88f
                    ) {
                        intercepted = true
                        activeAction = act
                        return true
                    }
                    return false
                }
            }
        )

        override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!isHomeReady()) {
                intercepted = false
                activeAction = null
                return false
            }
            gestureDetector.onTouchEvent(ev)
            scaleDetector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    intercepted = false
                    activeAction = null
                }
                MotionEvent.ACTION_MOVE -> {
                    if (intercepted && activeAction != null) return true
                    val dy = ev.rawY - downY
                    val dx = ev.rawX - downX
                    val downAct = prefs.swipeDownAction
                    if (downAct != NothingPrefs.GestureAction.NONE &&
                        ev.pointerCount == 1 &&
                        dy > slop * 1.4f &&
                        abs(dy) > abs(dx) * 1.5f &&
                        !isOverVerticallyScrollableWidget(ev.rawX.toInt(), ev.rawY.toInt())
                    ) {
                        intercepted = true
                        activeAction = downAct
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (intercepted) {
                        val pending = activeAction
                        intercepted = false
                        activeAction = null
                        if (ev.actionMasked == MotionEvent.ACTION_UP && pending != null) {
                            Tactile.tick(dragLayer)
                            executeGestureAction(pending)
                        }
                        return true
                    }
                }
            }
            return intercepted
        }

        override fun onControllerTouchEvent(ev: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val pending = activeAction
                    intercepted = false
                    activeAction = null
                    if (ev.actionMasked == MotionEvent.ACTION_UP && pending != null) {
                        Tactile.tick(dragLayer)
                        executeGestureAction(pending)
                    }
                }
            }
            return true
        }

        private fun isHomeReady(): Boolean =
            isInState(LauncherState.NORMAL) &&
                AbstractFloatingView.getTopOpenView(this@NothingLauncher) == null &&
                essentialOverlay?.isShown != true

        private fun isEmptyWorkspacePoint(rawX: Int, rawY: Int): Boolean {
            val hs = hotseat
            if (hs != null && hitContains(hs, rawX, rawY)) return false
            val ws = workspace ?: return false
            val page = ws.getChildAt(ws.currentPage) as? ViewGroup ?: return false
            for (c in 0 until page.childCount) {
                val shortcuts = page.getChildAt(c) as? ViewGroup ?: continue
                for (i in 0 until shortcuts.childCount) {
                    if (hitContains(shortcuts.getChildAt(i), rawX, rawY)) return false
                }
            }
            return true
        }

        private fun isOverVerticallyScrollableWidget(rawX: Int, rawY: Int): Boolean {
            val ws = workspace ?: return false
            val page = ws.getChildAt(ws.currentPage) as? ViewGroup ?: return false
            for (c in 0 until page.childCount) {
                val shortcuts = page.getChildAt(c) as? ViewGroup ?: continue
                for (i in 0 until shortcuts.childCount) {
                    val child = shortcuts.getChildAt(i)
                    if (hitContains(child, rawX, rawY) && canScrollUpRecursive(child)) return true
                }
            }
            return false
        }

        private fun canScrollUpRecursive(view: View): Boolean {
            if (view.canScrollVertically(-1)) return true
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (canScrollUpRecursive(view.getChildAt(i))) return true
                }
            }
            return false
        }

        private fun hitContains(v: View, rawX: Int, rawY: Int): Boolean {
            if (!v.isVisible) return false
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height).contains(rawX, rawY)
        }
    }

    /**
     * First run: the v10 home - LED clock on top, battery & date below (if the cells are free).
     *
     * Returns false (so it is retried on the next bind) only when Launcher3 hasn't registered
     * the Not a Thing elements: CustomWidgetManager skips them while the system's widget
     * provider list is still empty, which can happen right after install. The flag used to be
     * set before trying, so that case silently lost the default home for good. Occupied cells
     * (e.g. a restored layout) still count as done.
     */
    private fun placeDefaultTiles(): Boolean {
        val screen = workspace.getScreenWithId(0) ?: return false
        val widgets = CustomWidgetManager.INSTANCE.get(this)
        fun provider(widget: Class<*>) = widgets.getWidgetProvider(
            ComponentName(packageName, LauncherAppWidgetProviderInfo.CLS_CUSTOM_WIDGET_PREFIX + widget.name)
        )
        if (provider(ClockWidget::class.java) == null) return false
        val cols = deviceProfile.inv.numColumns
        val rows = deviceProfile.inv.numRows
        fun placeAt(widget: Class<*>, col: Int, row: Int, spanX: Int, spanY: Int) {
            if (col + spanX > cols || row + spanY > rows || !screen.isRegionVacant(col, row, spanX, spanY)) return
            val info = provider(widget) ?: return
            addPendingItem(
                PendingAddWidgetInfo(info, LauncherSettings.Favorites.CONTAINER_DESKTOP),
                LauncherSettings.Favorites.CONTAINER_DESKTOP, 0, intArrayOf(col, row), spanX, spanY
            )
        }
        if (cols >= 6 && rows >= 4) {
            // Big-screen Telemetry Studio Bento Grid: balanced 4x2 + 2x2 across all 6 columns.
            placeAt(ClockWidget::class.java, 0, 0, 4, 2)
            placeAt(WeatherWidget::class.java, 4, 0, cols - 4, 2)
            placeAt(BatteryCalendarWidget::class.java, 0, 2, 4, 2)
            placeAt(DeskBriefingWidget::class.java, 4, 2, cols - 4, 2)
        } else {
            var row = 0
            fun place(widget: Class<*>, spanX: Int, spanY: Int) {
                placeAt(widget, 0, row, spanX, spanY)
                row += spanY
            }
            place(ClockWidget::class.java, cols, 2)
            place(BatteryCalendarWidget::class.java, minOf(cols, 4), 2)
        }
        // Adding goes through the drag & drop path, which opens a resize frame on the last tile.
        AbstractFloatingView.closeOpenViews(this, false, AbstractFloatingView.TYPE_WIDGET_RESIZE_FRAME)
        return true
    }
}
