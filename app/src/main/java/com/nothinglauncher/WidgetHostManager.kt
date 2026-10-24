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

    private val longPressRunnable = Runnable {
        if (parent != null && isAttachedToWindow) {
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (longPressed) {
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
                longPressed = false
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
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
                if (longPressed) {
                    val dx = abs(event.rawX - downRawX)
                    val dy = abs(event.rawY - downRawY)
                    if (dx > slop * 1.5f || dy > slop * 1.5f) {
                        longPressed = false
                        onDragStart?.invoke(this, event.rawX, event.rawY)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                longPressed = false
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        return super.onTouchEvent(event) || longPressed
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(longPressRunnable)
    }
}
