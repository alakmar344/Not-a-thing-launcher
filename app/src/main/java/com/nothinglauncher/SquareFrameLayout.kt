package com.nothinglauncher

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A FrameLayout whose height always equals its width (used for the square home tiles). */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Square, but never taller than the space offered (wide widget cells would crop it).
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val side = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w
        else minOf(w, MeasureSpec.getSize(heightMeasureSpec))
        val square = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(square, square)
    }
}
