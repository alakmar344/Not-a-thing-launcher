package com.nothinglauncher

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.ItemAppBinding
import kotlin.math.abs

/**
 * Pinned apps on the home screen.
 *
 * A long press arms the gesture; moving the finger then starts a launcher-wide drag,
 * while releasing without moving opens the app menu. This mirrors the stock launcher
 * and avoids a focusable popup stealing the drag stream mid-gesture.
 */
class HomeIconAdapter(
    private val onAppClick: (AppInfo, View) -> Unit,
    private val onAppLongClick: (AppInfo, View) -> Unit,
    private val onAppDragStart: ((AppInfo, View, Float, Float) -> Unit)? = null
) : RecyclerView.Adapter<HomeIconAdapter.IconViewHolder>() {

    private val items = mutableListOf<AppInfo?>()

    var showLabels: Boolean = true
        @SuppressLint("NotifyDataSetChanged")
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    var iconSizePx: Int = 0
        @SuppressLint("NotifyDataSetChanged")
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    var labelShadow: Boolean = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    @SuppressLint("NotifyDataSetChanged")
    fun setItems(newItems: List<AppInfo?>) {
        if (newItems == items) return
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getSlotItems(): List<AppInfo?> = items.toList()
    fun getItems(): List<AppInfo> = items.filterNotNull()
    fun getItem(position: Int): AppInfo? = items.getOrNull(position)

    fun moveItem(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        val fromItem = items[from]
        val toItem = items[to]
        items[from] = toItem
        items[to] = fromItem
        notifyItemMoved(from, to)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconViewHolder =
        IconViewHolder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: IconViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class IconViewHolder(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        private var downX = 0f
        private var downY = 0f
        private var downRawX = 0f
        private var downRawY = 0f
        private var longPressed = false
        private val slop = ViewConfiguration.get(binding.root.context).scaledTouchSlop
        private val longPressRunnable = Runnable {
            if (!binding.root.isAttachedToWindow) return@Runnable
            longPressed = true
            binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        fun bind(app: AppInfo?) {
            binding.root.removeCallbacks(longPressRunnable)
            longPressed = false
            binding.root.isPressed = false
            if (iconSizePx > 0) {
                binding.appIcon.updateLayoutParams { width = iconSizePx; height = iconSizePx }
            }

            if (app == null) {
                binding.root.alpha = 0f
                binding.root.isClickable = false
                binding.root.isFocusable = false
                binding.root.setOnClickListener(null)
                binding.root.setOnTouchListener(null)
                binding.root.setOnLongClickListener(null)
                binding.appIcon.setImageDrawable(null)
                binding.appIcon.contentDescription = null
                binding.appLabel.text = ""
                binding.appLabel.isVisible = false
                return
            }

            binding.root.alpha = 1f
            binding.root.isClickable = true
            binding.root.isFocusable = true
            binding.appIcon.setImageDrawable(app.icon)
            binding.appIcon.contentDescription = app.label
            binding.appLabel.text = app.label
            binding.appLabel.isVisible = showLabels
            applyLabelShadow(binding, labelShadow)

            binding.root.setOnClickListener { onAppClick(app, binding.appIcon) }

            binding.root.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        longPressed = false
                        view.isPressed = true
                        view.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!longPressed) {
                            if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                                view.removeCallbacks(longPressRunnable)
                            }
                        } else {
                            val dx = abs(event.rawX - downRawX)
                            val dy = abs(event.rawY - downRawY)
                            if (dx > slop * 1.5f || dy > slop * 1.5f) {
                                view.removeCallbacks(longPressRunnable)
                                longPressed = false
                                view.isPressed = false
                                onAppDragStart?.invoke(app, binding.appIcon, event.rawX, event.rawY)
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        view.removeCallbacks(longPressRunnable)
                        view.isPressed = false
                        if (longPressed) {
                            longPressed = false
                            onAppLongClick(app, binding.appIcon)
                        } else {
                            view.performClick()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        view.removeCallbacks(longPressRunnable)
                        view.isPressed = false
                        longPressed = false
                    }
                }
                true
            }

            // Kept for accessibility services (TalkBack long-click). The touch listener above
            // consumes normal gestures, so this never fires twice during a real touch.
            binding.root.setOnLongClickListener {
                onAppLongClick(app, binding.appIcon)
                true
            }
        }
    }

    companion object {
        fun applyLabelShadow(binding: ItemAppBinding, enabled: Boolean) {
            if (enabled) {
                binding.appLabel.setTextColor(android.graphics.Color.WHITE)
                binding.appLabel.setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt())
            }
            else {
                binding.appLabel.setTextColor(androidx.core.content.ContextCompat.getColor(binding.root.context, R.color.text_primary))
                binding.appLabel.setShadowLayer(0f, 0f, 0f, 0)
            }
        }
    }
}
