package com.nothinglauncher

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Stock-launcher (Launcher3) touch model for anything that lives on the home screen, in the
 * dock, the drawer or a folder:
 *
 *  - tap                      -> [onClick]
 *  - long-press (held still)  -> haptic + [onLongPress] (shows the shortcut / action menu)
 *  - long-press, then move    -> [onDragStart] (the menu is dismissed and the item follows the finger)
 *
 * The moment the long-press fires the helper asks every parent (page scroller, horizontal page
 * pager, swipe-to-drawer layout) to stop intercepting, otherwise the first move of the finger is
 * stolen by a scroll container and the item can never be dragged.
 */
class LongPressDragHelper(
    private val view: View,
    private val onClick: ((View) -> Unit)?,
    private val onLongPress: ((View) -> Unit)?,
    private val onDragStart: ((View, Float, Float) -> Unit)?
) : View.OnTouchListener {

    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var longPressRawX = 0f
    private var longPressRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var longPressed = false
    private var movedBeforeLongPress = false
    private var dragStarted = false

    private val longPressRunnable = Runnable {
        if (!view.isAttachedToWindow || movedBeforeLongPress) return@Runnable
        longPressed = true
        longPressRawX = lastRawX
        longPressRawY = lastRawY
        view.isPressed = false
        view.parent?.requestDisallowInterceptTouchEvent(true)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastRawX = event.rawX
                lastRawY = event.rawY
                longPressed = false
                movedBeforeLongPress = false
                dragStarted = false
                v.isPressed = true
                v.removeCallbacks(longPressRunnable)
                v.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                if (dragStarted) return true
                if (!longPressed) {
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                        movedBeforeLongPress = true
                        v.isPressed = false
                        v.removeCallbacks(longPressRunnable)
                    }
                } else if (hypot(event.rawX - longPressRawX, event.rawY - longPressRawY) > slop) {
                    dragStarted = true
                    onDragStart?.invoke(v, event.rawX, event.rawY)
                }
            }
            MotionEvent.ACTION_UP -> {
                v.removeCallbacks(longPressRunnable)
                v.isPressed = false
                if (!longPressed && !movedBeforeLongPress && !dragStarted) {
                    // [attach] installs [onClick] as the click listener, so performClick() covers
                    // both cases and also plays the click sound / sends the accessibility event.
                    if (!v.performClick()) onClick?.invoke(v)
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(longPressRunnable)
                v.isPressed = false
                reset()
            }
        }
        return true
    }

    private fun reset() {
        longPressed = false
        movedBeforeLongPress = false
        dragStarted = false
    }

    companion object {
        /**
         * Installs the helper on [view]. Accessibility long-click is wired to [onLongPress] so
         * TalkBack users keep the action menu.
         */
        fun attach(
            view: View,
            onClick: ((View) -> Unit)?,
            onLongPress: ((View) -> Unit)?,
            onDragStart: ((View, Float, Float) -> Unit)?
        ) {
            view.isClickable = true
            view.isLongClickable = true
            view.setOnTouchListener(LongPressDragHelper(view, onClick, onLongPress, onDragStart))
            // With no explicit [onClick] the view's own OnClickListener handles taps.
            if (onClick != null) view.setOnClickListener { onClick.invoke(it) }
            view.setOnLongClickListener {
                onLongPress?.invoke(it)
                true
            }
        }
    }
}
