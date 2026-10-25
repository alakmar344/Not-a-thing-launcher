package com.nothinglauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.nothinglauncher.R
import com.nothinglauncher.model.ItemInfo
import kotlin.math.min

/**
 * Folder on the home screen: a raised disc with a 2×2 (or 3×3 when "Max") preview of its
 * contents, a name label and an accent ring that blooms when an app is dragged over it.
 */
class FolderIcon(context: Context) : View(context), ItemView {

    override var item: ItemInfo? = null
    var contents: List<ItemInfo> = emptyList()
        set(value) {
            field = value
            updateDescription()
            invalidate()
        }

    var iconSizePx = (54 * resources.displayMetrics.density).toInt()
        set(value) { field = value; requestLayout(); invalidate() }
    var showLabel = true
        set(value) { field = value; requestLayout(); invalidate() }
    var labelShadow = false
        set(value) {
            field = value
            if (value) textPaint.setShadowLayer(4f * density, 0f, 1f * density, 0xB0000000.toInt()) else textPaint.clearShadowLayer()
            invalidate()
        }
    var textSizePx: Float
        get() = textPaint.textSize
        set(value) { textPaint.textSize = value; requestLayout() }
    var accentColor: Int = ContextCompat.getColor(context, R.color.nothing_red)
        set(value) { field = value; invalidate() }
    var isMax = false
        set(value) { field = value; invalidate() }
    var dotCount = 0
        set(value) { field = value; invalidate() }
    var gapPx = (6 * resources.displayMetrics.density).toInt()

    private val density = resources.displayMetrics.density
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.surface_raised) }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = ContextCompat.getColor(context, R.color.hairline_strong)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = try { ResourcesCompat.getFont(context, R.font.space_grotesk) } catch (e: Exception) { null }
    }
    private val iconRect = Rect()
    private val childRect = Rect()
    private var hover = 0f
    private var hoverAnim: ValueAnimator? = null
    private var pressScale = 1f

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
    }

    fun bind(info: ItemInfo, items: List<ItemInfo>) {
        item = info
        contents = items
        invalidate()
    }

    private fun updateDescription() {
        contentDescription = context.getString(R.string.a11y_folder, item?.title ?: "", contents.size)
    }

    private fun labelHeight(): Int {
        val fm = textPaint.fontMetrics
        return (fm.descent - fm.ascent).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val contentH = paddingTop + paddingBottom + iconSizePx + if (showLabel) gapPx + labelHeight() else 0
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else contentH
        setMeasuredDimension(w, h)
    }

    private fun discSize(): Int {
        if (!isMax) return iconSizePx
        val labelH = if (showLabel) labelHeight() + gapPx else 0
        return (min(width, height - labelH) * 0.86f).toInt().coerceAtLeast(iconSizePx)
    }

    fun getIconBounds(out: Rect) {
        val size = discSize()
        val labelH = if (showLabel) gapPx + labelHeight() else 0
        val top = paddingTop + ((height - paddingTop - paddingBottom - size - labelH) / 2).coerceAtLeast(0)
        val left = (width - size) / 2
        out.set(left, top, left + size, top + size)
    }

    /** Hover feedback while an icon is dragged over the folder. */
    fun setDragHover(active: Boolean) {
        val target = if (active) 1f else 0f
        if (hover == target) return
        hoverAnim?.cancel()
        hoverAnim = ValueAnimator.ofFloat(hover, target).apply {
            duration = if (active) 260 else 160
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { hover = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        getIconBounds(iconRect)
        val cx = iconRect.exactCenterX()
        val cy = iconRect.exactCenterY()
        val r = iconRect.width() / 2f
        val save = canvas.save()
        canvas.scale(pressScale, pressScale, cx, cy)
        val grow = 1f + 0.16f * hover
        canvas.drawCircle(cx, cy, r * grow, discPaint)
        canvas.drawCircle(cx, cy, r * grow - rimPaint.strokeWidth / 2, rimPaint)
        if (hover > 0.01f) {
            ringPaint.color = accentColor
            ringPaint.alpha = (255 * hover.coerceIn(0f, 1f)).toInt()
            canvas.drawCircle(cx, cy, r * grow + 3 * density, ringPaint)
        }

        // Preview grid: 2×2, or 3×3 for Max folders.
        val n = if (isMax) 3 else 2
        val inner = r * 2 * 0.62f
        val cell = inner / n
        val pad = cell * 0.1f
        val startX = cx - inner / 2
        val startY = cy - inner / 2
        contents.take(n * n).forEachIndexed { i, child ->
            val d: Drawable = child.icon ?: return@forEachIndexed
            val col = i % n
            val row = i / n
            childRect.set(
                (startX + col * cell + pad).toInt(),
                (startY + row * cell + pad).toInt(),
                (startX + (col + 1) * cell - pad).toInt(),
                (startY + (row + 1) * cell - pad).toInt()
            )
            d.bounds = childRect
            d.alpha = 255
            d.colorFilter = null
            d.draw(canvas)
        }
        if (dotCount > 0) {
            dotPaint.color = accentColor
            canvas.drawCircle(cx + r * 0.72f, cy - r * 0.72f, r * 0.11f, dotPaint)
        }
        canvas.restoreToCount(save)

        if (showLabel) {
            val text = item?.title ?: return
            val avail = (width - 4 * density).coerceAtLeast(1f)
            val line = TextUtils.ellipsize(text, textPaint, avail, TextUtils.TruncateAt.END)
            val baseline = iconRect.bottom + gapPx - textPaint.fontMetrics.ascent
            canvas.drawText(line, 0, line.length, width / 2f, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animatePress(0.92f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animatePress(1f)
        }
        return super.onTouchEvent(event)
    }

    private fun animatePress(target: Float) {
        ValueAnimator.ofFloat(pressScale, target).apply {
            duration = 150
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    var textColor: Int
        get() = textPaint.color
        set(value) { textPaint.color = value; invalidate() }
}
