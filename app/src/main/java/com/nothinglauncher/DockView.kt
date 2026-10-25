package com.nothinglauncher

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs

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
        val slop = ViewConfiguration.get(context).scaledTouchSlop

        apps.forEachIndexed { index, app ->
            val cell = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
            }
            var downX = 0f
            var downY = 0f
            var downRawX = 0f
            var downRawY = 0f
            var longPressed = false
            val icon = ImageView(context).apply {
                setImageDrawable(app.icon)
                contentDescription = app.label
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundResource(R.drawable.fg_ripple_oval)
                isHapticFeedbackEnabled = true
                setOnClickListener { onAppClick?.invoke(app, this) }
            }
            val longPressRunnable = Runnable {
                if (!icon.isAttachedToWindow) return@Runnable
                longPressed = true
                icon.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            icon.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        longPressed = false
                        v.isPressed = true
                        v.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!longPressed) {
                            if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                                v.removeCallbacks(longPressRunnable)
                            }
                        } else {
                            val dx = abs(event.rawX - downRawX)
                            val dy = abs(event.rawY - downRawY)
                            if (dx > slop * 1.5f || dy > slop * 1.5f) {
                                v.removeCallbacks(longPressRunnable)
                                longPressed = false
                                v.isPressed = false
                                onAppDragStart?.invoke(app, v, event.rawX, event.rawY, index)
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.removeCallbacks(longPressRunnable)
                        v.isPressed = false
                        if (longPressed) {
                            longPressed = false
                            onAppLongClick?.invoke(app, icon)
                        } else {
                            v.performClick()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(longPressRunnable)
                        v.isPressed = false
                        longPressed = false
                    }
                }
                true
            }

            // Accessibility-only long-click; normal touches are consumed above.
            icon.setOnLongClickListener {
                onAppLongClick?.invoke(app, icon)
                true
            }
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
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.getLocationOnScreen(loc)
            val center = loc[0] + child.width / 2f
            if (rawX < center) return i
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
        super.onDetachedFromWindow()
    }
}
