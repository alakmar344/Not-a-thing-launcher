package com.nothinglauncher.home

import android.content.ComponentName
import android.graphics.drawable.ColorDrawable
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings
import com.android.launcher3.util.IntSet
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.launcher3.widget.PendingAddWidgetInfo
import com.android.launcher3.widget.custom.CustomWidgetManager
import com.nothinglauncher.DotGridDrawable
import com.nothinglauncher.DotMatrixClockView
import com.nothinglauncher.perf.VisualTier
import com.nothinglauncher.MediaSessionRepository
import com.nothinglauncher.WeatherRepository
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.icons.NothingIconLogic
import com.nothinglauncher.widgets.BatteryCalendarWidget
import com.nothinglauncher.widgets.ClockWidget

/**
 * Stock Launcher3 with the Not a Thing v10 surface: dot-grid (or black) backdrop, the v10
 * customize sheet on long-press, and the v10 home tiles on first run. All home screen logic
 * (model, drag & drop, folders, widgets) is untouched Launcher3.
 */
class NothingLauncher : Launcher() {

    private companion object {
        /** Away from home at least this long → the clock plays its power-on sweep. */
        const val POWER_ON_AFTER_MS = 5 * 60_000L
    }

    private val prefs by lazy { NothingPrefs.get(this) }

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
        // The backdrop picks the theme, so a change from settings needs a fresh activity.
        if (appliedBackground != null &&
            (appliedBackground != prefs.background || appliedTier != VisualTier.current(this))
        ) {
            recreate()
            return
        }
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
        val bg = ContextCompat.getColor(this, R.color.nothing_bg)
        when (prefs.background) {
            NothingPrefs.Background.DOTS -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                // Lite: flat grid. Balanced: + vignette. Rich: + the top "light pool".
                window.setBackgroundDrawable(DotGridDrawable.forTier(this))
            }
            NothingPrefs.Background.BLACK -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setBackgroundDrawable(ColorDrawable(bg))
            }
            NothingPrefs.Background.WALLPAPER -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setBackgroundDrawable(ColorDrawable(0))
            }
        }
    }

    /** Long-press on empty home: the v10 "Customize" sheet instead of the stock menu. */
    override fun showDefaultOptions(x: Float, y: Float) {
        CustomizeSheet.show(this)
    }

    override fun finishBindingItems(pagesBoundFirst: IntSet?) {
        super.finishBindingItems(pagesBoundFirst)
        if (!prefs.defaultTilesPlaced && placeDefaultTiles()) prefs.defaultTilesPlaced = true
    }

    /**
     * First run: the v10 home – LED clock on top, battery & date below (if the cells are free).
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
        var row = 0
        fun place(widget: Class<*>, spanX: Int, spanY: Int) {
            if (row + spanY > rows || !screen.isRegionVacant(0, row, spanX, spanY)) return
            val info = provider(widget) ?: return
            addPendingItem(
                PendingAddWidgetInfo(info, LauncherSettings.Favorites.CONTAINER_DESKTOP),
                LauncherSettings.Favorites.CONTAINER_DESKTOP, 0, intArrayOf(0, row), spanX, spanY
            )
            row += spanY
        }
        place(ClockWidget::class.java, cols, 2)
        place(BatteryCalendarWidget::class.java, minOf(cols, 4), 2)
        // Adding goes through the drag & drop path, which opens a resize frame on the last tile.
        AbstractFloatingView.closeOpenViews(this, false, AbstractFloatingView.TYPE_WIDGET_RESIZE_FRAME)
        return true
    }
}
