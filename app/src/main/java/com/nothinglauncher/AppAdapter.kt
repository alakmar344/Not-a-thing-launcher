package com.nothinglauncher

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.ItemAppBinding
import com.nothinglauncher.databinding.ItemDrawerHeaderBinding
import kotlin.math.abs

/** Rows shown in the app drawer. */
sealed class DrawerItem {
    data class Header(val id: String, val title: String, val count: String? = null) : DrawerItem()
    data class App(val app: AppInfo, val section: String) : DrawerItem()
}

/** App drawer grid: section headers (full width) + app cells. */
class AppAdapter(
    private val onAppClick: (AppInfo, View) -> Unit,
    private val onAppLongClick: (AppInfo, View) -> Unit,
    private val onAppDragStart: ((AppInfo, View, Float, Float) -> Unit)? = null
) : ListAdapter<DrawerItem, RecyclerView.ViewHolder>(Diff) {

    var iconSizePx: Int = 0
    var accentColor: Int = 0

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is DrawerItem.Header -> TYPE_HEADER
        is DrawerItem.App -> TYPE_APP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemDrawerHeaderBinding.inflate(inflater, parent, false))
        } else {
            AppHolder(ItemAppBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DrawerItem.Header -> (holder as HeaderHolder).bind(item)
            is DrawerItem.App -> (holder as AppHolder).bind(item.app)
        }
    }

    inner class HeaderHolder(private val b: ItemDrawerHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: DrawerItem.Header) {
            b.headerTitle.text = item.title
            b.headerCount.text = item.count.orEmpty()
            if (accentColor != 0) b.headerDot.background.mutate().setTint(accentColor)
        }
    }

    inner class AppHolder(private val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root) {
        private var downRawX = 0f
        private var downRawY = 0f
        private var longPressed = false
        private val slop = ViewConfiguration.get(b.root.context).scaledTouchSlop

        fun bind(app: AppInfo) {
            b.appIcon.setImageDrawable(app.icon)
            b.appIcon.contentDescription = app.label
            if (iconSizePx > 0) b.appIcon.updateLayoutParams { width = iconSizePx; height = iconSizePx }
            b.appLabel.text = app.label

            b.root.setOnClickListener { onAppClick(app, b.appIcon) }

            b.root.setOnTouchListener { _, event ->
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
                            if (dx > slop || dy > slop) {
                                longPressed = false
                                onAppDragStart?.invoke(app, b.appIcon, event.rawX, event.rawY)
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

            b.root.setOnLongClickListener {
                longPressed = true
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onAppLongClick(app, b.appIcon)
                true
            }
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_APP = 1

        private val Diff = object : DiffUtil.ItemCallback<DrawerItem>() {
            override fun areItemsTheSame(oldItem: DrawerItem, newItem: DrawerItem): Boolean = when {
                oldItem is DrawerItem.Header && newItem is DrawerItem.Header -> oldItem.id == newItem.id
                oldItem is DrawerItem.App && newItem is DrawerItem.App ->
                    oldItem.app.key == newItem.app.key && oldItem.section == newItem.section
                else -> false
            }

            override fun areContentsTheSame(oldItem: DrawerItem, newItem: DrawerItem): Boolean =
                oldItem == newItem
        }
    }
}
