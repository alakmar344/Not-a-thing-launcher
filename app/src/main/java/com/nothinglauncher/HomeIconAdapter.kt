package com.nothinglauncher

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.ItemHomeIconBinding

class HomeIconAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo, Int) -> Boolean
) : RecyclerView.Adapter<HomeIconAdapter.IconViewHolder>() {

    private val items = mutableListOf<AppInfo>()

    fun setItems(newItems: List<AppInfo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<AppInfo> = items.toList()

    fun moveItem(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun mergeItems(draggedPos: Int, targetPos: Int): Pair<AppInfo, AppInfo>? {
        if (draggedPos < 0 || targetPos < 0 || draggedPos >= items.size || targetPos >= items.size) return null
        val dragged = items[draggedPos]
        val target = items[targetPos]
        items.removeAt(draggedPos)
        notifyItemRemoved(draggedPos)
        return Pair(dragged, target)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconViewHolder {
        val binding = ItemHomeIconBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return IconViewHolder(binding)
    }

    override fun onBindViewHolder(holder: IconViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class IconViewHolder(private val binding: ItemHomeIconBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(appInfo: AppInfo) {
            binding.appIcon.setImageDrawable(appInfo.icon)
            binding.appLabel.text = appInfo.label
            binding.root.setOnClickListener {
                onAppClick(appInfo)
            }
            binding.root.setOnLongClickListener {
                onAppLongClick(appInfo, bindingAdapterPosition)
            }
        }
    }
}
