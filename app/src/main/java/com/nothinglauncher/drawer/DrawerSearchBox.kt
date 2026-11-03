package com.nothinglauncher.drawer

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ViewGroup
import com.android.launcher3.allapps.search.AppsSearchContainerLayout

/**
 * Stock drawer search box, placed under [DrawerHeader]. The header already absorbs the status
 * bar inset, so this box must not add it a second time.
 */
class DrawerSearchBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppsSearchContainerLayout(context, attrs, defStyleAttr) {

    override fun setInsets(insets: Rect) {
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = 0
            layoutParams = it
        }
    }
}
