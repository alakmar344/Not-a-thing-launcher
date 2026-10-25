package com.nothinglauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.nothinglauncher.AppInfo
import com.nothinglauncher.R
import com.nothinglauncher.model.ItemInfo
import kotlin.math.min

/**
 * The launcher's icon: rendered icon, single-line label, optional notification dot and
 * shortcut badge — all drawn directly for speed (hundreds of these live in the drawer).
 * Used on pages, in the dock, inside folders and in the app drawer.
 */
open class IconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), ItemView {

    override var item: ItemInfo? = null
    var app: AppInfo? = null

    var icon: Drawable? = null
        set(value) { field = value; invalidate() }
    var label: CharSequence? = null
        set(value) {
            field = value
            contentDescription = value
            ellipsized = null
            invalidate()
        }
    var badge: Drawable? = null
        set(value) { field = value; invalidate() }

    var iconSizePx: Int = (54 * resources.displayMetrics.density).toInt()
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    var showLabel = true
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    var labelShadow = false
        set(value) {
            field = value
            if (value) textPaint.setShadowLayer(4f * density, 0f, 1f * density, 0xB0000000.toInt())
            else textPaint.clearShadowLayer()
            invalidate()
        }
    var textColor: Int
        get() = textPaint.color
        set(value) { textPaint.color = value; invalidate() }
    var textSizePx: Float
        get() = textPaint.textSize
        set(value) { textPaint.textSize = value; ellipsized = null; requestLayout() }

    /** Number of notifications; >0 draws the accent dot. */
    var dotCount = 0
        set(value) {
            if (field == value) return
            field = value
            updateDescription()
            invalidate()
        }
    var dotColor: Int
        get() = dotPaint.color
        set(value) { dotPaint.color = value; invalidate() }

    var isDisabled = false
        set(value) { field = value; invalidate() }

    /** Folder-creation feedback when another icon hovers over this one. */
    var folderHover = 0f
        set(value) { field = value; invalidate() }
    var hoverRingColor: Int = ContextCompat.getColor(context, R.color.nothing_red)

    protected val density = resources.displayMetrics.density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = try { ResourcesCompat.getFont(context, R.font.space_grotesk) } catch (e: Exception) { null }
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.nothing_red) }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nothing_bg)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disabledFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    private var ellipsized: CharSequence? = null
    private val iconRect = Rect()
    private val tmpRectF = RectF()
    private var pressAnim: ValueAnimator? = null
    private var pressScale = 1f
    var gapPx = (6 * density).toInt()

    /** When true the icon fills a 2×2 "Max" cell area. */
    var isMax = false
        set(value) { field = value; requestLayout(); invalidate() }

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
        isHapticFeedbackEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bindApp(a: AppInfo) {
        app = a
        icon = a.icon
        label = a.label
    }

    fun bindItem(info: ItemInfo) {
        item = info
        app = info.app
        icon = info.icon
        label = info.label ?: info.title
        isDisabled = info.disabled
    }

    private fun updateDescription() {
        contentDescription = if (dotCount > 0) {
            context.getString(R.string.a11y_icon_with_notifications, label ?: "")
        } else label
    }

    private fun effectiveIconSize(): Int {
        if (!isMax) return iconSizePx
        val labelH = if (showLabel) labelHeight() + gapPx else 0
        val avail = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom - labelH)
        return (avail * 0.86f).toInt().coerceAtLeast(iconSizePx)
    }

    private fun labelHeight(): Int {
        val fm = textPaint.fontMetrics
        return (fm.descent - fm.ascent).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val contentH = paddingTop + paddingBottom + iconSizePx + if (showLabel) gapPx + labelHeight() else 0
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(contentH, MeasureSpec.getSize(heightMeasureSpec))
            else -> contentH
        }
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            iconSizePx + paddingLeft + paddingRight + (16 * density).toInt()
        } else w
        setMeasuredDimension(width, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ellipsized = null
    }

    /** Bounds of the icon inside this view (for drag images and animations). */
    fun getIconBounds(out: Rect) {
        val size = effectiveIconSize()
        val labelH = if (showLabel) gapPx + labelHeight() else 0
        val contentH = size + labelH
        val top = paddingTop + ((height - paddingTop - paddingBottom - contentH) / 2).coerceAtLeast(0)
        val left = (width - size) / 2
        out.set(left, top, left + size, top + size)
    }

    override fun onDraw(canvas: Canvas) {
        getIconBounds(iconRect)
        val cx = iconRect.exactCenterX()
        val cy = iconRect.exactCenterY()
        if (folderHover > 0f) {
            hoverPaint.color = hoverRingColor
            hoverPaint.alpha = (60 * folderHover).toInt()
            val r = iconRect.width() / 2f * (1f + 0.22f * folderHover)
            canvas.drawCircle(cx, cy, r, hoverPaint)
        }
        val save = canvas.save()
        val s = pressScale * (1f - 0.08f * folderHover)
        canvas.scale(s, s, cx, cy)
        icon?.let { d ->
            d.bounds = iconRect
            if (isDisabled) {
                d.colorFilter = disabledFilter
                d.alpha = 110
            } else {
                d.colorFilter = null
                d.alpha = 255
            }
            d.draw(canvas)
        }
        badge?.let { b ->
            val bs = (iconRect.width() * 0.38f).toInt()
            b.setBounds(iconRect.right - bs, iconRect.bottom - bs, iconRect.right, iconRect.bottom)
            b.draw(canvas)
        }
        if (dotCount > 0 && !isDisabled) {
            val r = iconRect.width() * 0.105f
            val dx = cx + iconRect.width() * 0.36f
            val dy = cy - iconRect.height() * 0.36f
            canvas.drawCircle(dx, dy, r + dotRingPaint.strokeWidth / 2f, dotRingPaint)
            canvas.drawCircle(dx, dy, r, dotPaint)
        }
        canvas.restoreToCount(save)

        if (showLabel) {
            val text = label ?: return
            val avail = (width - paddingLeft - paddingRight - 4 * density).coerceAtLeast(1f)
            val line = ellipsized ?: TextUtils.ellipsize(text, textPaint, avail, TextUtils.TruncateAt.END).also { ellipsized = it }
            val baseline = iconRect.bottom + gapPx - textPaint.fontMetrics.ascent
            textPaint.alpha = if (isDisabled) 130 else 255
            canvas.drawText(line, 0, line.length, width / 2f, baseline, textPaint)
        }
    }

    // ---- Press feedback -------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animatePress(0.9f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animatePress(1f)
        }
        return super.onTouchEvent(event)
    }

    private fun animatePress(target: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(pressScale, target).apply {
            duration = if (target < 1f) 120 else 220
            interpolator = PRESS_INTERPOLATOR
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        animatePress(1f)
    }

    fun resetPressState() {
        pressAnim?.cancel()
        pressScale = 1f
        invalidate()
    }

    companion object {
        private val PRESS_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}

/** A view on the home screen that represents an [ItemInfo]. */
interface ItemView {
    val item: ItemInfo?
}
