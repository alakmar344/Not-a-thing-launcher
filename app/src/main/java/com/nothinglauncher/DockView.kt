package com.nothinglauncher

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/** The dock: up to five label-less icons spread evenly across the bottom of home. */
class DockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onAppClick: ((AppInfo, ImageView) -> Unit)? = null
    var onAppLongClick: ((AppInfo, ImageView) -> Unit)? = null

    private val iconSize = resources.getDimensionPixelSize(R.dimen.dock_icon_size)
    private var current: List<String> = emptyList()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun setApps(apps: List<AppInfo>) {
        val keys = apps.map { it.key + "@" + System.identityHashCode(it.icon) }
        if (keys == current) return
        current = keys
        removeAllViews()
        apps.forEach { app ->
            val cell = FrameLayout(context)
            val icon = ImageView(context).apply {
                setImageDrawable(app.icon)
                contentDescription = app.label
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundResource(R.drawable.fg_ripple_oval)
                isHapticFeedbackEnabled = true
                setOnClickListener { onAppClick?.invoke(app, this) }
                setOnLongClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onAppLongClick?.invoke(app, this)
                    true
                }
            }
            cell.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            addView(cell, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }
}
