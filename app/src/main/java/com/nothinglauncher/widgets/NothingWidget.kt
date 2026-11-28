package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
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
 * describe themselves, optionally supply a picker preview drawable, and build their view.
 */
abstract class NothingWidget(protected val appContext: Context) : CustomWidgetPlugin {

    abstract val labelRes: Int
    /** One-line description, used for accessibility and Android 12+ picker cards. */
    abstract val descriptionRes: Int
    /** Optional drawable preview rendered in Launcher3's stock widget picker. */
    open val previewRes: Int = 0
    open val spanX: Int = 4
    open val spanY: Int = 2
    open val minSpanX: Int = 2
    open val minSpanY: Int = 1
    open val resizeMode: Int =
        AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL

    internal class LifecycleFrameLayout(
        context: Context,
        private val onVisibilityChangedCallback: (visible: Boolean) -> Unit
    ) : FrameLayout(context) {
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            onVisibilityChangedCallback(windowVisibility == VISIBLE && isShown)
        }

        override fun onDetachedFromWindow() {
            onVisibilityChangedCallback(false)
            super.onDetachedFromWindow()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            onVisibilityChangedCallback(visibility == VISIBLE && isShown)
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            onVisibilityChangedCallback(isVisible && isShown)
        }
    }

    /** Builds the element's view for a newly bound widget host. */
    abstract fun createView(host: AppWidgetHostView): View

    private companion object {
        const val MAX_SPAN = 12
        val PROVIDER_INFO_FIELD: java.lang.reflect.Field? by lazy {
            try {
                AppWidgetProviderInfo::class.java.getDeclaredField("providerInfo").apply {
                    isAccessible = true
                }
            } catch (_: Throwable) {
                null
            }
        }
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
        // Tiles sink a little under the finger with spring rebound. Long-press still reaches Launcher3
        // (the touch listener never consumes), and a drag cancels the press, springing the tile back.
        com.nothinglauncher.Tactile.pressTiles(view, pressedScale = 0.975f)
    }

    override fun updateWidgetInfo(info: AppWidgetProviderInfo, context: Context) {
        // Group the elements under this app in the stock picker and load previews/descriptions
        // from this package instead of the cloned seed provider.
        info.provider = ComponentName(
            context.packageName,
            LauncherAppWidgetProviderInfo.CLS_CUSTOM_WIDGET_PREFIX + javaClass.name
        )
        try {
            val field = PROVIDER_INFO_FIELD
            if (field != null) {
                val current = field.get(info) as? ActivityInfo
                field.set(
                    info,
                    (current?.let(::ActivityInfo) ?: ActivityInfo()).apply {
                        packageName = context.packageName
                        name = javaClass.name
                        applicationInfo = context.applicationInfo
                    }
                )
            }
        } catch (_: Throwable) {
            // Optional platform field on some SDK/test environments.
        }
        info.label = context.getString(labelRes)
        info.icon = com.nothinglauncher.R.mipmap.ic_launcher
        info.initialLayout = 0
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
        info.previewImage = previewRes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.previewLayout = 0
            info.descriptionRes = descriptionRes
        }
        info.resizeMode = resizeMode
        info.widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
        if (info is LauncherAppWidgetProviderInfo) {
            info.spanX = spanX
            info.spanY = spanY
            info.minSpanX = minSpanX
            info.minSpanY = minSpanY
            info.maxSpanX = MAX_SPAN
            info.maxSpanY = MAX_SPAN
        }
    }
}
