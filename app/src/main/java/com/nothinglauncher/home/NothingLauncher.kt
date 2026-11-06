package com.nothinglauncher.home

import android.content.ComponentName
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
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

    override fun onResume() {
        super.onResume()
        // The backdrop picks the theme, so a change from settings needs a fresh activity.
        if (appliedBackground != null && appliedBackground != prefs.background) {
            recreate()
            return
        }
        // Workspace views stay attached while home is in the background, so live elements are
        // refreshed here: media after notification access was granted, weather when stale.
        // Weather only if an element is on a home screen: otherwise this sent the user's IP to
        // a geolocation service every half hour of home use without anything showing it.
        MediaSessionRepository.refresh()
        if (WeatherRepository.hasListeners()) WeatherRepository.refresh(this, prefs) { }
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
        val bg = ContextCompat.getColor(this, R.color.nothing_bg)
        when (prefs.background) {
            NothingPrefs.Background.DOTS -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setBackgroundDrawable(
                    DotGridDrawable(
                        resources.displayMetrics.density,
                        backgroundColor = bg,
                        dotColor = ContextCompat.getColor(this, R.color.dot_off)
                    )
                )
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
