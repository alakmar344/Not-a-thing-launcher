package com.nothinglauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat

/**
 * Native Android Launcher Grid Layout.
 * Arranges workspace items (apps, folders, 2x2 max tiles, bespoke widgets, AppWidgets)
 * on a discrete 2D grid matrix of (cellX, cellY, spanX, spanY) cells.
 *
 * Provides stock-launcher drop preview visualization (folder merge pulse, target slot indicator)
 * and coordinate translation between touch events and grid cells.
 */
class CellLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    var countX: Int = 4
        set(value) {
            val v = value.coerceAtLeast(1)
            if (field != v) {
                field = v
                requestLayout()
            }
        }

    var countY: Int = 5
        private set

    var cellGapX: Int = (6 * resources.displayMetrics.density).toInt()
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    var cellGapY: Int = (8 * resources.displayMetrics.density).toInt()
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    var cellWidth: Int = 0
        private set

    var cellHeight: Int = 0
        private set

    // Drop target state for visual preview
    private var dropTargetCellX: Int = -1
    private var dropTargetCellY: Int = -1
    private var dropTargetSpanX: Int = 1
    private var dropTargetSpanY: Int = 1
    private var isDropFolderMerge: Boolean = false

    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val dropFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val folderMergePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private val folderMergeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val targetRect = RectF()
    private var pulseAlpha = 0.8f
    private var pulseAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        val accent = ContextCompat.getColor(context, R.color.nothing_red)
        folderMergePaint.color = accent
        folderMergeFillPaint.color = Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent))
        dropPaint.color = Color.argb(180, 255, 255, 255)
        dropFillPaint.color = Color.argb(30, 255, 255, 255)

        pulseAnimator = ValueAnimator.ofFloat(0.4f, 1f).apply {
            duration = 550
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulseAlpha = it.animatedValue as Float
                if (dropTargetCellX >= 0) invalidate()
            }
        }
    }

    fun setAccentColor(color: Int) {
        folderMergePaint.color = color
        folderMergeFillPaint.color = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
        invalidate()
    }

    fun setDropTarget(cellX: Int, cellY: Int, spanX: Int = 1, spanY: Int = 1, isFolderMerge: Boolean = false) {
        if (dropTargetCellX == cellX && dropTargetCellY == cellY &&
            dropTargetSpanX == spanX && dropTargetSpanY == spanY &&
            isDropFolderMerge == isFolderMerge
        ) {
            return
        }
        dropTargetCellX = cellX
        dropTargetCellY = cellY
        dropTargetSpanX = spanX
        dropTargetSpanY = spanY
        isDropFolderMerge = isFolderMerge

        if (isFolderMerge) {
            if (pulseAnimator?.isRunning != true) pulseAnimator?.start()
        } else {
            pulseAnimator?.cancel()
            pulseAlpha = 1f
        }
        invalidate()
    }

    fun clearDropTarget() {
        if (dropTargetCellX != -1 || dropTargetCellY != -1) {
            dropTargetCellX = -1
            dropTargetCellY = -1
            pulseAnimator?.cancel()
            invalidate()
        }
    }

    /**
     * Called (posted, once per measure pass) when a [LayoutParams.fitContent] child needs more
     * rows than it was given, so the owner can grow its span instead of clipping it.
     */
    var onChildNeedsRows: ((child: View, rows: Int) -> Unit)? = null
    private var pendingRowRequests = mutableMapOf<View, Int>()

    /**
     * Grid cell for an item of span ([spanX], [spanY]) whose dragged preview is centred on
     * screen point ([rawCenterX], [rawCenterY]) - the same "centre of the drag view" rule
     * Launcher3 uses, so wide items land where they visually hover.
     */
    fun cellForDragCenter(rawCenterX: Float, rawCenterY: Float, spanX: Int, spanY: Int): Pair<Int, Int> {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val stepX = (cellWidth + cellGapX).coerceAtLeast(1).toFloat()
        val stepY = (cellHeight + cellGapY).coerceAtLeast(1).toFloat()
        val sx = spanX.coerceIn(1, countX)
        val localX = rawCenterX - loc[0] - paddingStart - (sx * cellWidth + (sx - 1) * cellGapX) / 2f
        val localY = rawCenterY - loc[1] - paddingTop - (spanY * cellHeight + (spanY - 1) * cellGapY) / 2f
        val col = Math.round(localX / stepX).coerceIn(0, countX - sx)
        val row = Math.round(localY / stepY).coerceAtLeast(0)
        return col to row
    }

    /** True when the screen point lies inside this grid's visible bounds. */
    fun containsRawPoint(rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + width && rawY >= loc[1] && rawY <= loc[1] + height
    }

    /** Translates window (rawX, rawY) into grid (cellX, cellY). Returns null if outside bounds. */
    fun pointToCell(rawX: Float, rawY: Float): Pair<Int, Int>? {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val localX = rawX - loc[0] - paddingStart
        val localY = rawY - loc[1] - paddingTop

        if (localX < 0 || localY < 0) return null
        val stepX = (cellWidth + cellGapX).coerceAtLeast(1)
        val stepY = (cellHeight + cellGapY).coerceAtLeast(1)

        val col = (localX / stepX).toInt().coerceIn(0, countX - 1)
        val row = (localY / stepY).toInt().coerceAtLeast(0)
        return Pair(col, row)
    }

    fun findChildAtCell(cellX: Int, cellY: Int): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as? LayoutParams ?: continue
            if (cellX in lp.cellX until (lp.cellX + lp.spanX) &&
                cellY in lp.cellY until (lp.cellY + lp.spanY)
            ) {
                return child
            }
        }
        return null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val availWidth = (width - paddingStart - paddingEnd).coerceAtLeast(0)
        cellWidth = ((availWidth - (countX - 1) * cellGapX) / countX).coerceAtLeast(0)

        // Native proportion: height is 1.18x width to comfortably fit icon and caption.
        cellHeight = (cellWidth * 1.18f).toInt().coerceAtLeast((72 * resources.displayMetrics.density).toInt())

        var maxRow = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as? LayoutParams ?: continue
            val childSpanX = lp.spanX.coerceIn(1, countX)
            val childSpanY = lp.spanY.coerceAtLeast(1)

            val childW = childSpanX * cellWidth + (childSpanX - 1) * cellGapX
            val childH = childSpanY * cellHeight + (childSpanY - 1) * cellGapY

            if (lp.fitContent && onChildNeedsRows != null && cellHeight > 0) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(childW.coerceAtLeast(0), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                val natural = child.measuredHeight
                if (natural > childH + 1) {
                    val rows = ((natural + cellGapY + cellHeight - 1) / (cellHeight + cellGapY)).coerceAtLeast(childSpanY + 1)
                    if (pendingRowRequests[child] != rows) {
                        pendingRowRequests[child] = rows
                        post {
                            if (pendingRowRequests.remove(child) != null && child.parent === this) {
                                onChildNeedsRows?.invoke(child, rows)
                            }
                        }
                    }
                }
            }
            child.measure(
                MeasureSpec.makeMeasureSpec(childW.coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childH.coerceAtLeast(0), MeasureSpec.EXACTLY)
            )

            val endRow = lp.cellY + childSpanY
            if (endRow > maxRow) maxRow = endRow
        }

        countY = maxOf(maxRow, 5)
        val totalHeight = paddingTop + paddingBottom + countY * cellHeight + (countY - 1) * cellGapY
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as? LayoutParams ?: continue
            val childLeft = paddingStart + lp.cellX * (cellWidth + cellGapX)
            val childTop = paddingTop + lp.cellY * (cellHeight + cellGapY)
            child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        if (dropTargetCellX >= 0 && dropTargetCellY >= 0) {
            val left = paddingStart + dropTargetCellX * (cellWidth + cellGapX).toFloat()
            val top = paddingTop + dropTargetCellY * (cellHeight + cellGapY).toFloat()
            val right = left + dropTargetSpanX * cellWidth + (dropTargetSpanX - 1) * cellGapX
            val bottom = top + dropTargetSpanY * cellHeight + (dropTargetSpanY - 1) * cellGapY
            targetRect.set(left, top, right, bottom)

            val cornerRadius = 18f * resources.displayMetrics.density
            if (isDropFolderMerge) {
                folderMergePaint.alpha = (pulseAlpha * 255).toInt()
                folderMergeFillPaint.alpha = (pulseAlpha * 65).toInt()
                canvas.drawRoundRect(targetRect, cornerRadius, cornerRadius, folderMergeFillPaint)
                canvas.drawRoundRect(targetRect, cornerRadius, cornerRadius, folderMergePaint)
            } else {
                canvas.drawRoundRect(targetRect, cornerRadius, cornerRadius, dropFillPaint)
                canvas.drawRoundRect(targetRect, cornerRadius, cornerRadius, dropPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        pendingRowRequests.clear()
        super.onDetachedFromWindow()
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams =
        when (p) {
            is LayoutParams -> LayoutParams(p)
            is MarginLayoutParams -> LayoutParams(p)
            else -> LayoutParams(p)
        }

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
        LayoutParams(0, 0, 1, 1)

    class LayoutParams : ViewGroup.MarginLayoutParams {
        var cellX: Int = 0
        var cellY: Int = 0
        var spanX: Int = 1
        var spanY: Int = 1

        /** Measure the child at its natural height and report when it needs more rows. */
        var fitContent: Boolean = false

        constructor(cellX: Int, cellY: Int, spanX: Int = 1, spanY: Int = 1) : super(
            MATCH_PARENT,
            MATCH_PARENT
        ) {
            this.cellX = cellX
            this.cellY = cellY
            this.spanX = spanX
            this.spanY = spanY
        }

        constructor(c: Context, attrs: AttributeSet?) : super(c, attrs)

        constructor(width: Int, height: Int) : super(width, height)

        constructor(source: ViewGroup.LayoutParams?) : super(source)

        constructor(source: LayoutParams) : super(source) {
            this.cellX = source.cellX
            this.cellY = source.cellY
            this.spanX = source.spanX
            this.spanY = source.spanY
            this.fitContent = source.fitContent
        }
    }
}
