package com.nothinglauncher.drawer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.allapps.AllAppsGridAdapter
import com.android.launcher3.allapps.AllAppsRecyclerView
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
import com.nothinglauncher.icons.IconPackManager
import com.nothinglauncher.icons.NothingIconLogic

/**
 * Renders full-width [CommandEngine] cards and Minimal List app rows inside Launcher3's
 * stock [AllAppsGridAdapter] without modifying AOSP Launcher3.
 */
class NothingSearchAdapterProvider(
    private val appContext: Context,
    launcher: ActivityContext,
    private val isSearchingProvider: () -> Boolean = { false },
    private val itemLookup: (position: Int, inSearch: Boolean) -> BaseAllAppsAdapter.AdapterItem?
) : DefaultSearchAdapterProvider(launcher) {

    private var highlighted: View? = null

    override fun isViewSupported(viewType: Int): Boolean =
        viewType == VIEW_TYPE_COMMAND ||
            viewType == VIEW_TYPE_LIST_APP ||
            viewType == VIEW_TYPE_CATEGORY

    override fun getSupportedItemsPerRowArray(): IntArray = intArrayOf(1, 2, 3)

    override fun getItemsPerRow(viewType: Int, appsPerRow: Int): Int = when (viewType) {
        VIEW_TYPE_LIST_APP -> com.nothinglauncher.BigScreenUi.drawerListColumns(appContext)
        VIEW_TYPE_COMMAND -> com.nothinglauncher.BigScreenUi.searchCommandColumns(appContext)
        VIEW_TYPE_CATEGORY -> 1
        else -> appsPerRow
    }

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
        VIEW_TYPE_CATEGORY -> {
            val v = layoutInflater.inflate(R.layout.item_drawer_category, parent, false)
            BaseAllAppsAdapter.ViewHolder(v)
        }
        else -> null
    }

    private fun lookupAdapterItem(
        view: View,
        position: Int,
        inSearch: Boolean
    ): BaseAllAppsAdapter.AdapterItem? {
        val attached = (view.parent as? AllAppsRecyclerView)?.apps?.adapterItems?.getOrNull(position)
        return attached ?: itemLookup(position, inSearch)
    }

    override fun onBindView(holder: BaseAllAppsAdapter.ViewHolder, position: Int) {
        if (position == 0 && isSearchingProvider()) {
            highlighted = holder.itemView
        } else if (holder.itemView === highlighted && (!isSearchingProvider() || position != 0)) {
            highlighted = null
        }
        when (holder.itemViewType) {
            VIEW_TYPE_COMMAND -> bindCommand(holder.itemView, position)
            VIEW_TYPE_LIST_APP -> bindListApp(holder.itemView, position)
            VIEW_TYPE_CATEGORY -> bindCategory(holder.itemView, position)
            else -> super.onBindView(holder, position)
        }
    }

    private class CatHolder(val dot: View, val title: TextView, val count: TextView?)
    private class CmdHolder(val badge: TextView, val title: TextView, val subtitle: TextView)
    private class ListAppHolder(val title: TextView, val iconView: ImageView, val tag: TextView)

    private val prefs by lazy(LazyThreadSafetyMode.NONE) { NothingPrefs.get(appContext) }

    private fun bindCategory(view: View, position: Int) {
        val item = lookupAdapterItem(view, position, false) as? NothingAllAppsContainerView.CategoryAdapterItem ?: return
        val holder = (view.getTag(R.id.nt_view_holder_tag) as? CatHolder) ?: CatHolder(
            view.findViewById(R.id.catDot),
            view.findViewById(R.id.catTitle),
            view.findViewById(R.id.catCount)
        ).also { view.setTag(R.id.nt_view_holder_tag, it) }
        val ctx = view.context
        AccentTheme.tintDot(holder.dot, ctx)
        holder.title.text = item.title
        holder.count?.text = item.count.toString()
    }

    private fun bindCommand(view: View, position: Int) {
        val item = lookupAdapterItem(view, position, true) as? NothingSearchAlgorithm.CommandAdapterItem ?: return
        val cmd = item.command
        val ctx = view.context
        var holder = view.getTag(R.id.nt_view_holder_tag) as? CmdHolder
        if (holder == null) {
            holder = CmdHolder(
                view.findViewById(R.id.cmdBadge),
                view.findViewById(R.id.cmdTitle),
                view.findViewById(R.id.cmdSubtitle)
            )
            view.setTag(R.id.nt_view_holder_tag, holder)
            view.setOnClickListener { clicked ->
                val active = clicked.tag as? com.nothinglauncher.search.CommandEngine.CommandResult ?: return@setOnClickListener
                Tactile.confirm(clicked)
                active.action(clicked.context)
            }
        }
        holder.badge.text = cmd.badge
        val isAccent = cmd.kind == com.nothinglauncher.search.CommandEngine.Kind.ANSWER
        holder.badge.setBackgroundResource(if (isAccent) R.drawable.bg_pill_white else R.drawable.bg_pill_outline)
        holder.badge.setTextColor(
            if (isAccent) ContextCompat.getColor(ctx, R.color.pill_active_text)
            else AccentTheme.accentColor(ctx)
        )
        holder.title.text = cmd.title
        holder.subtitle.text = cmd.subtitle
        view.tag = cmd
    }

    private fun bindListApp(view: View, position: Int) {
        val item = lookupAdapterItem(view, position, false) ?: return
        val info: AppInfo = item.itemInfo ?: return
        val ctx = view.context
        var holder = view.getTag(R.id.nt_view_holder_tag) as? ListAppHolder
        if (holder == null) {
            holder = ListAppHolder(
                view.findViewById(R.id.listAppTitle),
                view.findViewById(R.id.listAppIcon),
                view.findViewById(R.id.listAppTag)
            )
            view.setTag(R.id.nt_view_holder_tag, holder)
            view.setOnClickListener(mLauncher.itemOnClickListener)
            view.setOnLongClickListener(mLauncher.allAppsItemLongClickListener)
        }

        val cn = info.componentName
        holder.title.text = cn?.let { prefs.customAppLabels[it.flattenToString()] } ?: info.title
        holder.tag.text = info.sectionName
        val size = (34 * ctx.resources.displayMetrics.density).toInt()
        val bmp = info.bitmap.icon
        val pkg = cn?.packageName ?: info.targetPackage
        if (bmp != null) {
            holder.iconView.setImageDrawable(android.graphics.drawable.BitmapDrawable(ctx.resources, bmp))
        } else if (!pkg.isNullOrBlank()) {
            val packPkg = prefs.iconPackPackage
            val style = NothingIconLogic.currentStyle(ctx)
            val night = NothingIconLogic.isNight(ctx)
            val compKey = cn?.flattenToString() ?: pkg
            val cacheKey = "list:$compKey:${style.name}:$packPkg:$night"
            val cachedDrawable = IconFactory.getCached(ctx.resources, style, size, cacheKey)
            if (cachedDrawable != null) {
                holder.iconView.setImageDrawable(cachedDrawable)
            } else {
                val packIcon = if (packPkg.isNotBlank() && cn != null) {
                    IconPackManager.loadIconForComponent(ctx, packPkg, cn)
                } else {
                    null
                }
                val d = packIcon ?: try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { null }
                if (d != null) {
                    holder.iconView.setImageDrawable(IconFactory.render(ctx.resources, d, style, size, cacheKey = cacheKey))
                } else {
                    holder.iconView.setImageDrawable(null)
                }
            }
        } else {
            holder.iconView.setImageDrawable(null)
        }
        view.tag = info
    }

    override fun launchHighlightedItem(): Boolean {
        val v = highlighted ?: return false
        val tag = v.tag
        if (tag is com.nothinglauncher.search.CommandEngine.CommandResult) {
            tag.action(v.context)
            return true
        }
        if (tag is ItemInfo) {
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
        const val VIEW_TYPE_CATEGORY = 1 shl (BaseAllAppsAdapter.NEXT_ID + 2)
    }
}
