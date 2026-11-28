package com.nothinglauncher.drawer

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.AllAppsGridAdapter
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.allapps.LauncherAllAppsContainerView
import com.android.launcher3.allapps.search.AllAppsSearchUiDelegate
import com.android.launcher3.allapps.search.SearchAdapterProvider
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.ComponentKey
import com.nothinglauncher.BigScreenUi
import com.nothinglauncher.NothingPrefs
import java.util.EnumMap
import java.util.Locale
import java.util.function.Predicate

/**
 * Custom [LauncherAllAppsContainerView] wired via `res/layout/all_apps.xml` overlay:
 * - Installs [NothingSearchAdapterProvider] for inline Command Cards, List rows, and Smart Categories
 * - Filters apps hidden in Vault cleanly across rebinds
 * - Supports instant Grid <-> Minimal List <-> Smart Categories drawer switching
 */
class NothingAllAppsContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LauncherAllAppsContainerView(context, attrs, defStyleAttr) {

    class CategoryAdapterItem(
        val key: String,
        val title: String,
        val count: Int
    ) : BaseAllAppsAdapter.AdapterItem(NothingSearchAdapterProvider.VIEW_TYPE_CATEGORY) {
        override fun isSameAs(other: BaseAllAppsAdapter.AdapterItem): Boolean =
            other is CategoryAdapterItem && other.key == key

        override fun isContentSame(other: BaseAllAppsAdapter.AdapterItem): Boolean =
            other is CategoryAdapterItem && other.title == title && other.count == count
    }

    private val prefs: NothingPrefs by lazy(LazyThreadSafetyMode.NONE) { NothingPrefs.get(context) }
    private var mainAppsList: AlphabeticalAppsList<Launcher>? = null
    private var workAppsList: AlphabeticalAppsList<Launcher>? = null
    private var searchAppsList: AlphabeticalAppsList<Launcher>? = null
    private var mainAdapter: AllAppsGridAdapter<Launcher>? = null
    private var workAdapter: AllAppsGridAdapter<Launcher>? = null
    private var observedMainAdapter: AllAppsGridAdapter<Launcher>? = null
    private var observedWorkAdapter: AllAppsGridAdapter<Launcher>? = null
    private var applyingCategories = false

    private val categorySyncObserver = object : RecyclerView.AdapterDataObserver() {
        private val schedule = Runnable {
            if (!applyingCategories) applyCategoriesIfNeeded()
        }

        override fun onChanged() {
            if (!applyingCategories) post(schedule)
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            if (!applyingCategories) post(schedule)
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            if (!applyingCategories) post(schedule)
        }
    }

    override fun createSearchUiDelegate(): AllAppsSearchUiDelegate {
        return object : AllAppsSearchUiDelegate(this) {
            override fun createMainAdapterProvider(): SearchAdapterProvider<*> {
                return NothingSearchAdapterProvider(
                    context,
                    mActivityContext,
                    { isSearching }
                ) { position, inSearch ->
                    val list = when {
                        inSearch || isSearching -> searchAppsList
                        currentPage == AdapterHolder.WORK -> workAppsList
                        else -> mainAppsList
                    }
                    list?.adapterItems?.getOrNull(position)
                }
            }
        }
    }

