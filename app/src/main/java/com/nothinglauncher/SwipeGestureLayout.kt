package com.nothinglauncher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * A FrameLayout that steals clearly-vertical drags from its children when the [listener]
 * agrees, and reports them continuously so the UI can follow the finger
 * (e.g. the app drawer sliding up from the home screen).
 */
class SwipeGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Listener {
        /** [direction] is [UP] (finger moving up) or [DOWN]. */
        fun canStartDrag(direction: Int, downX: Float, downY: Float): Boolean
        fun onDragStart(direction: Int) {}
        /** [totalDy] is the finger travel since the drag started (negative = up). */
        fun onDrag(direction: Int, totalDy: Float) {}
        /** [velocityY] in px/s (negative = up). */
        fun onDragEnd(direction: Int, totalDy: Float, velocityY: Float) {}
    }

    var listener: Listener? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragStartY = 0f
    private var lastDy = 0f
    private var dragging = false
    private var direction = 0
    private var tracker: VelocityTracker? = null

    val isDragging: Boolean get() = dragging

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetTracking(ev)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (!dragging && maybeStartDrag(ev)) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) recycleTracker()
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reached only when no child consumed the touch (empty space): keep the stream.
                resetTracking(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (!dragging) maybeStartDrag(ev)
                if (dragging) {
                    lastDy = ev.y - dragStartY
                    listener?.onDrag(direction, lastDy)
                }
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(ev)
                if (dragging) {
                    val vt = tracker
                    vt?.computeCurrentVelocity(1000)
                    finishDrag(vt?.yVelocity ?: 0f)
                }
                recycleTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) finishDrag(0f)
                recycleTracker()
            }
        }
        return true
    }

    private fun maybeStartDrag(ev: MotionEvent): Boolean {
        val dy = ev.y - downY
        val dx = ev.x - downX
        if (abs(dy) <= touchSlop || abs(dy) <= abs(dx) * 1.2f) return false
        val dir = if (dy < 0) UP else DOWN
        if (listener?.canStartDrag(dir, downX, downY) != true) return false
        dragging = true
        direction = dir
        dragStartY = ev.y
        lastDy = 0f
        parent?.requestDisallowInterceptTouchEvent(true)
        listener?.onDragStart(dir)
        return true
    }

    private fun finishDrag(velocityY: Float) {
        dragging = false
        listener?.onDragEnd(direction, lastDy, velocityY)
    }

    private fun resetTracking(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        dragging = false
        recycleTracker()
        tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    private fun recycleTracker() {
        tracker?.recycle()
        tracker = null
    }

    companion object {
        const val UP = -1
        const val DOWN = 1
    }
}
