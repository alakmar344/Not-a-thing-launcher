package com.nothinglauncher.drawer

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ViewGroup
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.search.AppsSearchContainerLayout

/**
 * Upgraded drawer Command Bar placed under [DrawerHeader].
 * Wires [NothingSearchAlgorithm] cleanly into Launcher3's `AppsSearchContainerLayout`.
 */
class DrawerSearchBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppsSearchContainerLayout(context, attrs, defStyleAttr) {

    override fun initializeSearch(appsView: ActivityAllAppsContainerView<*>) {
        super.initializeSearch(appsView)
        // Swap stock DefaultAppSearchAlgorithm for NothingSearchAlgorithm using AllAppsSearchBarController's
        // existing text watcher, IME editor listener, and callback wiring.
        try {
            val field = AppsSearchContainerLayout::class.java.getDeclaredField("mSearchBarController")
            field.isAccessible = true
            val controller = field.get(this) as? com.android.launcher3.allapps.search.AllAppsSearchBarController
            controller?.initialize(NothingSearchAlgorithm(context), this, com.android.launcher3.views.ActivityContext.lookupContext(context), this)
        } catch (_: Throwable) {
            // Graceful fallback to stock search algorithm if field signature ever changes.
        }
    }

    override fun setInsets(insets: Rect) {
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = 0
            layoutParams = it
        }
    }
}
