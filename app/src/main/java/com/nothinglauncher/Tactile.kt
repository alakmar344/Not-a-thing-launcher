package com.nothinglauncher

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.nothinglauncher.perf.VisualTier

/**
 * Makes tiles feel physical: they sink slightly under the finger and spring back on release,
 * with a light haptic tick when the tap lands. Scale is a RenderThread property animation (no
 * relayout, no redraw of the content), so it is cheap enough for every tier; it is skipped
 * when the user turned animations off or Battery Saver is on. Haptics follow the system's
 * "touch feedback" setting because they go through [View.performHapticFeedback].
 */
object Tactile {

    private val pressInterpolator = DecelerateInterpolator(1.6f)
    private val releaseInterpolator = OvershootInterpolator(1.4f)

    @SuppressLint("ClickableViewAccessibility") // Never consumes the event; clicks are unchanged.
    fun press(view: View, pressedScale: Float = 0.965f) {
        val bounds = Rect()
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (VisualTier.motionAllowed(v.context)) {
                    v.animate().scaleX(pressedScale).scaleY(pressedScale)
                        .setDuration(110L).setInterpolator(pressInterpolator).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        bounds.set(0, 0, v.width, v.height)
                        if (bounds.contains(e.x.toInt(), e.y.toInt())) tick(v)
                    }
                    if (v.scaleX != 1f || v.scaleY != 1f) {
                        v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(280L).setInterpolator(releaseInterpolator).start()
                    }
                }
            }
            false
        }
    }

    /** [press] on [root] if it is a tile itself, else on each directly clickable child tile. */
    fun pressTiles(root: View, pressedScale: Float = 0.965f) {
        if (root.isClickable) {
            press(root, pressedScale)
        } else if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child.isClickable) press(child, pressedScale)
            }
        }
    }

    /** A subtle confirmation tick for toggles and selections. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
