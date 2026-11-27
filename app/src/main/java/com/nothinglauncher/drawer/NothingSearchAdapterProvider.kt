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

    private fun bindCategory(view: View, position: Int) {
        val item = lookupAdapterItem(view, position, false) as? NothingAllAppsContainerView.CategoryAdapterItem ?: return
        val ctx = view.context
        AccentTheme.tintDot(view.findViewById(R.id.catDot), ctx)
        view.findViewById<TextView>(R.id.catTitle).text = item.title
        view.findViewById<TextView?>(R.id.catCount)?.text = item.count.toString()
    }

    private fun bindCommand(view: View, position: Int) {
        val item = lookupAdapterItem(view, position, true) as? NothingSearchAlgorithm.CommandAdapterItem ?: return
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
        val item = lookupAdapterItem(view, position, false) ?: return
        val info: AppInfo = item.itemInfo ?: return
        val ctx = view.context
        val prefs = NothingPrefs.get(ctx)
        val title = view.findViewById<TextView>(R.id.listAppTitle)
        val iconView = view.findViewById<ImageView>(R.id.listAppIcon)
        val tag = view.findViewById<TextView>(R.id.listAppTag)

        val cn = info.componentName
        title.text = cn?.let { prefs.customAppLabels[it.flattenToString()] } ?: info.title
        tag.text = info.sectionName
        val size = (34 * ctx.resources.displayMetrics.density).toInt()
        val bmp = info.bitmap.icon
        val pkg = cn?.packageName ?: info.targetPackage
        if (bmp != null) {
            iconView.setImageDrawable(android.graphics.drawable.BitmapDrawable(ctx.resources, bmp))
        } else if (!pkg.isNullOrBlank()) {
            val packPkg = prefs.iconPackPackage
            val packIcon = if (packPkg.isNotBlank() && cn != null) {
                IconPackManager.loadIconForComponent(ctx, packPkg, cn)
            } else {
                null
            }
            val d = packIcon ?: try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { null }
            if (d != null) {
                val style = NothingIconLogic.currentStyle(ctx)
                val night = NothingIconLogic.isNight(ctx)
                val compKey = cn?.flattenToString() ?: pkg
                iconView.setImageDrawable(
                    IconFactory.render(
                        ctx.resources,
                        d,
                        style,
                        size,
                        cacheKey = "list:$compKey:${style.name}:$packPkg:$night"
                    )
                )
            } else {
                iconView.setImageDrawable(null)
            }
        } else {
            iconView.setImageDrawable(null)
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
