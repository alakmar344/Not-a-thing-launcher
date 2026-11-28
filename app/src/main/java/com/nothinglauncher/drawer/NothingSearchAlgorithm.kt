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
import java.util.concurrent.atomic.AtomicInteger

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
    private val searchSeq = AtomicInteger(0)

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
        searchSeq.incrementAndGet()
        if (interruptActiveRequests) {
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    override fun doSearch(query: String, callback: SearchCallback<AdapterItem>) {
        val gen = searchSeq.incrementAndGet()
        val trimmed = query.trim()
        appState.model.enqueueModelUpdateTask(object : BaseModelUpdateTask() {
            override fun execute(app: LauncherAppState, dataModel: BgDataModel, apps: AllAppsList) {
                if (gen != searchSeq.get()) return
                val prefs = NothingPrefs.get(context)
                val hidden = prefs.hiddenPackages
                val commands = CommandEngine.evaluate(context, trimmed)
                if (gen != searchSeq.get()) return

                // Insertion top-K avoids sorting/boxing all installed apps on every keystroke.
                val topInfos = arrayOfNulls<com.android.launcher3.model.data.AppInfo>(MAX_APP_RESULTS)
                val topScores = IntArray(MAX_APP_RESULTS)
                var topCount = 0
                val data = apps.data
                for (i in 0 until data.size) {
                    val info = data[i]
                    val pkg = info.componentName?.packageName
                    if (pkg != null && hidden.contains(pkg)) continue
                    val title = info.title?.toString() ?: continue
                    val score = CommandEngine.scoreApp(trimmed, title)
                    if (score <= 0) continue
                    if (topCount < MAX_APP_RESULTS || score > topScores[topCount - 1]) {
                        val limit = if (topCount < MAX_APP_RESULTS) topCount++ else MAX_APP_RESULTS - 1
                        var pos = limit
                        while (pos > 0 && topScores[pos - 1] < score) {
                            topInfos[pos] = topInfos[pos - 1]
                            topScores[pos] = topScores[pos - 1]
                            pos--
                        }
                        topInfos[pos] = info
                        topScores[pos] = score
                    }
                }

                if (gen != searchSeq.get()) return

                val results = ArrayList<AdapterItem>(commands.size + topCount)
                for (c in commands) {
                    if (c.kind != CommandEngine.Kind.WEB) {
                        results += CommandAdapterItem(NothingSearchAdapterProvider.VIEW_TYPE_COMMAND, c)
                    }
                }
                for (i in 0 until topCount) {
                    results += AdapterItem.asApp(topInfos[i]!!)
                }
                for (c in commands) {
                    if (c.kind == CommandEngine.Kind.WEB) {
                        results += CommandAdapterItem(NothingSearchAdapterProvider.VIEW_TYPE_COMMAND, c)
                    }
                }

                mainHandler.post {
                    if (gen == searchSeq.get()) {
                        callback.onSearchResult(query, results)
                    }
                }
            }
        })
    }

    private companion object {
        const val MAX_APP_RESULTS = 8
    }
}
