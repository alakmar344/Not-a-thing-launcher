package com.nothinglauncher.home

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.nothinglauncher.R

/**
 * The v10 "Search apps & web" pill under the dock, in the slot Launcher3 reserves for a hotseat
 * search bar. Tapping it opens the drawer with the keyboard; the grid button opens the drawer.
 *
 * On phone grids Launcher3 measures this slot at width 0 (it is meant for scalable grids), so the
 * pill sizes itself to the icon area of the dock.
 */
class HotseatSearchPill @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    override fun onFinishInflate() {
        super.onFinishInflate()
        setOnClickListener { openDrawer(search = true) }
        findViewById<View>(R.id.pillApps).setOnClickListener { openDrawer(search = false) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dp = Launcher.getLauncher(context).deviceProfile
        val inset = dp.workspacePadding.left + dp.cellLayoutPaddingPx.left
        val width = (dp.widthPx - 2 * inset).coerceAtLeast(0)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            heightMeasureSpec
        )
    }

    private fun openDrawer(search: Boolean) {
        val launcher = Launcher.getLauncher(context)
        launcher.stateManager.goToState(LauncherState.ALL_APPS)
        if (search) {
            launcher.appsView.searchUiManager.editText?.showKeyboard()
        }
    }
}
