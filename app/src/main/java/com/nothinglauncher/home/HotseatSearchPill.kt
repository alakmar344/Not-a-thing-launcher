package com.nothinglauncher.home

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.nothinglauncher.BigScreenUi
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Tactile
import com.nothinglauncher.drawer.HiddenVaultSheet

/**
 * The v10/v13 "Search apps & web" pill under the dock, in the slot Launcher3 reserves for a hotseat
 * search bar. Tapping it opens the drawer with the keyboard; the grid button opens the drawer;
 * long-pressing opens the Hidden Vault.
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
        Tactile.press(this, 0.98f)
        setOnClickListener { openDrawer(search = true) }
        setOnLongClickListener {
            val launcher = try {
                Launcher.getLauncher(context)
            } catch (_: RuntimeException) {
                null
            }
            if (launcher != null) {
                HiddenVaultSheet.openWithAuth(launcher) {
                    (launcher as? NothingLauncher)?.refreshHiddenApps()
                }
            }
            true
        }
        findViewById<View>(R.id.pillApps).setOnClickListener { openDrawer(search = false) }
        refreshVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshVisibility()
    }

    fun refreshVisibility() {
        isVisible = NothingPrefs.get(context).showSearchPill
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dp = try {
            Launcher.getLauncher(context).deviceProfile
        } catch (_: RuntimeException) {
            null // not hosted by the launcher (preview): use the offered size
        }
        if (dp == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val offeredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val offeredMode = MeasureSpec.getMode(widthMeasureSpec)
        // On tablets/landscape with inline QSB, Launcher3 already supplies the exact slot width.
        if (dp.isQsbInline && offeredWidth > 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val inset = dp.workspacePadding.left + dp.cellLayoutPaddingPx.left
        val rawWidth = if (offeredMode == MeasureSpec.EXACTLY && offeredWidth > 0) {
            offeredWidth
        } else {
            (dp.widthPx - 2 * inset).coerceAtLeast(0)
        }
        val density = resources.displayMetrics.density
        val width = if (BigScreenUi.isLargeScreen(context)) {
            minOf(rawWidth, (580f * density).toInt())
        } else {
            rawWidth
        }
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
