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
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val square = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        super.onMeasure(square, square)
    }
}
