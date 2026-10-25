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

/** Pinned apps on the home screen. Supports drag-to-reorder via ItemTouchHelper and full launcher dragging across grid slots. */
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
        private var downRawX = 0f
        private var downRawY = 0f
        private var longPressed = false
        private val slop = ViewConfiguration.get(binding.root.context).scaledTouchSlop

        fun bind(app: AppInfo?) {
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

            binding.root.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        longPressed = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (longPressed) {
                            val dx = abs(event.rawX - downRawX)
                            val dy = abs(event.rawY - downRawY)
                            if (dx > slop * 1.5f || dy > slop * 1.5f) {
                                longPressed = false
                                onAppDragStart?.invoke(app, binding.appIcon, event.rawX, event.rawY)
                                return@setOnTouchListener true
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressed = false
                    }
                }
                false
            }

            binding.root.setOnLongClickListener {
                longPressed = true
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
