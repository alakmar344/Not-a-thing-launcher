package com.nothinglauncher

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/** The dock: up to five icons spread evenly across the bottom of home, with optional labels. */
class DockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onAppClick: ((AppInfo, ImageView) -> Unit)? = null
    var onAppLongClick: ((AppInfo, ImageView) -> Unit)? = null

    var showLabels: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                current = emptyList() // force rebuild
                lastApps?.let { setApps(it) }
            }
        }

    private val iconSize = resources.getDimensionPixelSize(R.dimen.dock_icon_size)
    private var current: List<String> = emptyList()
    private var lastApps: List<AppInfo>? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun setApps(apps: List<AppInfo>) {
        lastApps = apps
        val keys = apps.map { it.key + "@" + System.identityHashCode(it.icon) + "@" + showLabels }
        if (keys == current) return
        current = keys
        removeAllViews()
        val d = resources.displayMetrics.density

        apps.forEach { app ->
            val cell = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
            }
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
            cell.addView(icon, LayoutParams(iconSize, iconSize))

            if (showLabels) {
                val label = TextView(context).apply {
                    text = app.label
                    textSize = 10f
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    typeface = try { ResourcesCompat.getFont(context, R.font.space_grotesk) } catch (e: Exception) { null }
                    val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = (2 * d).toInt()
                    }
                    layoutParams = lp
                }
                cell.addView(label)
            }
            addView(cell, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }
}
