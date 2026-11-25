package com.nothinglauncher.drawer

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.launcher3.LauncherAppState
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.model.AllAppsList
import com.android.launcher3.model.BaseModelUpdateTask
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.search.SearchAlgorithm
import com.android.launcher3.search.SearchCallback
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.search.CommandEngine

/**
 * Replaces stock [com.android.launcher3.allapps.search.DefaultAppSearchAlgorithm]:
 * - Excludes apps hidden in Vault
 * - Scores apps by prefix, word boundary, initials/acronym ("yt" -> YouTube), and 1-typo fuzzy
 * - Injects offline [CommandEngine] answers, unit/timezone conversions, quick actions, contacts, and web search
 */
class NothingSearchAlgorithm(
    private val context: Context
) : SearchAlgorithm<AdapterItem> {

    private val appState = LauncherAppState.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    class CommandAdapterItem(
        viewType: Int,
        val command: CommandEngine.CommandResult
    ) : AdapterItem(viewType) {
        override fun isSameAs(other: AdapterItem): Boolean =
            other is CommandAdapterItem &&
                other.viewType == viewType &&
                other.command.badge == command.badge &&
                other.command.title == command.title

        override fun isContentSame(other: AdapterItem): Boolean =
            other is CommandAdapterItem &&
                other.command.title == command.title &&
                other.command.subtitle == command.subtitle
    }

    override fun cancel(interruptActiveRequests: Boolean) {
        if (interruptActiveRequests) {
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    override fun doSearch(query: String, callback: SearchCallback<AdapterItem>) {
        val trimmed = query.trim()
        appState.model.enqueueModelUpdateTask(object : BaseModelUpdateTask() {
            override fun execute(app: LauncherAppState, dataModel: BgDataModel, apps: AllAppsList) {
                val prefs = NothingPrefs.get(context)
                val hidden = prefs.hiddenPackages
                val commands = CommandEngine.evaluate(context, trimmed)
                val answersAndActions = commands.filter { it.kind != CommandEngine.Kind.WEB }
                val webFallback = commands.filter { it.kind == CommandEngine.Kind.WEB }

                val scoredApps = apps.data
                    .asSequence()
                    .filter { info ->
                        val pkg = info.componentName?.packageName
                        pkg == null || !hidden.contains(pkg)
                    }
                    .mapNotNull { info ->
                        val title = info.title?.toString().orEmpty()
                        val score = CommandEngine.scoreApp(trimmed, title)
                        if (score > 0) info to score else null
                    }
                    .sortedByDescending { it.second }
                    .take(MAX_APP_RESULTS)
                    .map { AdapterItem.asApp(it.first) }
                    .toList()

                val results = ArrayList<AdapterItem>(answersAndActions.size + scoredApps.size + webFallback.size)
                answersAndActions.forEach { cmd ->
                    results += CommandAdapterItem(NothingSearchAdapterProvider.VIEW_TYPE_COMMAND, cmd)
                }
                results += scoredApps
                webFallback.forEach { cmd ->
                    results += CommandAdapterItem(NothingSearchAdapterProvider.VIEW_TYPE_COMMAND, cmd)
                }

                mainHandler.post { callback.onSearchResult(query, results) }
            }
        })
    }

    private companion object {
        const val MAX_APP_RESULTS = 8
    }
}
