package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.systemui.plugins.CustomWidgetPlugin

/**
 * A Not a Thing dot-matrix element, hosted by stock Launcher3 as an in-process custom widget.
 *
 * Launcher3 lists every class in `R.array.custom_widget_providers` in its widget picker; the
 * user adds, moves, resizes and removes elements exactly like any other widget. Subclasses only
 * describe themselves and build their view.
 */
abstract class NothingWidget(protected val appContext: Context) : CustomWidgetPlugin {

    abstract val labelRes: Int
    /** One-line description, used for accessibility. */
    abstract val descriptionRes: Int
    open val spanX: Int = 4
    open val spanY: Int = 2
    open val minSpanX: Int = 2
    open val minSpanY: Int = 1
    open val resizeMode: Int =
        AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL

    /** Builds the element's view for a newly bound widget host. */
    abstract fun createView(host: AppWidgetHostView): View

    private companion object {
        const val MAX_SPAN = 12
    }

    final override fun onViewCreated(parent: AppWidgetHostView) {
        parent.removeAllViews()
        // The host already applies the widget cell padding; the element fills it.
        parent.setPadding(0, 0, 0, 0)
        val view = createView(parent)
        parent.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // Tiles sink a little under the finger. Long-press still reaches Launcher3 (the touch
        // listener never consumes), and a drag cancels the press, which springs the tile back.
        com.nothinglauncher.Tactile.pressTiles(view, pressedScale = 0.975f)
    }

    override fun updateWidgetInfo(info: AppWidgetProviderInfo, context: Context) {
        // Group the elements under this app in the stock picker (and load previews/icons from
        // this package). The class name keeps Launcher3's custom-widget prefix.
        info.provider = ComponentName(
            context.packageName,
            LauncherAppWidgetProviderInfo.CLS_CUSTOM_WIDGET_PREFIX + javaClass.name
        )
        info.label = context.getString(labelRes)
        info.icon = com.nothinglauncher.R.mipmap.ic_launcher
        info.initialLayout = 0
        // Everything else was inherited from that other app's widget: a configure screen would
        // make Launcher3 try to configure our (negative, in-process) widget id and crash, and
        // its size limits would clamp ours.
        info.configure = null
        // Field added in Android 9; touching it on 8.0/8.1 throws NoSuchFieldError.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.widgetFeatures = 0
        info.minWidth = 0
        info.minHeight = 0
        info.minResizeWidth = 0
        info.minResizeHeight = 0
        info.updatePeriodMillis = 0
        info.autoAdvanceViewId = View.NO_ID
        info.initialKeyguardLayout = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.targetCellWidth = spanX
            info.targetCellHeight = spanY
            info.maxResizeWidth = 0
            info.maxResizeHeight = 0
        }
        // The info is cloned from another app's provider (CustomWidgetManager), and the framework
        // resolves preview/description resources through that clone's package. Clear them so the
        // picker draws Launcher3's own preview tile (grid + this app's icon) instead.
        info.previewImage = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.previewLayout = 0
            info.descriptionRes = 0
        }
        info.resizeMode = resizeMode
        info.widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
        if (info is LauncherAppWidgetProviderInfo) {
            info.spanX = spanX
            info.spanY = spanY
            info.minSpanX = minSpanX
            info.minSpanY = minSpanY
            // Launcher3 clamps these to the grid, so any grid size can be filled.
            info.maxSpanX = MAX_SPAN
            info.maxSpanY = MAX_SPAN
        }
    }

}