    override fun createAdapter(appsList: AlphabeticalAppsList<Launcher>): BaseAllAppsAdapter<Launcher> {
        val role = when {
            mainAppsList == null || appsList === mainAppsList -> {
                mainAppsList = appsList
                AdapterHolder.MAIN
            }
            workAppsList == null || appsList === workAppsList -> {
                workAppsList = appsList
                AdapterHolder.WORK
            }
            else -> {
                if (searchAppsList == null) searchAppsList = appsList
                AdapterHolder.SEARCH
            }
        }
        val isSearchAdapter = role == AdapterHolder.SEARCH
        val adapter = object : AllAppsGridAdapter<Launcher>(
            mActivityContext, layoutInflater, appsList, mMainAdapterProvider, null
        ) {
            override fun getItemViewType(position: Int): Int {
                val base = super.getItemViewType(position)
                if (base == VIEW_TYPE_ICON && !isSearchAdapter && !isSearching &&
                    prefs.drawerLayoutMode == NothingPrefs.DrawerLayoutMode.LIST
                ) {
                    return NothingSearchAdapterProvider.VIEW_TYPE_LIST_APP
                }
                return base
            }
        }
        val baseSizer = adapter.layoutManager.spanSizeLookup
        val searchCmdCols = BigScreenUi.searchCommandColumns(context).coerceAtLeast(1)
        val drawerListCols = BigScreenUi.drawerListColumns(context).coerceAtLeast(1)
        adapter.layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            init {
                isSpanIndexCacheEnabled = true
                isSpanGroupIndexCacheEnabled = true
            }

            override fun getSpanSize(position: Int): Int {
                val items = appsList.adapterItems
                if (position in items.indices) {
                    val item = items[position]
                    if (item.viewType == NothingSearchAdapterProvider.VIEW_TYPE_CATEGORY) {
                        return adapter.layoutManager.spanCount
                    }
                    if ((isSearchAdapter || isSearching) &&
                        item.viewType == NothingSearchAdapterProvider.VIEW_TYPE_COMMAND
                    ) {
                        return (adapter.layoutManager.spanCount / searchCmdCols).coerceAtLeast(1)
                    }
                    if (!isSearchAdapter && !isSearching &&
                        item.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON &&
                        prefs.drawerLayoutMode == NothingPrefs.DrawerLayoutMode.LIST
                    ) {
                        return (adapter.layoutManager.spanCount / drawerListCols).coerceAtLeast(1)
                    }
                }
                return baseSizer.getSpanSize(position)
            }
        }
        when (role) {
            AdapterHolder.MAIN -> mainAdapter = adapter
            AdapterHolder.WORK -> workAdapter = adapter
        }
        return adapter
    }

    private fun ensureObserverAttached() {
        mainAdapter?.let { adapter ->
            if (observedMainAdapter !== adapter) {
                observedMainAdapter?.unregisterAdapterDataObserver(categorySyncObserver)
                adapter.registerAdapterDataObserver(categorySyncObserver)
                observedMainAdapter = adapter
            }
        }
        workAdapter?.let { adapter ->
            if (observedWorkAdapter !== adapter) {
                observedWorkAdapter?.unregisterAdapterDataObserver(categorySyncObserver)
                adapter.registerAdapterDataObserver(categorySyncObserver)
                observedWorkAdapter = adapter
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        ensureObserverAttached()
        searchRecyclerView?.let { srv ->
            searchAppsList = srv.apps as? AlphabeticalAppsList<Launcher>
        }
        applyHiddenFilter()
    }

    override fun rebindAdapters(force: Boolean) {
        super.rebindAdapters(force)
        ensureObserverAttached()
        searchRecyclerView?.let { srv ->
            @Suppress("UNCHECKED_CAST")
            searchAppsList = srv.apps as? AlphabeticalAppsList<Launcher>
        }
        applyHiddenFilter()
    }

    fun applyHiddenFilter() {
        val prefs = NothingPrefs.get(context)
        val hidden = prefs.hiddenPackages
        mainAppsList?.updateItemFilter(Predicate { info ->
            mPersonalMatcher.test(info) &&
                (info?.targetPackage == null || !hidden.contains(info.targetPackage))
        })
        val workUserMatcher = mWorkManager.userMatcher
        workAppsList?.updateItemFilter(Predicate { info ->
            info != null && workUserMatcher.test(info.user) &&
                (info.targetPackage == null || !hidden.contains(info.targetPackage))
        })
        applyCategoriesIfNeeded()
    }

    fun refreshDrawerLayoutMode() {
        mainAdapter?.layoutManager?.spanSizeLookup?.apply {
            invalidateSpanIndexCache()
            invalidateSpanGroupIndexCache()
        }
        workAdapter?.layoutManager?.spanSizeLookup?.apply {
            invalidateSpanIndexCache()
            invalidateSpanGroupIndexCache()
        }
        mainAppsList?.updateAdapterItems()
        workAppsList?.updateAdapterItems()
        if (prefs.drawerLayoutMode == NothingPrefs.DrawerLayoutMode.CATEGORIES) {
            applyCategoriesIfNeeded(forceRebuild = true)
        } else {
            applyingCategories = true
            mainAdapter?.notifyDataSetChanged()
            workAdapter?.notifyDataSetChanged()
            applyingCategories = false
        }
    }

    private fun applyCategoriesIfNeeded(forceRebuild: Boolean = false) {
        if (isSearching) return
        if (prefs.drawerLayoutMode != NothingPrefs.DrawerLayoutMode.CATEGORIES) return
        applyCategoriesToList(mainAppsList, mainAdapter, forceRebuild)
        applyCategoriesToList(workAppsList, workAdapter, forceRebuild)
    }

    private fun applyCategoriesToList(
        list: AlphabeticalAppsList<Launcher>?,
        adapter: AllAppsGridAdapter<Launcher>?,
        forceRebuild: Boolean
    ) {
        if (list == null || adapter == null) return
        val items = list.adapterItems
        if (!forceRebuild) {
            for (i in 0 until items.size) {
                if (items[i] is CategoryAdapterItem) return
            }
        }
        val seen = HashSet<ComponentKey>(items.size)
        val grouped = EnumMap<AppCategorizer.Bucket, ArrayList<AppInfo>>(AppCategorizer.Bucket::class.java)
        var totalApps = 0
        for (i in 0 until items.size) {
            val info = items[i].itemInfo ?: continue
            val key = ComponentKey(info.componentName, info.user)
            if (!seen.add(key)) continue
            val bucket = AppCategorizer.bucketFor(context, info)
            grouped.getOrPut(bucket) { ArrayList() }.add(info)
            totalApps++
        }
        if (totalApps == 0) return

        val rebuilt = ArrayList<BaseAllAppsAdapter.AdapterItem>(totalApps + grouped.size)
        val sections = ArrayList<AlphabeticalAppsList.FastScrollSectionInfo>(grouped.size)
        val cols = mActivityContext.deviceProfile.numShownAllAppsColumns.coerceAtLeast(1)
        val locale = Locale.getDefault()
        val titleComparator = Comparator<AppInfo> { a, b ->
            val ta = a.title?.toString()?.lowercase(locale).orEmpty()
            val tb = b.title?.toString()?.lowercase(locale).orEmpty()
            ta.compareTo(tb)
        }
        var rowIndex = 0
        for (bucket in AppCategorizer.Bucket.entries) {
            val bucketApps = grouped[bucket] ?: continue
            if (bucketApps.isEmpty()) continue
            bucketApps.sortWith(titleComparator)
            val bucketTitle = context.getString(bucket.titleRes)
            val headerPos = rebuilt.size
            sections += AlphabeticalAppsList.FastScrollSectionInfo(
                bucketTitle.take(3).uppercase(locale),
                headerPos
            )
            rebuilt += CategoryAdapterItem(
                key = bucket.name,
                title = bucketTitle,
                count = bucketApps.size
            ).apply {
                this.rowIndex = rowIndex++
            }
            for (idx in 0 until bucketApps.size) {
                val item = BaseAllAppsAdapter.AdapterItem.asApp(bucketApps[idx])
                item.rowIndex = rowIndex + idx / cols
                item.rowAppIndex = idx % cols
                rebuilt += item
            }
            rowIndex += (bucketApps.size + cols - 1) / cols
        }

        applyingCategories = true
        items.clear()
        items.addAll(rebuilt)
        list.fastScrollerSections.clear()
        list.fastScrollerSections.addAll(sections)
        adapter.layoutManager.spanSizeLookup.apply {
            invalidateSpanIndexCache()
            invalidateSpanGroupIndexCache()
        }
        adapter.notifyDataSetChanged()
        applyingCategories = false
    }

    fun openVault() {
        HiddenVaultSheet.openWithAuth(mActivityContext) {
            applyHiddenFilter()
        }
    }
}
