package com.nothinglauncher

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.UUID

/**
 * Single source of truth for apps, multi-page workspace, elements and dock layout.
 * Apps are loaded through [LauncherApps] and refreshed automatically when packages change.
 */
class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = LauncherPrefs(application)

    private val _allApps = MutableLiveData<List<AppInfo>>(emptyList())
    /** Every visible (non-hidden) app, alphabetically. */
    val allApps: LiveData<List<AppInfo>> = _allApps

    private val _workspacePages = MutableLiveData<List<WorkspacePage>>(emptyList())
    /** Ordered workspace pages. */
    val workspacePages: LiveData<List<WorkspacePage>> = _workspacePages

    private val _homeApps = MutableLiveData<List<AppInfo>>(emptyList())
    val homeApps: LiveData<List<AppInfo>> = _homeApps

    private val _dockApps = MutableLiveData<List<AppInfo>>(emptyList())
    val dockApps: LiveData<List<AppInfo>> = _dockApps

    private val _recentApps = MutableLiveData<List<AppInfo>>(emptyList())
    val recentApps: LiveData<List<AppInfo>> = _recentApps

    private val _loaded = MutableLiveData(false)
    val loaded: LiveData<Boolean> = _loaded

    /** All launchable apps including hidden ones. */
    private var installed: List<AppInfo> = emptyList()
    private var renderedIconStyle: LauncherPrefs.IconStyle? = null
    private var loadJob: Job? = null

    private val launcherApps = application.getSystemService(LauncherApps::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = loadApps()
        override fun onPackageAdded(packageName: String, user: UserHandle) = loadApps()
        override fun onPackageChanged(packageName: String, user: UserHandle) = loadApps()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = loadApps()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            if (!replacing) loadApps()
        }
    }

    init {
        try {
            launcherApps?.registerCallback(packageCallback, mainHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        loadApps()
    }

    fun loadApps() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val style = prefs.iconStyle
            val apps = withContext(Dispatchers.Default) { queryApps(style) }
            installed = apps
            renderedIconStyle = style
            publish()
            _loaded.value = true
        }
    }

    /** Re-applies settings that affect the data (icon style, hidden apps). */
    fun onSettingsChanged() {
        val rendered = renderedIconStyle ?: return
        if (prefs.iconStyle != rendered) loadApps() else publish()
    }

    private fun queryApps(style: LauncherPrefs.IconStyle): List<AppInfo> {
        val app = getApplication<Application>()
        val la = launcherApps ?: return emptyList()
        val mainUser = Process.myUserHandle()
        val userManager = app.getSystemService(android.os.UserManager::class.java)
        val density = app.resources.displayMetrics.densityDpi
        val sizePx = app.resources.getDimensionPixelSize(R.dimen.icon_render_size)
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val profiles = try { la.profiles } catch (e: Exception) { null }?.takeIf { it.isNotEmpty() } ?: listOf(mainUser)
        return profiles.flatMap { user ->
            val isMain = user == mainUser
            val serial = if (isMain) 0L else (try { userManager?.getSerialNumberForUser(user) } catch (e: Exception) { null } ?: -1L)
            val quiet = !isMain && (try { userManager?.isQuietModeEnabled(user) } catch (e: Exception) { null } == true)
            if (quiet) return@flatMap emptyList()
            try {
                la.getActivityList(null, user)
                    .filter { !(isMain && it.componentName.packageName == app.packageName) }
                    .map { info ->
                        val raw = try {
                            if (isMain) info.getIcon(density) else info.getBadgedIcon(density)
                        } catch (e: Exception) { null } ?: app.packageManager.defaultActivityIcon
                        val flags = info.applicationInfo.flags
                        AppInfo(
                            label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                            packageName = info.componentName.packageName,
                            activityName = info.componentName.className,
                            icon = IconFactory.render(app.resources, raw, style, sizePx),
                            user = info.user,
                            isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
                            profileSerial = serial
                        )
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    private fun publish() {
        val hidden = prefs.hiddenApps
        val visible = installed.filter { it.key !in hidden }
        val byKey = installed.associateBy { it.key }
        _allApps.value = visible

        // Dock: saved -> legacy -> sensible defaults
        var dockKeys = prefs.dockItems
        if (dockKeys == null) {
            dockKeys = prefs.legacyDockPackages?.mapNotNull { pkg -> installed.firstOrNull { it.packageName == pkg }?.key }
                ?: defaultDock(visible).map { it.key }
            if (installed.isNotEmpty()) prefs.dockItems = dockKeys
        }
        val dock = dockKeys.mapNotNull { byKey[it] }.filter { it.key !in hidden }.take(MAX_DOCK)
        _dockApps.value = dock

        // Pages: saved -> migrated -> sensible defaults (2 pages out of the box)
        var savedPages = prefs.pages
        if (savedPages == null || savedPages.isEmpty()) {
            val legacyHome = prefs.legacyHomePackages?.mapNotNull { pkg -> installed.firstOrNull { it.packageName == pkg }?.key }
                ?: prefs.homeItems
            val homeKeys = legacyHome ?: defaultHome(visible, dock).map { it.key }
            val page0Items = WorkspacePage.buildItemsFromLegacy(
                columns = prefs.gridColumns,
                elements = listOf(BespokeElement.CLOCK.id, BespokeElement.BATTERY_CALENDAR.id),
                widgetIds = prefs.widgetIds,
                enlargedApps = emptyList(),
                folders = emptyList(),
                appKeys = homeKeys
            )
            val page0 = WorkspacePage(id = "page_0", items = page0Items)
            val page1Apps = defaultPage1(visible, dock, homeKeys.toSet()).map { it.key }
            val page1Items = WorkspacePage.buildItemsFromLegacy(
                columns = prefs.gridColumns,
                elements = listOf(BespokeElement.WEATHER.id, BespokeElement.QUICK_TOGGLES.id),
                widgetIds = emptyList(),
                enlargedApps = emptyList(),
                folders = emptyList(),
                appKeys = page1Apps
            )
            val page1 = WorkspacePage(id = "page_1", items = page1Items)
            savedPages = listOf(page0, page1)
            if (installed.isNotEmpty()) prefs.pages = savedPages
        }

        // Enforce one home copy per app (first occurrence wins) and drop uninstalled/hidden apps.
        val claimed = mutableSetOf<String>()
        fun claim(key: String): Boolean =
            key.isNotEmpty() && key !in hidden && (installed.isEmpty() || key in byKey) && claimed.add(key)

        val sanitizedPages = savedPages.map { page ->
            val sanitizedItems = page.items.mapNotNull { itm ->
                when (itm.type) {
                    WorkspaceItem.Type.APP, WorkspaceItem.Type.MAX_APP -> if (claim(itm.appKey)) itm else null
                    WorkspaceItem.Type.FOLDER -> {
                        val f = itm.folder ?: return@mapNotNull null
                        val validApps = f.appKeys.filter { claim(it) }
                        if (validApps.isEmpty()) null else itm.copy(folder = f.copy(appKeys = validApps))
                    }
                    WorkspaceItem.Type.BESPOKE -> if (BespokeElement.fromId(itm.elementId) != null) itm else null
                    WorkspaceItem.Type.WIDGET -> if (itm.widgetId >= 0) itm else null
                }
            }
            normalizePage(WorkspacePage(page.id, sanitizedItems))
        }
        // Display-only: the saved layout is not rewritten here, so an app that is briefly
        // unavailable (update in progress, SD card, work profile paused) keeps its spot.
        _workspacePages.value = sanitizedPages
        publishHomeApps(sanitizedPages)
        _recentApps.value = prefs.recents.mapNotNull { byKey[it] }.filter { it.key !in hidden }
    }

    private fun publishHomeApps(pages: List<WorkspacePage>) {
        val byKey = installed.associateBy { it.key }
        val hidden = prefs.hiddenApps
        val keys = pages.flatMap { it.allAppKeys }.distinct()
        _homeApps.value = keys.mapNotNull { byKey[it] }.filter { it.key !in hidden }
    }

    // ---- Grid helpers -------------------------------------------------------------------

    private val columns: Int get() = prefs.gridColumns.coerceAtLeast(1)

    /**
     * Makes a page valid for the current column count: spans are clamped, full-width elements
     * stay full width, empty folders disappear, and any overlapping or out-of-bounds item is
     * moved to the nearest free area.
     */
    private fun normalizePage(page: WorkspacePage): WorkspacePage {
        val cols = columns
        val placed = mutableListOf<WorkspaceItem>()
        val ordered = page.items.distinctBy { it.id }.sortedWith(compareBy({ it.cellY }, { it.cellX }))
        ordered.forEach { raw ->
            var itm = raw
            if (itm.type == WorkspaceItem.Type.FOLDER && itm.folder?.appKeys.isNullOrEmpty()) return@forEach
            itm = when (itm.type) {
                WorkspaceItem.Type.BESPOKE -> itm.copy(cellX = 0, spanX = cols, spanY = itm.spanY.coerceAtLeast(1))
                WorkspaceItem.Type.APP -> itm.copy(spanX = 1, spanY = 1)
                WorkspaceItem.Type.MAX_APP -> {
                    val span = if (cols >= 2) 2 else 1
                    itm.copy(spanX = span, spanY = span)
                }
                WorkspaceItem.Type.FOLDER -> {
                    val span = if (itm.folder?.isEnlarged == true && cols >= 2) 2 else 1
                    itm.copy(spanX = span, spanY = span)
                }
                WorkspaceItem.Type.WIDGET -> itm.copy(
                    spanX = itm.spanX.coerceIn(1, cols),
                    spanY = itm.spanY.coerceIn(1, MAX_WIDGET_SPAN_Y)
                )
            }
            val (c, r) = GridMath.nearestFree(placed, cols, itm.cellX, itm.cellY, itm.spanX, itm.spanY)
            placed += itm.copy(cellX = c, cellY = r)
        }
        return WorkspacePage(page.id, placed)
    }

    private fun appItemId(key: String) = "app_$key"

    fun findFreeCell(page: WorkspacePage, spanX: Int = 1, spanY: Int = 1): Pair<Int, Int> =
        GridMath.firstFree(page.items, columns, spanX, spanY)

    private fun pages(): MutableList<WorkspacePage> = _workspacePages.value.orEmpty().toMutableList()

    /** Removes [key] from every presentation on a single page (icons, 2x2 tiles, folders). */
    private fun WorkspacePage.withoutApp(key: String): WorkspacePage {
        val updatedItems = items.mapNotNull { itm ->
            when {
                (itm.type == WorkspaceItem.Type.APP || itm.type == WorkspaceItem.Type.MAX_APP) && itm.appKey == key -> null
                itm.type == WorkspaceItem.Type.FOLDER && itm.folder != null && key in itm.folder.appKeys -> {
                    collapseFolderAfterRemoval(itm, itm.folder.appKeys.filterNot { it == key })
                }
                else -> itm
            }
        }
        return WorkspacePage(id, updatedItems)
    }

    /**
     * Stock Android behaviour: when an app leaves a folder and only one app is left, the folder
     * turns back into that app icon at the same spot; an emptied folder disappears.
     */
    private fun collapseFolderAfterRemoval(folderItem: WorkspaceItem, remaining: List<String>): WorkspaceItem? {
        val folder = folderItem.folder ?: return null
        return when (remaining.size) {
            0 -> null
            1 -> WorkspaceItem(
                id = appItemId(remaining[0]),
                type = WorkspaceItem.Type.APP,
                cellX = folderItem.cellX,
                cellY = folderItem.cellY,
                appKey = remaining[0]
            )
            else -> folderItem.copy(folder = folder.copy(appKeys = remaining))
        }
    }

    private fun List<WorkspacePage>.withoutAppEverywhere(key: String): MutableList<WorkspacePage> =
        map { it.withoutApp(key) }.toMutableList()

    private fun WorkspacePage.place(item: WorkspaceItem, col: Int, row: Int): WorkspacePage {
        val others = items.filterNot { it.id == item.id }
        val (c, r) = GridMath.nearestFree(others, columns, col, row, item.spanX.coerceIn(1, columns), item.spanY)
        return WorkspacePage(id, others + item.copy(cellX = c, cellY = r, spanX = item.spanX.coerceIn(1, columns)))
    }

    private fun WorkspacePage.placeFirstFree(item: WorkspaceItem): WorkspacePage {
        val others = items.filterNot { it.id == item.id }
        val (c, r) = GridMath.firstFree(others, columns, item.spanX, item.spanY)
        return WorkspacePage(id, others + item.copy(cellX = c, cellY = r, spanX = item.spanX.coerceIn(1, columns)))
    }

    // ---- Queries --------------------------------------------------------------------------

    fun isOnHome(app: AppInfo): Boolean = pageIndexForApp(app) >= 0

    fun pageIndexForApp(app: AppInfo): Int =
        _workspacePages.value.orEmpty().indexOfFirst { page -> app.key in page.allAppKeys }

    fun isInDock(app: AppInfo) = _dockApps.value.orEmpty().any { it.key == app.key }
    fun isDockFull() = _dockApps.value.orEmpty().size >= MAX_DOCK

    fun itemById(pageIndex: Int, itemId: String): WorkspaceItem? =
        _workspacePages.value?.getOrNull(pageIndex)?.items?.firstOrNull { it.id == itemId }

    fun findAppItem(appKey: String): Pair<Int, WorkspaceItem>? {
        _workspacePages.value.orEmpty().forEachIndexed { idx, page ->
            page.items.firstOrNull {
                (it.type == WorkspaceItem.Type.APP || it.type == WorkspaceItem.Type.MAX_APP) && it.appKey == appKey
            }?.let { return idx to it }
        }
        return null
    }

    /** Finds the folder that currently owns [appKey] on [pageIndex], if any. */
    fun folderIdForApp(pageIndex: Int, appKey: String): String? =
        _workspacePages.value?.getOrNull(pageIndex)?.items
            ?.firstOrNull { it.type == WorkspaceItem.Type.FOLDER && appKey in (it.folder?.appKeys.orEmpty()) }
            ?.folder?.id

    fun folderById(pageIndex: Int, folderId: String): WorkspaceFolder? =
        _workspacePages.value?.getOrNull(pageIndex)?.folders?.firstOrNull { it.id == folderId }

    // ---- Pages ------------------------------------------------------------------------------

    fun addPage(): Int {
        val current = pages()
        val newIndex = current.size
        val usedIds = current.map { it.id }.toSet()
        var n = newIndex
        while ("page_$n" in usedIds) n++
        current.add(WorkspacePage(id = "page_$n"))
        savePages(current)
        return newIndex
    }

    /**
     * Removes a page. Nothing the user placed is ever lost: its items are moved onto the
     * neighbouring page (into free cells).
     */
    fun removePage(index: Int): Boolean {
        val current = pages()
        if (current.size <= 1 || index !in current.indices) return false
        val removed = current.removeAt(index)
        val targetIdx = (index - 1).coerceAtLeast(0)
        var target = current[targetIdx]
        removed.items.sortedWith(compareBy({ it.cellY }, { it.cellX })).forEach { itm ->
            target = target.placeFirstFree(itm)
        }
        current[targetIdx] = target
        savePages(current)
        return true
    }

    /** Drops empty pages at or after [fromIndex] (pages created while dragging but not used). */
    fun removeEmptyPagesFrom(fromIndex: Int) {
        val current = pages()
        var changed = false
        for (i in current.indices.reversed()) {
            if (i >= fromIndex && i > 0 && current.size > 1 && current[i].items.isEmpty()) {
                current.removeAt(i)
                changed = true
            }
        }
        if (changed) savePages(current)
    }

    // ---- Elements & widgets -------------------------------------------------------------------

    fun addElementToPage(pageIndex: Int, elementId: String) {
        val current = pages()
        if (pageIndex !in current.indices) return
        // One instance of each element on the whole home screen; adding it again moves it here.
        if (current[pageIndex].items.any { it.type == WorkspaceItem.Type.BESPOKE && it.elementId == elementId }) return
        for (i in current.indices) {
            current[i] = WorkspacePage(current[i].id, current[i].items.filterNot {
                it.type == WorkspaceItem.Type.BESPOKE && it.elementId == elementId
            })
        }
        val spanY = defaultElementRows(elementId)
        val newItem = WorkspaceItem(
            id = "bespoke_$elementId",
            type = WorkspaceItem.Type.BESPOKE,
            cellX = 0,
            cellY = 0,
            spanX = columns,
            spanY = spanY,
            elementId = elementId
        )
        current[pageIndex] = current[pageIndex].placeFirstFree(newItem)
        savePages(current)
    }

    fun removeElementFromPage(pageIndex: Int, elementId: String) {
        val current = pages()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        current[pageIndex] = WorkspacePage(page.id, page.items.filterNot {
            it.type == WorkspaceItem.Type.BESPOKE && it.elementId == elementId
        })
        savePages(current)
    }

    fun addWidgetToPage(pageIndex: Int, widgetId: Int, spanX: Int = columns, spanY: Int = 2) {
        val current = pages()
        if (current.isEmpty()) return
        val idx = pageIndex.coerceIn(0, current.size - 1)
        val newItem = WorkspaceItem(
            id = "widget_$widgetId",
            type = WorkspaceItem.Type.WIDGET,
            cellX = 0,
            cellY = 0,
            spanX = spanX.coerceIn(1, columns),
            spanY = spanY.coerceIn(1, MAX_WIDGET_SPAN_Y),
            widgetId = widgetId
        )
        current[idx] = current[idx].placeFirstFree(newItem)
        savePages(current)
    }

    fun removeWidget(widgetId: Int) {
        val current = _workspacePages.value.orEmpty().map { page ->
            WorkspacePage(page.id, page.items.filterNot { it.type == WorkspaceItem.Type.WIDGET && it.widgetId == widgetId })
        }
        prefs.removeWidgetHeight(widgetId)
        savePages(current)
    }

    /**
     * Resizes an item in place (widget resize, enlarge/shrink). Items that end up underneath
     * the new area are pushed to the nearest free cells, like the stock resize frame does.
     */
    fun resizeItem(pageIndex: Int, itemId: String, spanX: Int, spanY: Int, transform: (WorkspaceItem) -> WorkspaceItem = { it }) {
        val current = pages()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val item = page.items.firstOrNull { it.id == itemId } ?: return
        val cols = columns
        val sx = spanX.coerceIn(1, cols)
        val sy = spanY.coerceAtLeast(1)
        val x = item.cellX.coerceAtMost(cols - sx)
        val resized = transform(item).copy(cellX = x, spanX = sx, spanY = sy)
        val kept = mutableListOf(resized)
        val displaced = mutableListOf<WorkspaceItem>()
        page.items.filterNot { it.id == itemId }.forEach { other ->
            if (other.overlaps(resized.cellX, resized.cellY, resized.spanX, resized.spanY)) displaced += other else kept += other
        }
        displaced.forEach { other ->
            val (c, r) = GridMath.nearestFree(kept, cols, other.cellX, other.cellY, other.spanX, other.spanY)
            kept += other.copy(cellX = c, cellY = r)
        }
        current[pageIndex] = WorkspacePage(page.id, kept)
        savePages(current)
    }

    /** Grows an element that does not fit its rows (never shrinks a user choice). */
    fun ensureMinRows(pageIndex: Int, itemId: String, rows: Int) {
        val item = itemById(pageIndex, itemId) ?: return
        if (rows <= item.spanY || rows > MAX_WIDGET_SPAN_Y) return
        resizeItem(pageIndex, itemId, item.spanX, rows)
    }

    // ---- Apps on the workspace ------------------------------------------------------------------

    fun pinToPage(pageIndex: Int, app: AppInfo) {
        if (isOnHome(app)) return
        val current = pages()
        if (current.isEmpty()) {
            addPage()
            return pinToPage(0, app)
        }
        val idx = pageIndex.coerceIn(0, current.size - 1)
        val item = WorkspaceItem(id = appItemId(app.key), type = WorkspaceItem.Type.APP, cellX = 0, cellY = 0, appKey = app.key)
        current[idx] = current[idx].placeFirstFree(item)
        savePages(current)
    }

    /**
     * Places [app] at ([col], [row]) on [pageIndex] (or the nearest free cell), removing any
     * other home copy first. Keeps a 2x2 app enlarged when it is just being moved.
     */
    fun placeApp(app: AppInfo, pageIndex: Int, col: Int, row: Int) {
        val current = pages()
        if (pageIndex !in current.indices) return
        val wasEnlarged = current.any { p -> p.items.any { it.type == WorkspaceItem.Type.MAX_APP && it.appKey == app.key } }
        val cleaned = current.withoutAppEverywhere(app.key)
        val span = if (wasEnlarged && columns >= 2) 2 else 1
        val item = WorkspaceItem(
            id = if (wasEnlarged) "max_${app.key}" else appItemId(app.key),
            type = if (wasEnlarged) WorkspaceItem.Type.MAX_APP else WorkspaceItem.Type.APP,
            cellX = col,
            cellY = row,
            spanX = span,
            spanY = span,
            appKey = app.key
        )
        cleaned[pageIndex] = cleaned[pageIndex].place(item, col, row)
        savePages(cleaned)
    }

    /** Moves any workspace item (app, folder, 2x2 tile, element, widget) to a cell on any page. */
    fun moveItem(fromPage: Int, itemId: String, toPage: Int, col: Int, row: Int): Boolean {
        val current = pages()
        if (fromPage !in current.indices || toPage !in current.indices) return false
        val item = current[fromPage].items.firstOrNull { it.id == itemId } ?: return false
        current[fromPage] = WorkspacePage(current[fromPage].id, current[fromPage].items.filterNot { it.id == itemId })
        if (item.type == WorkspaceItem.Type.BESPOKE) {
            // Elements are unique and full width.
            current[toPage] = WorkspacePage(current[toPage].id, current[toPage].items.filterNot {
                it.type == WorkspaceItem.Type.BESPOKE && it.elementId == item.elementId
            })
        }
        current[toPage] = current[toPage].place(item, if (item.type == WorkspaceItem.Type.BESPOKE) 0 else col, row)
        savePages(current)
        return true
    }

    fun moveAppBetweenPages(app: AppInfo, fromIndex: Int, toIndex: Int) {
        val current = pages()
        if (toIndex !in current.indices) return
        val owner = findAppItem(app.key)
        if (owner != null) {
            val (pageIdx, item) = owner
            val target = current[toIndex].items.filterNot { it.id == item.id }
            val (c, r) = GridMath.firstFree(target, columns, item.spanX, item.spanY)
            moveItem(pageIdx, item.id, toIndex, c, r)
        } else {
            val (c, r) = GridMath.firstFree(current[toIndex].items, columns, 1, 1)
            placeApp(app, toIndex, c, r)
        }
    }

    fun removeFromHome(app: AppInfo) {
        savePages(_workspacePages.value.orEmpty().withoutAppEverywhere(app.key))
    }

    fun removeFromPage(pageIndex: Int, appKey: String) {
        val current = pages()
        if (pageIndex !in current.indices) return
        current[pageIndex] = current[pageIndex].withoutApp(appKey)
        savePages(current)
    }

    fun removeItem(pageIndex: Int, itemId: String) {
        val current = pages()
        if (pageIndex !in current.indices) return
        current[pageIndex] = WorkspacePage(current[pageIndex].id, current[pageIndex].items.filterNot { it.id == itemId })
        savePages(current)
    }

    // ---- Max Icons (Enlarge 2x2) --------------------------------------------------------

    fun toggleEnlargeApp(pageIndex: Int, appKey: String) {
        val owner = findAppItem(appKey)
        if (owner == null) {
            // The app lives in a folder: pull it out as a 2x2 tile.
            val current = pages()
            if (pageIndex !in current.indices) return
            val cleaned = current.withoutAppEverywhere(appKey)
            val span = if (columns >= 2) 2 else 1
            val item = WorkspaceItem(
                id = "max_$appKey", type = WorkspaceItem.Type.MAX_APP,
                cellX = 0, cellY = 0, spanX = span, spanY = span, appKey = appKey
            )
            cleaned[pageIndex] = cleaned[pageIndex].placeFirstFree(item)
            savePages(cleaned)
            return
        }
        val (idx, item) = owner
        val willEnlarge = item.type != WorkspaceItem.Type.MAX_APP
        val span = if (willEnlarge && columns >= 2) 2 else 1
        // Swap the item for its other representation, then resize in place.
        val current = pages()
        val swapped = item.copy(
            id = if (willEnlarge) "max_$appKey" else appItemId(appKey),
            type = if (willEnlarge) WorkspaceItem.Type.MAX_APP else WorkspaceItem.Type.APP
        )
        current[idx] = WorkspacePage(current[idx].id, current[idx].items.map { if (it.id == item.id) swapped else it })
        _workspacePages.value = current
        resizeItem(idx, swapped.id, span, span)
    }

    fun isAppEnlarged(pageIndex: Int, appKey: String): Boolean =
        _workspacePages.value.orEmpty().any { page ->
            page.items.any { it.type == WorkspaceItem.Type.MAX_APP && it.appKey == appKey }
        }

    fun toggleEnlargeFolder(pageIndex: Int, folderId: String) {
        val page = _workspacePages.value?.getOrNull(pageIndex) ?: return
        val item = page.items.firstOrNull { it.type == WorkspaceItem.Type.FOLDER && it.folder?.id == folderId } ?: return
        val folder = item.folder ?: return
        val enlarge = !folder.isEnlarged
        val span = if (enlarge && columns >= 2) 2 else 1
        resizeItem(pageIndex, item.id, span, span) { it.copy(folder = folder.copy(isEnlarged = enlarge)) }
    }

    fun isFolderEnlarged(pageIndex: Int, folderId: String): Boolean =
        folderById(pageIndex, folderId)?.isEnlarged == true

    // ---- Workspace Folders -------------------------------------------------------------

    /** Turns an app into a new folder at the same position (stock "Add to new folder"). */
    fun createFolder(pageIndex: Int, name: String, initialAppKey: String): String? {
        val current = pages()
        if (pageIndex !in current.indices) return null
        val owner = findAppItem(initialAppKey)
        val (col, row) = if (owner != null && owner.first == pageIndex) owner.second.cellX to owner.second.cellY
        else GridMath.firstFree(current[pageIndex].items, columns, 1, 1)
        val cleaned = current.withoutAppEverywhere(initialAppKey)
        val folderId = "f_${UUID.randomUUID().toString().take(8)}"
        val folderItem = WorkspaceItem(
            id = "folder_$folderId",
            type = WorkspaceItem.Type.FOLDER,
            cellX = col,
            cellY = row,
            folder = WorkspaceFolder(id = folderId, name = name, appKeys = listOf(initialAppKey))
        )
        cleaned[pageIndex] = cleaned[pageIndex].place(folderItem, col, row)
        savePages(cleaned)
        return folderId
    }

    /** Dropping an app onto another app creates a folder with both, where the target was. */
    fun createFolderFromDrop(pageIndex: Int, targetAppKey: String, droppedAppKey: String): String? {
        if (targetAppKey == droppedAppKey) return null
        val current = pages()
        if (pageIndex !in current.indices) return null
        val target = current[pageIndex].items.firstOrNull {
            (it.type == WorkspaceItem.Type.APP || it.type == WorkspaceItem.Type.MAX_APP) && it.appKey == targetAppKey
        } ?: return null
        val cleaned = current.withoutAppEverywhere(droppedAppKey).let { list ->
            list.map { it.withoutApp(targetAppKey) }.toMutableList()
        }
        val folderId = "f_${UUID.randomUUID().toString().take(8)}"
        val folderItem = WorkspaceItem(
            id = "folder_$folderId",
            type = WorkspaceItem.Type.FOLDER,
            cellX = target.cellX,
            cellY = target.cellY,
            folder = WorkspaceFolder(id = folderId, name = "FOLDER", appKeys = listOf(targetAppKey, droppedAppKey))
        )
        cleaned[pageIndex] = cleaned[pageIndex].place(folderItem, target.cellX, target.cellY)
        savePages(cleaned)
        return folderId
    }

    fun addAppToFolder(pageIndex: Int, folderId: String, appKey: String) {
        val current = pages()
        if (pageIndex !in current.indices) return
        val folderItem = current[pageIndex].items.firstOrNull { it.type == WorkspaceItem.Type.FOLDER && it.folder?.id == folderId } ?: return
        if (appKey in folderItem.folder?.appKeys.orEmpty()) return
        val cleaned = current.withoutAppEverywhere(appKey)
        val page = cleaned[pageIndex]
        cleaned[pageIndex] = WorkspacePage(page.id, page.items.map { itm ->
            if (itm.id == folderItem.id && itm.folder != null) {
                itm.copy(folder = itm.folder.copy(appKeys = itm.folder.appKeys + appKey))
            } else itm
        })
        savePages(cleaned)
    }

    fun removeAppFromFolder(pageIndex: Int, folderId: String, appKey: String) {
        val current = pages()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val folderItem = page.items.firstOrNull { it.type == WorkspaceItem.Type.FOLDER && it.folder?.id == folderId } ?: return
        val updatedItems = page.items.mapNotNull { itm ->
            if (itm.id == folderItem.id) {
                collapseFolderAfterRemoval(itm, itm.folder?.appKeys.orEmpty().filterNot { it == appKey })
            } else itm
        }
        val appItem = WorkspaceItem(id = appItemId(appKey), type = WorkspaceItem.Type.APP, cellX = 0, cellY = 0, appKey = appKey)
        current[pageIndex] = WorkspacePage(page.id, updatedItems)
            .place(appItem, folderItem.cellX + folderItem.spanX, folderItem.cellY)
        savePages(current)
    }

    fun dissolveFolder(pageIndex: Int, folderId: String) {
        val current = pages()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val fItem = page.items.firstOrNull { it.type == WorkspaceItem.Type.FOLDER && it.folder?.id == folderId } ?: return
        val folder = fItem.folder ?: return
        var result = WorkspacePage(page.id, page.items.filterNot { it.id == fItem.id })
        folder.appKeys.forEach { aKey ->
            val appItem = WorkspaceItem(id = appItemId(aKey), type = WorkspaceItem.Type.APP, cellX = 0, cellY = 0, appKey = aKey)
            result = result.place(appItem, fItem.cellX, fItem.cellY)
        }
        current[pageIndex] = result
        savePages(current)
    }

    fun renameFolder(pageIndex: Int, folderId: String, newName: String) {
        val current = pages()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        current[pageIndex] = WorkspacePage(page.id, page.items.map { itm ->
            if (itm.type == WorkspaceItem.Type.FOLDER && itm.folder?.id == folderId) {
                itm.copy(folder = itm.folder.copy(name = newName.take(MAX_FOLDER_NAME)))
            } else itm
        })
        savePages(current)
    }

    private fun savePages(pages: List<WorkspacePage>) {
        val normalized = pages.map { normalizePage(it) }
        prefs.pages = normalized
        _workspacePages.value = normalized
        publishHomeApps(normalized)
    }

    // ---- Dock mutations ------------------------------------------------------------------

    fun addToDock(app: AppInfo): Boolean {
        if (isInDock(app)) return true
        if (isDockFull()) return false
        saveDock(_dockApps.value.orEmpty() + app)
        return true
    }

    /** Adds or moves [app] to [index] in the dock. Returns false when a new entry would overflow. */
    fun insertIntoDock(app: AppInfo, index: Int): Boolean {
        val current = _dockApps.value.orEmpty().toMutableList()
        val existing = current.indexOfFirst { it.key == app.key }
        if (existing == -1 && current.size >= MAX_DOCK) return false
        if (existing != -1) current.removeAt(existing)
        val target = index.coerceIn(0, current.size)
        current.add(target, app)
        saveDock(current)
        return true
    }

    fun moveDockItem(from: Int, to: Int): Boolean {
        val current = _dockApps.value.orEmpty().toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) return false
        val item = current.removeAt(from)
        current.add(to.coerceIn(0, current.size), item)
        saveDock(current)
        return true
    }

    fun removeFromDockAt(index: Int): Boolean {
        val current = _dockApps.value.orEmpty().toMutableList()
        if (index !in current.indices) return false
        current.removeAt(index)
        saveDock(current)
        return true
    }

    fun dockIndexOf(appKey: String): Int = _dockApps.value.orEmpty().indexOfFirst { it.key == appKey }

    fun removeFromDock(app: AppInfo) = saveDock(_dockApps.value.orEmpty().filterNot { it.key == app.key })

    private fun saveDock(apps: List<AppInfo>) {
        val trimmed = apps.take(MAX_DOCK)
        prefs.dockItems = trimmed.map { it.key }
        _dockApps.value = trimmed
    }

    fun hide(app: AppInfo) {
        prefs.hiddenApps = prefs.hiddenApps + app.key
        publish()
    }

    fun recordLaunch(app: AppInfo) {
        prefs.recents = listOf(app.key) + prefs.recents.filterNot { it == app.key }
        val byKey = installed.associateBy { it.key }
        val hidden = prefs.hiddenApps
        _recentApps.value = prefs.recents.mapNotNull { byKey[it] }.filter { it.key !in hidden }
    }

    fun resetLayout() {
        prefs.resetLayout()
        publish()
    }

    // ---- Defaults -----------------------------------------------------------------------

    private fun packageFor(intent: Intent): String? {
        val pm = getApplication<Application>().packageManager
        return try {
            val ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val pkg = ri?.activityInfo?.packageName
            if (pkg == null || pkg == "android") {
                pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.packageName
            } else pkg
        } catch (e: Exception) {
            null
        }
    }

    private fun defaultDock(apps: List<AppInfo>): List<AppInfo> {
        val wanted = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://nothing.tech")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        ).mapNotNull { packageFor(it) }
        val dock = wanted.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }.distinctBy { it.key }
        return if (dock.isNotEmpty()) dock else apps.take(4)
    }

    private fun defaultHome(apps: List<AppInfo>, dock: List<AppInfo>): List<AppInfo> {
        val dockKeys = dock.map { it.key }.toSet()
        val preferred = listOf(
            "com.google.android.apps.photos", "com.google.android.gm", "com.google.android.apps.maps",
            "com.google.android.youtube", "com.google.android.calendar", "com.google.android.deskclock",
            "com.android.vending", "com.spotify.music"
        )
        val picked = preferred.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg && it.key !in dockKeys } }
        val filler = apps.filter { it.key !in dockKeys && picked.none { p -> p.key == it.key } }
        return (picked + filler).take(DEFAULT_HOME_COUNT)
    }

    private fun defaultPage1(apps: List<AppInfo>, dock: List<AppInfo>, page0Keys: Set<String>): List<AppInfo> {
        val dockKeys = dock.map { it.key }.toSet()
        val secondary = listOf(
            "com.whatsapp", "com.google.android.apps.docs", "com.android.settings", "com.google.android.apps.nbu.files",
            "com.netflix.mediaclient", "com.slack", "com.discord"
        )
        val picked = secondary.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg && it.key !in dockKeys && it.key !in page0Keys } }
        val filler = apps.filter { it.key !in dockKeys && it.key !in page0Keys && picked.none { p -> p.key == it.key } }
        return (picked + filler).take(4)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            launcherApps?.unregisterCallback(packageCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun defaultElementRows(elementId: String): Int = when (elementId) {
        BespokeElement.CLOCK.id, BespokeElement.BATTERY_CALENDAR.id -> 2
        else -> 1
    }

    companion object {
        const val MAX_DOCK = 5
        const val MAX_WIDGET_SPAN_Y = 8
        private const val MAX_FOLDER_NAME = 32
        private const val DEFAULT_HOME_COUNT = 8
    }
}
