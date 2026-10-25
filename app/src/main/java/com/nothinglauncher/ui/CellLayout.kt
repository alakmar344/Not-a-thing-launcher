package com.nothinglauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import com.nothinglauncher.R
import com.nothinglauncher.model.CellRect
import com.nothinglauncher.model.GridOccupancy
import com.nothinglauncher.model.ItemInfo

/**
 * A page of the home screen (or the dock): a fixed grid where every child occupies a
 * rectangle of cells. Supports temporary "reorder" positions while something is dragged over
 * it, animated moves, a dotted drop outline and a dot-grid hint in edit states.
 */
class CellLayout(context: Context, val isHotseat: Boolean = false) : ViewGroup(context) {

    var countX = 4
        private set
    var countY = 6
        private set
    var screenId: Long = -1L

    var cellWidth = 0
        private set
    var cellHeight = 0
        private set

    private val density = resources.displayMetrics.density

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(0.1f, 6f * density), 0f)
    }
    private val outlineFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_tertiary)
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.hairline_strong)
    }
    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface)
        alpha = 70
    }

    var accentColor: Int = ContextCompat.getColor(context, R.color.nothing_red)
    private var outline: CellRect? = null
    private var outlineValid = true
    private var outlineAlpha = 0f
    private var outlineAnim: ValueAnimator? = null

    /** Faint dots on every cell while editing. */
    var showGridHint = false
        set(value) {
            if (field == value) return
            field = value
            animateHint(if (value) 1f else 0f)
        }
    private var hintAlpha = 0f
    private var hintAnim: ValueAnimator? = null

    /** Draw a page card (page overview / edit mode). */
    var overviewMode = false
        set(value) { field = value; invalidate() }
    var isDefaultPage = false
        set(value) { field = value; invalidate() }

    private val pendingMoves = HashMap<View, Pair<Int, Int>>()
    private val tmpRect = Rect()
    private val tmpRectF = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        isMotionEventSplittingEnabled = false
    }

    fun setGridSize(x: Int, y: Int) {
        if (x == countX && y == countY) return
        countX = x.coerceAtLeast(1)
        countY = y.coerceAtLeast(1)
        requestLayout()
    }

    // ---- Layout params ----------------------------------------------------------------------

    class LayoutParams(var cellX: Int, var cellY: Int, var spanX: Int, var spanY: Int) :
        ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT) {
        var tmpCellX = 0
        var tmpCellY = 0
        var useTmpCoords = false
        internal var x = 0
        internal var y = 0

        val curX get() = if (useTmpCoords) tmpCellX else cellX
        val curY get() = if (useTmpCoords) tmpCellY else cellY
        val rect get() = CellRect(cellX, cellY, spanX, spanY)
        val curRect get() = CellRect(curX, curY, spanX, spanY)

        fun set(info: ItemInfo) {
            cellX = info.cellX
            cellY = info.cellY
            spanX = info.spanX
            spanY = info.spanY
            useTmpCoords = false
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(0, 0, 1, 1)
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LayoutParams(0, 0, 1, 1)

    fun addItemView(view: View, info: ItemInfo) {
        val lp = LayoutParams(info.cellX, info.cellY, info.spanX, info.spanY)
        (view.parent as? ViewGroup)?.removeView(view)
        addView(view, lp)
    }

    // ---- Measure & layout -------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        cellWidth = ((w - paddingLeft - paddingRight) / countX).coerceAtLeast(1)
        cellHeight = ((h - paddingTop - paddingBottom) / countY).coerceAtLeast(1)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            child.measure(
                MeasureSpec.makeMeasureSpec(lp.spanX * cellWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lp.spanY * cellHeight, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            lp.x = paddingLeft + lp.curX * cellWidth
            lp.y = paddingTop + lp.curY * cellHeight
            child.layout(lp.x, lp.y, lp.x + lp.spanX * cellWidth, lp.y + lp.spanY * cellHeight)
            pendingMoves.remove(child)?.let { (oldX, oldY) ->
                val dx = (oldX - lp.x).toFloat()
                val dy = (oldY - lp.y).toFloat()
                if (dx != 0f || dy != 0f) {
                    child.translationX = dx
                    child.translationY = dy
                    child.animate().translationX(0f).translationY(0f)
                        .setDuration(MOVE_DURATION).setInterpolator(MOVE_INTERPOLATOR).start()
                }
            }
        }
    }

    /** Moves [child] to new cell coordinates with a smooth glide. */
    fun animateChildTo(child: View, cellX: Int, cellY: Int, temporary: Boolean) {
        val lp = child.layoutParams as? LayoutParams ?: return
        val targetX = cellX
        val targetY = cellY
        if (lp.curX == targetX && lp.curY == targetY && lp.useTmpCoords == temporary) return
        child.animate().cancel()
        if (child.isLaidOut) {
            pendingMoves[child] = (child.left + child.translationX).toInt() to (child.top + child.translationY).toInt()
        }
        child.translationX = 0f
        child.translationY = 0f
        if (temporary) {
            lp.tmpCellX = targetX
            lp.tmpCellY = targetY
            lp.useTmpCoords = true
        } else {
            lp.cellX = targetX
            lp.cellY = targetY
            lp.useTmpCoords = false
        }
        requestLayout()
    }

    // ---- Geometry ---------------------------------------------------------------------------

    fun cellRectToPixels(r: CellRect, out: Rect) {
        out.left = paddingLeft + r.x * cellWidth
        out.top = paddingTop + r.y * cellHeight
        out.right = out.left + r.w * cellWidth
        out.bottom = out.top + r.h * cellHeight
    }

    /** Point (local px) → position in cell units (2.5 = middle of the third column). */
    fun toCellUnitsX(px: Float) = (px - paddingLeft) / cellWidth.coerceAtLeast(1)
    fun toCellUnitsY(py: Float) = (py - paddingTop) / cellHeight.coerceAtLeast(1)

    fun cellAt(px: Float, py: Float): Pair<Int, Int>? {
        val cx = toCellUnitsX(px).toInt()
        val cy = toCellUnitsY(py).toInt()
        if (px < paddingLeft || py < paddingTop || cx !in 0 until countX || cy !in 0 until countY) return null
        return cx to cy
    }

    /** Child currently covering cell (x, y) (temporary positions included). */
    fun childAtCell(x: Int, y: Int, ignore: View? = null): View? {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c === ignore || c.visibility == GONE) continue
            val lp = c.layoutParams as LayoutParams
            if (x >= lp.curX && x < lp.curX + lp.spanX && y >= lp.curY && y < lp.curY + lp.spanY) return c
        }
        return null
    }

    fun childForItem(id: Long): View? {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if ((c as? ItemView)?.item?.id == id) return c
        }
        return null
    }

    fun occupancy(ignore: View? = null, useTemp: Boolean = true): GridOccupancy {
        val g = GridOccupancy(countX, countY)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c === ignore || c.visibility == GONE && c !== ignore) continue
            val lp = c.layoutParams as LayoutParams
            if (useTemp) g.mark(lp.curRect) else g.mark(lp.rect)
        }
        return g
    }

    /** Real (non temporary) rectangles of all children except [ignore]. */
    fun itemRects(ignore: View?): Map<View, CellRect> {
        val out = LinkedHashMap<View, CellRect>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c === ignore) continue
            out[c] = (c.layoutParams as LayoutParams).rect
        }
        return out
    }

    // ---- Reorder ------------------------------------------------------------------------------

    fun applyTempPositions(solution: Map<View, CellRect>) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LayoutParams
            val target = solution[c] ?: continue
            if (target.x != lp.cellX || target.y != lp.cellY) {
                animateChildTo(c, target.x, target.y, temporary = true)
            } else if (lp.useTmpCoords) {
                animateChildTo(c, lp.cellX, lp.cellY, temporary = false)
            }
        }
    }

    fun revertTempPositions() {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LayoutParams
            if (lp.useTmpCoords) animateChildTo(c, lp.cellX, lp.cellY, temporary = false)
        }
    }

    val hasTempPositions: Boolean
        get() = (0 until childCount).any { (getChildAt(it).layoutParams as LayoutParams).useTmpCoords }

    /** Makes temporary positions permanent; returns the children that moved. */
    fun commitTempPositions(): List<View> {
        val moved = ArrayList<View>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LayoutParams
            if (lp.useTmpCoords) {
                lp.cellX = lp.tmpCellX
                lp.cellY = lp.tmpCellY
                lp.useTmpCoords = false
                moved += c
            }
        }
        return moved
    }

    // ---- Drop feedback ----------------------------------------------------------------------

    fun setDropOutline(r: CellRect?, valid: Boolean = true) {
        if (r == outline && valid == outlineValid) return
        val had = outline != null
        if (r != null) {
            outline = r
            outlineValid = valid
        }
        when {
            r != null && !had -> animateOutline(1f)
            r == null && had -> animateOutline(0f) { outline = null }
            else -> invalidate()
        }
    }

    private fun animateOutline(target: Float, end: (() -> Unit)? = null) {
        outlineAnim?.cancel()
        outlineAnim = ValueAnimator.ofFloat(outlineAlpha, target).apply {
            duration = 140
            addUpdateListener { outlineAlpha = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) { end?.invoke(); invalidate() }
                }
            })
            start()
        }
    }

    private fun animateHint(target: Float) {
        hintAnim?.cancel()
        hintAnim = ValueAnimator.ofFloat(hintAlpha, target).apply {
            duration = 200
            addUpdateListener { hintAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (overviewMode) {
            val r = 28f * density
            tmpRectF.set(density * 2, density * 2, width - density * 2, height - density * 2)
            canvas.drawRoundRect(tmpRectF, r, r, cardFill)
            cardPaint.color = if (isDefaultPage) accentColor else ContextCompat.getColor(context, R.color.hairline_strong)
            canvas.drawRoundRect(tmpRectF, r, r, cardPaint)
        }
        if (hintAlpha > 0f && cellWidth > 0) {
            hintPaint.alpha = (110 * hintAlpha).toInt()
            val rad = 1.6f * density
            for (x in 0 until countX) for (y in 0 until countY) {
                canvas.drawCircle(
                    paddingLeft + (x + 0.5f) * cellWidth,
                    paddingTop + (y + 0.5f) * cellHeight,
                    rad, hintPaint
                )
            }
        }
        val o = outline
        if (o != null && outlineAlpha > 0f) {
            cellRectToPixels(o, tmpRect)
            val inset = 4f * density
            tmpRectF.set(tmpRect.left + inset, tmpRect.top + inset, tmpRect.right - inset, tmpRect.bottom - inset)
            val color = if (outlineValid) accentColor else ContextCompat.getColor(context, R.color.text_tertiary)
            outlineFill.color = color
            outlineFill.alpha = (28 * outlineAlpha).toInt()
            outlinePaint.color = color
            outlinePaint.alpha = (255 * outlineAlpha).toInt()
            val radius = minOf(22f * density, tmpRectF.height() / 2f)
            canvas.drawRoundRect(tmpRectF, radius, radius, outlineFill)
            canvas.drawRoundRect(tmpRectF, radius, radius, outlinePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Empty space: let the workspace see the whole gesture (long-press menu, double tap…).
        return false
    }

    override fun shouldDelayChildPressedState() = false

    companion object {
        const val MOVE_DURATION = 220L
        val MOVE_INTERPOLATOR = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    }
}
