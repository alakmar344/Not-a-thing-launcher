package com.nothinglauncher

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
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

    var onAppClick: ((AppInfo, View) -> Unit)? = null
    var onAppLongClick: ((AppInfo, View) -> Unit)? = null

    /** Called once a long-pressed dock icon is moved far enough to begin a launcher drag. */
    var onAppDragStart: ((AppInfo, View, Float, Float, Int) -> Unit)? = null

    var showLabels: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                current = emptyList() // force rebuild
                lastApps?.let { setApps(it) }
            }
        }

    var labelShadow: Boolean = false
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
    private var dropTarget: Int? = null

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
        dropTarget = null
        val d = resources.displayMetrics.density

        apps.forEachIndexed { index, app ->
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
            }
            LongPressDragHelper.attach(
                icon,
                onClick = { onAppClick?.invoke(app, it) },
                onLongPress = { onAppLongClick?.invoke(app, it) },
                onDragStart = { v, rawX, rawY -> onAppDragStart?.invoke(app, v, rawX, rawY, index) }
            )
            cell.addView(icon, LayoutParams(iconSize, iconSize))

            if (showLabels) {
                val label = TextView(context).apply {
                    text = app.label
                    textSize = 10f
                    if (labelShadow) {
                        setTextColor(android.graphics.Color.WHITE)
                        setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt())
                    } else {
                        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        setShadowLayer(0f, 0f, 0f, 0)
                    }
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
            cell.isHapticFeedbackEnabled = true
            addView(cell, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    /** Index the user is hovering over while dragging an icon. */
    fun dropIndexAt(rawX: Float): Int {
        if (childCount == 0) return 0
        val loc = IntArray(2)
        // Children are laid out right-to-left in RTL, so "before" a slot means right of its centre.
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.getLocationOnScreen(loc)
            val center = loc[0] + child.width / 2f
            if (if (rtl) rawX > center else rawX < center) return i
        }
        return childCount
    }

    /** Lifts the nearest slot while an icon is dragged over the dock. */
    fun showDropTarget(index: Int?) {
        if (dropTarget == index) return
        dropTarget = index
        val lift = -10f * resources.displayMetrics.density
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val active = index != null && (i == index || (index == childCount && i == childCount - 1))
            child.animate()
                .translationY(if (active) lift else 0f)
                .scaleX(if (active) 1.08f else 1f)
                .scaleY(if (active) 1.08f else 1f)
                .setDuration(120)
                .start()
        }
    }

    fun clearDropTarget() = showDropTarget(null)

    override fun onDetachedFromWindow() {
        clearDropTarget()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.animate().cancel()
            child.translationY = 0f
            child.scaleX = 1f
            child.scaleY = 1f
        }
        super.onDetachedFromWindow()
    }
}
