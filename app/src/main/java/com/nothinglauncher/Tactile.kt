package com.nothinglauncher

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.nothinglauncher.perf.VisualTier

/**
 * Physical spring micro-interactions for tiles, pills, and icons.
 * Uses AndroidX [SpringAnimation] for natural velocity-preserving rebounds, with haptic
 * confirmation and optional synthesized mechanical click audio.
 */
object Tactile {

    private fun spring(view: View, prop: DynamicAnimation.ViewProperty, target: Float, stiffness: Float, damping: Float) {
        if (Looper.myLooper() == null) {
            view.scaleX = target
            view.scaleY = target
            return
        }
        val tagKey = if (prop === DynamicAnimation.SCALE_X) R.id.nt_spring_x else R.id.nt_spring_y
        val existing = view.getTag(tagKey) as? SpringAnimation
        val anim = existing ?: SpringAnimation(view, prop, target).also {
            view.setTag(tagKey, it)
        }
        anim.spring = (anim.spring ?: SpringForce(target)).apply {
            finalPosition = target
            this.stiffness = stiffness
            this.dampingRatio = damping
        }
        anim.animateToFinalPosition(target)
    }

    @SuppressLint("ClickableViewAccessibility") // Never consumes the event; clicks and long-press pass through.
    fun press(view: View, pressedScale: Float = 0.965f) {
        val bounds = Rect()
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (VisualTier.motionAllowed(v.context)) {
                    spring(v, DynamicAnimation.SCALE_X, pressedScale, 720f, SpringForce.DAMPING_RATIO_NO_BOUNCY)
                    spring(v, DynamicAnimation.SCALE_Y, pressedScale, 720f, SpringForce.DAMPING_RATIO_NO_BOUNCY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        bounds.set(0, 0, v.width, v.height)
                        if (bounds.contains(e.x.toInt(), e.y.toInt())) tick(v)
                    }
                    if (v.scaleX != 1f || v.scaleY != 1f) {
                        if (VisualTier.motionAllowed(v.context)) {
                            spring(v, DynamicAnimation.SCALE_X, 1f, 380f, SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                            spring(v, DynamicAnimation.SCALE_Y, 1f, 380f, SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                        } else {
                            v.scaleX = 1f
                            v.scaleY = 1f
                        }
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

    /** A subtle confirmation haptic tick + optional mechanical click sound. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        TactileAudio.playTick(view.context)
    }

    /** Stronger milestone confirmation (habit checked, timer started, mode toggled). */
    fun confirm(view: View) {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(flag)
        TactileAudio.playTick(view.context)
    }
}
