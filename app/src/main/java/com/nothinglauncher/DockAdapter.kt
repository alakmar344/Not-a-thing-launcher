package com.nothinglauncher

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.ItemAppBinding

class DockAdapter(
    private val onAppClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, DockAdapter.DockViewHolder>(DockDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DockViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DockViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DockViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DockViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(appInfo: AppInfo) {
            binding.appIcon.setImageDrawable(appInfo.icon)
            binding.appLabel.text = ""
            binding.root.setOnClickListener {
                onAppClick(appInfo)
            }
        }
    }

    class DockDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
            oldItem.label == newItem.label
    }
}
