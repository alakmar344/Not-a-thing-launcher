package com.nothinglauncher

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.ItemAppBinding

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
            binding.root.isFocusable = true
            binding.appIcon.setImageDrawable(app.icon)
            binding.appIcon.contentDescription = app.label
            binding.appLabel.text = app.label
            binding.appLabel.isVisible = showLabels
            applyLabelShadow(binding, labelShadow)
            LongPressDragHelper.attach(
                binding.root,
                onClick = { onAppClick(app, binding.appIcon) },
                onLongPress = { onAppLongClick(app, binding.appIcon) },
                onDragStart = { _, rawX, rawY -> onAppDragStart?.invoke(app, binding.root, rawX, rawY) }
            )
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
