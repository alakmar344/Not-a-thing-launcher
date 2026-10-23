package com.nothinglauncher

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.ItemAppBinding

/** Pinned apps on the home screen. Supports drag-to-reorder via ItemTouchHelper. */
class HomeIconAdapter(
    private val onAppClick: (AppInfo, View) -> Unit,
    private val onAppLongClick: (AppInfo, View) -> Unit
) : RecyclerView.Adapter<HomeIconAdapter.IconViewHolder>() {

    private val items = mutableListOf<AppInfo>()

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
    fun setItems(newItems: List<AppInfo>) {
        if (newItems == items) return
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<AppInfo> = items.toList()

    fun moveItem(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconViewHolder =
        IconViewHolder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: IconViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class IconViewHolder(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(app: AppInfo) {
            binding.appIcon.setImageDrawable(app.icon)
            binding.appIcon.contentDescription = app.label
            if (iconSizePx > 0) binding.appIcon.updateLayoutParams { width = iconSizePx; height = iconSizePx }
            binding.appLabel.text = app.label
            binding.appLabel.isVisible = showLabels
            applyLabelShadow(binding, labelShadow)
            binding.root.setOnClickListener { onAppClick(app, binding.appIcon) }
            binding.root.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onAppLongClick(app, binding.appIcon)
                true
            }
        }
    }

    companion object {
        /** Wallpaper backgrounds can be bright, so labels get a soft shadow there. */
        fun applyLabelShadow(binding: ItemAppBinding, enabled: Boolean) {
            if (enabled) binding.appLabel.setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt())
            else binding.appLabel.setShadowLayer(0f, 0f, 0f, 0)
        }
    }
}
