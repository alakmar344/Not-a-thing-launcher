package com.nothinglauncher.ui

import android.app.WallpaperManager
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Horizontally paged container of [CellLayout] pages with finger-following scroll, fling,
 * edge resistance and wallpaper parallax. Recognises empty-space gestures (long press,
 * double tap, pinch) and reports them to [listener].
 */
class Workspace @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    interface Listener {
        fun onPageScrolled(position: Float) {}
        fun onPageSettled(page: Int) {}
        fun onEmptyLongPress(x: Float, y: Float) {}
        fun onEmptyDoubleTap() {}
        fun onPinchIn() {}
        fun onPageTapInOverview(page: Int) {}
        fun canInteract(): Boolean = true
    }

    var listener: Listener? = null

    var currentPage = 0
        private set
    private var nextPage = -1

    private val scroller = OverScroller(context, PathInterpolator(0.2f, 0.9f, 0.25f, 1f))
    private var velocityTracker: VelocityTracker? = null
    private val vc = ViewConfiguration.get(context)
    private val touchSlop = vc.scaledPagingTouchSlop
    private val minFling = vc.scaledMinimumFlingVelocity * 3
    private val maxFling = vc.scaledMaximumFlingVelocity
    private val density = resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var isBeingDragged = false
    private var downOnEmpty = false
    private var overview = false

    val pageCount get() = childCount
    fun pageAt(i: Int): CellLayout? = getChildAt(i) as? CellLayout
    val currentCellLayout: CellLayout? get() = pageAt(currentPage)
    val isScrolling get() = isBeingDragged || !scroller.isFinished
    var isInOverview: Boolean
        get() = overview
        set(value) {
            overview = value
            for (i in 0 until childCount) pageAt(i)?.overviewMode = value
        }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onLongPress(e: MotionEvent) {
            if (!downOnEmpty || isBeingDragged || overview || listener?.canInteract() == false) return
            listener?.onEmptyLongPress(e.rawX, e.rawY)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!downOnEmpty || isBeingDragged || overview || listener?.canInteract() == false) return false
            listener?.onEmptyDoubleTap()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (overview) {
                listener?.onPageTapInOverview(currentPage)
                return true
            }
            return false
        }
    }).apply { setIsLongpressEnabled(true) }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var total = 1f
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            total = 1f
            return !overview && listener?.canInteract() != false
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            total *= detector.scaleFactor
            if (total < 0.8f) {
                total = 1f
                listener?.onPinchIn()
                return false
            }
            return true
        }
    })

    init {
        isMotionEventSplittingEnabled = false
        clipChildren = false
        clipToPadding = false
    }

    // ---- Measure & layout -------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val childW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val childH = MeasureSpec.makeMeasureSpec((h - paddingTop - paddingBottom).coerceAtLeast(0), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(childW, childH)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.layout(i * w, paddingTop, (i + 1) * w, paddingTop + c.measuredHeight)
        }
        if (changed || !isBeingDragged && scroller.isFinished) {
            currentPage = currentPage.coerceIn(0, max(0, childCount - 1))
            scrollTo(currentPage * w, 0)
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val w = width.coerceAtLeast(1)
        val pos = l.toFloat() / w
        listener?.onPageScrolled(pos)
        updateWallpaperOffset(pos)
    }

    private fun updateWallpaperOffset(pos: Float) {
        val token = windowToken ?: return
        val pages = childCount
        try {
            val wm = WallpaperManager.getInstance(context)
            val step = if (pages > 1) 1f / (pages - 1) else 0f
            wm.setWallpaperOffsetSteps(step, 0f)
            wm.setWallpaperOffsets(token, if (pages > 1) (pos / (pages - 1)).coerceIn(0f, 1f) else 0.5f, 0.5f)
        } catch (e: Exception) {
            // Wallpaper service unavailable; parallax is cosmetic.
        }
    }

    // ---- Paging -----------------------------------------------------------------------------

    fun setCurrentPageImmediately(page: Int) {
        scroller.forceFinished(true)
        currentPage = page.coerceIn(0, max(0, childCount - 1))
        nextPage = -1
        scrollTo(currentPage * width, 0)
        listener?.onPageSettled(currentPage)
    }

    fun snapToPage(page: Int, duration: Int = -1) {
        val target = page.coerceIn(0, max(0, childCount - 1))
        val dx = target * width - scrollX
        nextPage = target
        scroller.forceFinished(true)
        val d = if (duration >= 0) duration else (260 + min(abs(dx) / width.coerceAtLeast(1), 3) * 80)
        scroller.startScroll(scrollX, 0, dx, 0, d)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        } else if (nextPage != -1) {
            currentPage = nextPage
            nextPage = -1
            listener?.onPageSettled(currentPage)
        }
    }

    // ---- Touch --------------------------------------------------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downOnEmpty = isEmptyAt(ev.x, ev.y)
        }
        if (ev.pointerCount > 1 || scaleDetector.isInProgress) scaleDetector.onTouchEvent(ev)
        else if (ev.actionMasked == MotionEvent.ACTION_DOWN) scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun isEmptyAt(x: Float, y: Float): Boolean {
        if (overview) return true
        val page = currentCellLayout ?: return true
        val lx = x + scrollX - page.left
        val ly = y - page.top
        for (i in 0 until page.childCount) {
            val c = page.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (lx >= c.left && lx < c.right && ly >= c.top && ly < c.bottom) return false
        }
        return true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (overview && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Pages are previews in overview: the workspace handles every touch.
            beginTracking(ev)
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginTracking(ev)
                if (!scroller.isFinished && abs(scroller.finalX - scroller.currX) > touchSlop) {
                    // Catch a settling page.
                    scroller.forceFinished(true)
                    nextPage = -1
                    isBeingDragged = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!isBeingDragged && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.1f && childCount > 0) {
                    startDragging(ev)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isBeingDragged) recycleTracker()
            }
        }
        return isBeingDragged
    }

    private fun beginTracking(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        lastX = ev.x
        isBeingDragged = false
        recycleTracker()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    private fun startDragging(ev: MotionEvent) {
        isBeingDragged = true
        lastX = ev.x
        parent?.requestDisallowInterceptTouchEvent(true)
        cancelChildLongPresses()
    }

    private fun cancelChildLongPresses() {
        for (i in 0 until childCount) {
            val page = getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until page.childCount) page.getChildAt(j).cancelLongPress()
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!overview) beginTracking(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isBeingDragged) {
                    val dx = ev.x - downX
                    if (abs(dx) > touchSlop && abs(dx) > abs(ev.y - downY)) startDragging(ev)
                }
                if (isBeingDragged) {
                    val delta = lastX - ev.x
                    lastX = ev.x
                    val maxScroll = (childCount - 1).coerceAtLeast(0) * width
                    var target = scrollX + delta
                    // Rubber band at both ends.
                    if (target < 0 || target > maxScroll) target = scrollX + delta * 0.35f
                    scrollTo(target.roundToInt().coerceIn(-width / 3, maxScroll + width / 3), 0)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isBeingDragged) {
                    val vt = velocityTracker
                    vt?.computeCurrentVelocity(1000, maxFling.toFloat())
                    settle(vt?.xVelocity ?: 0f)
                }
                isBeingDragged = false
                recycleTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isBeingDragged) settle(0f)
                isBeingDragged = false
                recycleTracker()
            }
        }
        return true
    }

    private fun settle(velocityX: Float) {
        val w = width.coerceAtLeast(1)
        val pos = scrollX.toFloat() / w
        val last = max(0, childCount - 1)
        val target = when {
            velocityX < -minFling -> ceil(pos).toInt()
            velocityX > minFling -> floor(pos).toInt()
            else -> pos.roundToInt()
        }.coerceIn((currentPage - 1).coerceAtLeast(0), (currentPage + 1).coerceAtMost(last))
        val speedFactor = (abs(velocityX) / 8000f).coerceIn(0f, 0.6f)
        val dist = abs(target * w - scrollX).toFloat() / w
        snapToPage(target, ((180 + 220 * dist) * (1f - speedFactor)).toInt().coerceAtLeast(120))
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun shouldDelayChildPressedState() = true
}
