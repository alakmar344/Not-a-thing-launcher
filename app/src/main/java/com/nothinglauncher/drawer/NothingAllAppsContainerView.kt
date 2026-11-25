package com.nothinglauncher.drawer

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.GridLayoutManager
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.AllAppsGridAdapter
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.allapps.LauncherAllAppsContainerView
import com.android.launcher3.allapps.search.AllAppsSearchUiDelegate
import com.android.launcher3.allapps.search.SearchAdapterProvider
import com.nothinglauncher.NothingPrefs
import java.util.function.Predicate

/**
 * Custom [LauncherAllAppsContainerView] wired via `res/layout/all_apps.xml` overlay:
 * - Installs [NothingSearchAdapterProvider] for inline Command Cards & List-Mode rows
 * - Filters apps hidden in Vault cleanly across rebinds
 * - Supports instant Grid <-> Minimal List drawer switching
 */
class NothingAllAppsContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LauncherAllAppsContainerView(context, attrs, defStyleAttr) {

    private var mainAppsList: AlphabeticalAppsList<Launcher>? = null
    private var searchAppsList: AlphabeticalAppsList<Launcher>? = null
    private var mainAdapter: AllAppsGridAdapter<Launcher>? = null

    override fun createSearchUiDelegate(): AllAppsSearchUiDelegate {
        return object : AllAppsSearchUiDelegate(this) {
            override fun createMainAdapterProvider(): SearchAdapterProvider<*> {
                return NothingSearchAdapterProvider(mActivityContext) { position, inSearch ->
                    val list = if (inSearch || isSearching) searchAppsList else mainAppsList
                    list?.adapterItems?.getOrNull(position)
                }
            }
        }
    }

    override fun createAdapter(appsList: AlphabeticalAppsList<Launcher>): BaseAllAppsAdapter<Launcher> {
        if (mainAppsList == null) {
            mainAppsList = appsList
        } else if (searchAppsList == null && mainAppsList !== appsList && appsList.adapterItems.isEmpty()) {
            // AdapterHolder order in ActivityAllAppsContainerView: MAIN, WORK, SEARCH.
            // Track whichever non-main list is bound to search RecyclerView later.
        }
        val adapter = object : AllAppsGridAdapter<Launcher>(
            mActivityContext, layoutInflater, appsList, mMainAdapterProvider, null
        ) {
            override fun getItemViewType(position: Int): Int {
                val base = super.getItemViewType(position)
                if (base == VIEW_TYPE_ICON && !isSearching &&
                    NothingPrefs.get(context).drawerLayoutMode == NothingPrefs.DrawerLayoutMode.LIST
                ) {
                    return NothingSearchAdapterProvider.VIEW_TYPE_LIST_APP
                }
                return base
            }
        }
        val baseSizer = adapter.layoutManager.spanSizeLookup
        adapter.layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (!isSearching &&
                    NothingPrefs.get(context).drawerLayoutMode == NothingPrefs.DrawerLayoutMode.LIST
                ) {
                    val items = appsList.adapterItems
                    if (position in items.indices && items[position].viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON) {
                        return adapter.layoutManager.spanCount
                    }
                }
                return baseSizer.getSpanSize(position)
            }
        }
        if (appsList === mainAppsList) mainAdapter = adapter
        return adapter
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Capture search list safely from SearchRecyclerView once inflated
        searchRecyclerView?.let { srv ->
            searchAppsList = srv.apps as? AlphabeticalAppsList<Launcher>
        }
        applyHiddenFilter()
    }

    override fun rebindAdapters(force: Boolean) {
        super.rebindAdapters(force)
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
    }

    fun refreshDrawerLayoutMode() {
        mainAdapter?.notifyDataSetChanged()
        mainAppsList?.updateAdapterItems()
    }

    fun openVault() {
        HiddenVaultSheet.openWithAuth(mActivityContext) {
            applyHiddenFilter()
        }
    }
}
