package com.nothinglauncher.drawer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.allapps.AllAppsGridAdapter
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.allapps.search.DefaultSearchAdapterProvider
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.views.ActivityContext
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.IconFactory
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Tactile
import com.nothinglauncher.icons.NothingIconLogic

/**
 * Renders full-width [CommandEngine] cards and Minimal List app rows inside Launcher3's
 * stock [AllAppsGridAdapter] without modifying AOSP Launcher3.
 */
class NothingSearchAdapterProvider(
    launcher: ActivityContext,
    private val itemLookup: (position: Int, inSearch: Boolean) -> BaseAllAppsAdapter.AdapterItem?
) : DefaultSearchAdapterProvider(launcher) {

    private var highlighted: View? = null

    override fun isViewSupported(viewType: Int): Boolean =
        viewType == VIEW_TYPE_COMMAND || viewType == VIEW_TYPE_LIST_APP

    override fun getSupportedItemsPerRowArray(): IntArray = intArrayOf(1)

    override fun getItemsPerRow(viewType: Int, appsPerRow: Int): Int =
        if (isViewSupported(viewType)) 1 else appsPerRow

    override fun onCreateViewHolder(
        layoutInflater: LayoutInflater,
        parent: ViewGroup,
        viewType: Int
    ): BaseAllAppsAdapter.ViewHolder? = when (viewType) {
        VIEW_TYPE_COMMAND -> {
            val v = layoutInflater.inflate(R.layout.item_search_command, parent, false)
            Tactile.press(v, 0.98f)
            BaseAllAppsAdapter.ViewHolder(v)
        }
        VIEW_TYPE_LIST_APP -> {
            val v = layoutInflater.inflate(R.layout.item_drawer_list_app, parent, false)
            Tactile.press(v, 0.985f)
            BaseAllAppsAdapter.ViewHolder(v)
        }
        else -> null
    }

    override fun onBindView(holder: BaseAllAppsAdapter.ViewHolder, position: Int) {
        if (position == 0) highlighted = holder.itemView
        when (holder.itemViewType) {
            VIEW_TYPE_COMMAND -> bindCommand(holder.itemView, position)
            VIEW_TYPE_LIST_APP -> bindListApp(holder.itemView, position)
            else -> super.onBindView(holder, position)
        }
    }

    private fun bindCommand(view: View, position: Int) {
        val item = itemLookup(position, true) as? NothingSearchAlgorithm.CommandAdapterItem ?: return
        val cmd = item.command
        val ctx = view.context
        val badge = view.findViewById<TextView>(R.id.cmdBadge)
        badge.text = cmd.badge
        val isAccent = cmd.kind == com.nothinglauncher.search.CommandEngine.Kind.ANSWER
        badge.setBackgroundResource(if (isAccent) R.drawable.bg_pill_white else R.drawable.bg_pill_outline)
        badge.setTextColor(
            if (isAccent) ContextCompat.getColor(ctx, R.color.pill_active_text)
            else AccentTheme.accentColor(ctx)
        )
        view.findViewById<TextView>(R.id.cmdTitle).text = cmd.title
        view.findViewById<TextView>(R.id.cmdSubtitle).text = cmd.subtitle
        view.tag = cmd
        view.setOnClickListener {
            Tactile.confirm(it)
            cmd.action(ctx)
        }
    }

    private fun bindListApp(view: View, position: Int) {
        val item = itemLookup(position, false) ?: return
        val info: AppInfo = item.itemInfo ?: return
        val ctx = view.context
        val title = view.findViewById<TextView>(R.id.listAppTitle)
        val iconView = view.findViewById<ImageView>(R.id.listAppIcon)
        val tag = view.findViewById<TextView>(R.id.listAppTag)

        title.text = info.title
        tag.text = info.sectionName
        val size = (34 * ctx.resources.displayMetrics.density).toInt()
        val bmp = info.bitmap.icon
        if (bmp != null) {
            iconView.setImageDrawable(android.graphics.drawable.BitmapDrawable(ctx.resources, bmp))
        } else {
            val d = try { ctx.packageManager.getApplicationIcon(info.componentName!!.packageName) } catch (_: Throwable) { null }
            if (d != null) {
                iconView.setImageDrawable(IconFactory.render(ctx.resources, d, NothingIconLogic.currentStyle(ctx), size))
            }
        }
        view.tag = info
        view.setOnClickListener(mLauncher.itemOnClickListener)
        view.setOnLongClickListener(mLauncher.allAppsItemLongClickListener)
    }

    override fun launchHighlightedItem(): Boolean {
        val v = highlighted ?: return false
        val tag = v.tag
        if (tag is com.nothinglauncher.search.CommandEngine.CommandResult) {
            tag.action(v.context)
            return true
        }
        if (v is BubbleTextView && tag is ItemInfo) {
            return mLauncher.startActivitySafely(v, tag.intent, tag) != null
        }
        return super.launchHighlightedItem()
    }

    override fun getHighlightedItem(): View? = highlighted ?: super.getHighlightedItem()

    override fun clearHighlightedItem() {
        highlighted = null
        super.clearHighlightedItem()
    }

    companion object {
        const val VIEW_TYPE_COMMAND = 1 shl BaseAllAppsAdapter.NEXT_ID
        const val VIEW_TYPE_LIST_APP = 1 shl (BaseAllAppsAdapter.NEXT_ID + 1)
    }
}
