package com.nothinglauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Hosts third-party widgets on the home screen. */
class WidgetHostManager(context: Context) : AppWidgetHost(context, HOST_ID) {

    private val viewsCache = mutableMapOf<Int, AppWidgetHostView>()

    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        LauncherWidgetHostView(context)

    fun getOrCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView? {
        val cached = viewsCache[appWidgetId]
        if (cached != null) return cached
        return try {
            val view = createView(context, appWidgetId, appWidget)
            viewsCache[appWidgetId] = view
            view
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun deleteAppWidgetId(appWidgetId: Int) {
        viewsCache.remove(appWidgetId)
        super.deleteAppWidgetId(appWidgetId)
    }

    public override fun clearViews() {
        viewsCache.clear()
        super.clearViews()
    }

    companion object {
        const val HOST_ID = 1024
    }
}

/**
 * Widget container that can be long-pressed (to remove or drag it) without breaking the widget's
 * own taps and scrolls: we only steal the gesture once the long-press timeout fires.
 */
class LauncherWidgetHostView(context: Context) : AppWidgetHostView(context) {

    var onLongPress: (() -> Unit)? = null
    var onDragStart: ((View, Float, Float) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var longPressed = false

    private var longPressRawX = 0f
    private var longPressRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    private val longPressRunnable = Runnable {
        if (parent != null && isAttachedToWindow) {
            longPressed = true
            longPressRawX = lastRawX
            longPressRawY = lastRawY
            // Stock behaviour: the menu shows while the finger is down, and a following move
            // drags the widget. Scroll containers must not steal the rest of the gesture.
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) longPressed = false
        if (longPressed) {
            // Take the gesture from the widget's own views so a move can start a drag.
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                longPressed = false
            }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downRawX = ev.rawX
                downRawY = ev.rawY
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                longPressed = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) {
                    removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressRunnable)
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                // When no child consumed the down, moves arrive here instead of the intercept:
                // a drag across the widget must still cancel the pending long press.
                if (!longPressed && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
                    removeCallbacks(longPressRunnable)
                }
                if (longPressed) {
                    val dx = abs(event.rawX - longPressRawX)
                    val dy = abs(event.rawY - longPressRawY)
                    if (dx > slop || dy > slop) {
                        removeCallbacks(longPressRunnable)
                        longPressed = false
                        onDragStart?.invoke(this, event.rawX, event.rawY)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (longPressed) {
                    longPressed = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                longPressed = false
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        longPressed = false
        super.onDetachedFromWindow()
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(longPressRunnable)
    }
}
