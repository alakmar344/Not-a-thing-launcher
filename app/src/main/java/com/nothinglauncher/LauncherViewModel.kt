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
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = loadApps()
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
        val user = Process.myUserHandle()
        val density = app.resources.displayMetrics.densityDpi
        val sizePx = app.resources.getDimensionPixelSize(R.dimen.icon_render_size)
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        return try {
            la.getActivityList(null, user)
                .filter { it.componentName.packageName != app.packageName }
                .map { info ->
                    val raw = try { info.getIcon(density) } catch (e: Exception) { null }
                        ?: app.packageManager.defaultActivityIcon
                    val flags = info.applicationInfo.flags
                    AppInfo(
                        label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                        packageName = info.componentName.packageName,
                        activityName = info.componentName.className,
                        icon = IconFactory.render(app.resources, raw, style, sizePx),
                        user = info.user,
                        isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    )
                }
                .sortedWith { a, b -> collator.compare(a.label, b.label) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
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

        // Enforce one home copy per app. appKeys wins over enlarged, enlarged wins over folders.
        val claimed = mutableSetOf<String>()
        fun claim(key: String): Boolean =
            key.isNotEmpty() && key !in hidden && (installed.isEmpty() || key in byKey) && claimed.add(key)

        val sanitizedPages = savedPages.map { page ->
            val pageItems = if (page.items.isNotEmpty()) {
                page.items
            } else {
                WorkspacePage.buildItemsFromLegacy(
                    prefs.gridColumns,
                    page.elements,
                    page.widgetIds,
                    page.enlargedApps,
                    page.folders,
                    page.appKeys
                )
            }
            val sanitizedItems = pageItems.mapNotNull { itm ->
                when (itm.type) {
                    WorkspaceItem.Type.APP, WorkspaceItem.Type.MAX_APP -> {
                        if (claim(itm.appKey)) itm else null
                    }
                    WorkspaceItem.Type.FOLDER -> {
                        val f = itm.folder ?: return@mapNotNull null
                        val validApps = f.appKeys.filter { claim(it) }
                        if (validApps.isEmpty()) null else itm.copy(folder = f.copy(appKeys = validApps))
                    }
                    else -> itm
                }
            }
            WorkspacePage(id = page.id, items = sanitizedItems)
        }
        _workspacePages.value = sanitizedPages

        // All apps on the workspace, including enlarged apps and folder contents.
        val allHomeKeys = sanitizedPages.flatMap { page ->
            page.items.flatMap { itm ->
                when (itm.type) {
                    WorkspaceItem.Type.APP, WorkspaceItem.Type.MAX_APP -> listOf(itm.appKey)
                    WorkspaceItem.Type.FOLDER -> itm.folder?.appKeys.orEmpty()
                    else -> emptyList()
                }
            }
        }.filter { it.isNotEmpty() }.distinct()
        _homeApps.value = allHomeKeys.mapNotNull { byKey[it] }.filter { it.key !in hidden }
        _recentApps.value = prefs.recents.mapNotNull { byKey[it] }.filter { it.key !in hidden }
    }

    /** All app keys referenced by a page, regardless of how they are presented. */
    private fun pageKeys(page: WorkspacePage): List<String> =
        page.items.flatMap { itm ->
            when (itm.type) {
                WorkspaceItem.Type.APP, WorkspaceItem.Type.MAX_APP -> listOf(itm.appKey)
                WorkspaceItem.Type.FOLDER -> itm.folder?.appKeys.orEmpty()
                else -> emptyList()
            }
        }.filter { it.isNotEmpty() }

    /** Removes [key] from every presentation on a single page. */
    private fun WorkspacePage.withoutApp(key: String): WorkspacePage {
        val updatedItems = items.mapNotNull { itm ->
            when {
                (itm.type == WorkspaceItem.Type.APP || itm.type == WorkspaceItem.Type.MAX_APP) && itm.appKey == key -> null
                itm.type == WorkspaceItem.Type.FOLDER && itm.folder != null -> {
                    val remaining = itm.folder.appKeys.filterNot { it == key }
                    if (remaining.isEmpty()) null else itm.copy(folder = itm.folder.copy(appKeys = remaining))
                }
                else -> itm
            }
        }
        return WorkspacePage(id, updatedItems)
    }

    private fun List<WorkspacePage>.withoutAppEverywhere(key: String): List<WorkspacePage> =
        map { it.withoutApp(key) }

    fun findFreeCell(page: WorkspacePage, spanX: Int = 1, spanY: Int = 1): Pair<Int, Int> {
        val cols = prefs.gridColumns.coerceAtLeast(1)
        val occupied = mutableSetOf<Pair<Int, Int>>()
        page.items.forEach { itm ->
            for (dx in 0 until itm.spanX) {
                for (dy in 0 until itm.spanY) {
                    occupied.add(Pair(itm.cellX + dx, itm.cellY + dy))
                }
            }
        }
        var r = 0
        while (true) {
            for (c in 0..cols - spanX) {
                var free = true
                for (dx in 0 until spanX) {
                    for (dy in 0 until spanY) {
                        if (Pair(c + dx, r + dy) in occupied) {
                            free = false
                            break
                        }
                    }
                    if (!free) break
                }
                if (free) return Pair(c, r)
            }
            r++
        }
    }

    // ---- Workspace & Page Mutations ------------------------------------------------------

    fun getAppsForPage(page: WorkspacePage): List<AppInfo> {
        val byKey = installed.associateBy { it.key }
        val hidden = prefs.hiddenApps
        return page.appKeys.filter { it.isNotEmpty() }.mapNotNull { byKey[it] }.filter { it.key !in hidden }
    }

    fun getSlotItemsForPage(page: WorkspacePage, columns: Int, minRows: Int = 4): List<AppInfo?> {
        val byKey = installed.associateBy { it.key }
        val hidden = prefs.hiddenApps
        val validCols = columns.coerceAtLeast(1)
        // Enlarged apps and folder contents are rendered by their own containers.
        val gridKeys = page.appKeys.filterNot { it in page.enlargedApps }
        val calculatedRows = ((gridKeys.size + validCols - 1) / validCols).coerceAtLeast(minRows)
        val totalSlots = calculatedRows * validCols
        val result = ArrayList<AppInfo?>(totalSlots)
        for (i in 0 until totalSlots) {
            val key = gridKeys.getOrNull(i)
            if (!key.isNullOrEmpty() && key !in hidden) {
                result.add(byKey[key])
            } else {
                result.add(null)
            }
        }
        return result
    }

    fun savePageSlots(pageIndex: Int, slots: List<AppInfo?>) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val lastIdx = slots.indexOfLast { it != null }
        val keys = if (lastIdx == -1) {
            emptyList()
        } else {
            slots.take(lastIdx + 1).map { it?.key ?: "" }
        }
        current[pageIndex] = current[pageIndex].copy(appKeys = keys)
        savePages(current)
    }

    fun isOnHome(app: AppInfo): Boolean = pageIndexForApp(app) >= 0

    fun pageIndexForApp(app: AppInfo): Int =
        _workspacePages.value.orEmpty().indexOfFirst { page -> app.key in pageKeys(page) }

    fun isInDock(app: AppInfo) = _dockApps.value.orEmpty().any { it.key == app.key }
    fun isDockFull() = _dockApps.value.orEmpty().size >= MAX_DOCK

    fun addPage(): Int {
        val current = _workspacePages.value.orEmpty().toMutableList()
        val newIndex = current.size
        val newPage = WorkspacePage(
            id = "page_$newIndex",
            appKeys = emptyList(),
            elements = emptyList(),
            widgetIds = emptyList()
        )
        current.add(newPage)
        savePages(current)
        return newIndex
    }

    fun removePage(index: Int): Boolean {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (current.size <= 1 || index !in current.indices) return false
        val removed = current.removeAt(index)
        // Never lose user content: merge every item from the removed page into the first page.
        val target = current[0]
        val folderApps = removed.folders.flatMap { it.appKeys }.toSet()
        val enlargedKeys = removed.enlargedApps.toSet()
        val mergedApps = (target.appKeys + removed.appKeys).filter { it.isNotEmpty() }.distinct()
            .filterNot { it in enlargedKeys || it in folderApps }
        val mergedEnlarged = (target.enlargedApps + removed.enlargedApps).distinct()
            .filterNot { it in mergedApps }
        val mergedFolders = (target.folders + removed.folders).distinctBy { it.id }
            .map { folder -> folder.copy(appKeys = folder.appKeys.filterNot { it in mergedApps || it in mergedEnlarged }) }
            .filter { it.appKeys.isNotEmpty() }
        current[0] = target.copy(
            appKeys = mergedApps,
            elements = (target.elements + removed.elements).distinct(),
            widgetIds = (target.widgetIds + removed.widgetIds).distinct(),
            enlargedApps = mergedEnlarged,
            folders = mergedFolders
        )
        val updated = current.mapIndexed { idx, p -> p.copy(id = "page_$idx") }
        savePages(updated)
        return true
    }

    fun addElementToPage(pageIndex: Int, elementId: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        if (page.items.any { it.type == WorkspaceItem.Type.BESPOKE && it.elementId == elementId }) return
        val cols = prefs.gridColumns.coerceAtLeast(1)
        val spanY = if (elementId == BespokeElement.CLOCK.id || elementId == BespokeElement.BATTERY_CALENDAR.id) 2 else 1
        val (c, r) = findFreeCell(page, cols, spanY)
        val newItem = WorkspaceItem(
            id = "bespoke_$elementId",
            type = WorkspaceItem.Type.BESPOKE,
            cellX = c,
            cellY = r,
            spanX = cols,
            spanY = spanY,
            elementId = elementId
        )
        current[pageIndex] = WorkspacePage(page.id, page.items + newItem)
        savePages(current)
    }

    fun removeElementFromPage(pageIndex: Int, elementId: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val updated = page.items.filterNot { it.type == WorkspaceItem.Type.BESPOKE && it.elementId == elementId }
        current[pageIndex] = WorkspacePage(page.id, updated)
        savePages(current)
    }

    /** Moves an element up ([delta] = -1) or down ([delta] = 1) inside a page's element stack. */
    fun moveElementOnPage(pageIndex: Int, elementId: String, delta: Int) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val idx = page.elements.indexOf(elementId)
        if (idx == -1) return
        val target = idx + delta
        if (target !in page.elements.indices) return
        val list = page.elements.toMutableList()
        list.removeAt(idx)
        list.add(target, elementId)
        current[pageIndex] = page.copy(elements = list)
        savePages(current)
    }

    /** Moves a widget up ([delta] = -1) or down ([delta] = 1) inside a page's widget stack. */
    fun moveWidgetOnPage(pageIndex: Int, widgetId: Int, delta: Int) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val idx = page.widgetIds.indexOf(widgetId)
        if (idx == -1) return
        val target = idx + delta
        if (target !in page.widgetIds.indices) return
        val list = page.widgetIds.toMutableList()
        list.removeAt(idx)
        list.add(target, widgetId)
        current[pageIndex] = page.copy(widgetIds = list)
        savePages(current)
    }

    fun addWidgetToPage(pageIndex: Int, widgetId: Int) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val cols = prefs.gridColumns.coerceAtLeast(1)
        val (c, r) = findFreeCell(page, cols, 2)
        val newItem = WorkspaceItem(
            id = "widget_$widgetId",
            type = WorkspaceItem.Type.WIDGET,
            cellX = c,
            cellY = r,
            spanX = cols,
            spanY = 2,
            widgetId = widgetId
        )
        current[pageIndex] = WorkspacePage(page.id, page.items + newItem)
        savePages(current)
    }

    fun removeWidget(widgetId: Int) {
        val current = _workspacePages.value.orEmpty().map { page ->
            val updated = page.items.filterNot { it.type == WorkspaceItem.Type.WIDGET && it.widgetId == widgetId }
            WorkspacePage(page.id, updated)
        }
        prefs.removeWidgetHeight(widgetId)
        savePages(current)
    }

    fun pinToPage(pageIndex: Int, app: AppInfo) {
        if (isOnHome(app)) return
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (current.isEmpty()) addPage()
        val idx = pageIndex.coerceIn(0, current.size - 1)
        val page = current[idx]
        val (c, r) = findFreeCell(page, 1, 1)
        val newItem = WorkspaceItem(
            id = "app_${app.key}",
            type = WorkspaceItem.Type.APP,
            cellX = c,
            cellY = r,
            spanX = 1,
            spanY = 1,
            appKey = app.key
        )
        current[idx] = WorkspacePage(page.id, page.items + newItem)
        savePages(current)
    }

    fun placeAppAtCell(pageIndex: Int, app: AppInfo, col: Int, row: Int) {
        if (_workspacePages.value.orEmpty().isEmpty()) addPage()
        val pages = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in pages.indices) return
        val cleaned = pages.map { it.withoutApp(app.key) }.toMutableList()
        val page = cleaned[pageIndex]
        val updated = page.items.filterNot { it.cellX == col && it.cellY == row } + WorkspaceItem(
            id = "app_${app.key}",
            type = WorkspaceItem.Type.APP,
            cellX = col,
            cellY = row,
            spanX = 1,
            spanY = 1,
            appKey = app.key
        )
        cleaned[pageIndex] = WorkspacePage(page.id, updated)
        savePages(cleaned)
    }

    fun placeAppAtSlot(pageIndex: Int, app: AppInfo, targetSlot: Int) {
        val cols = prefs.gridColumns.coerceAtLeast(1)
        val col = targetSlot % cols
        val row = targetSlot / cols
        placeAppAtCell(pageIndex, app, col, row)
    }

    fun moveAppToPageSlot(app: AppInfo, fromIndex: Int, toIndex: Int, targetSlot: Int? = null) {
        if (targetSlot != null) {
            val cols = prefs.gridColumns.coerceAtLeast(1)
            val col = targetSlot % cols
            val row = targetSlot / cols
            placeAppAtCell(toIndex, app, col, row)
        } else {
            val current = _workspacePages.value.orEmpty().toMutableList()
            if (fromIndex !in current.indices || toIndex !in current.indices) return
            val cleaned = current.map { it.withoutApp(app.key) }.toMutableList()
            val targetPage = cleaned[toIndex]
            val (c, r) = findFreeCell(targetPage, 1, 1)
            val updated = targetPage.items + WorkspaceItem(
                id = "app_${app.key}",
                type = WorkspaceItem.Type.APP,
                cellX = c,
                cellY = r,
                spanX = 1,
                spanY = 1,
                appKey = app.key
            )
            cleaned[toIndex] = WorkspacePage(targetPage.id, updated)
            savePages(current)
        }
    }

    fun moveAppBetweenPages(app: AppInfo, fromIndex: Int, toIndex: Int) {
        moveAppToPageSlot(app, fromIndex, toIndex, null)
    }

    fun removeFromHome(app: AppInfo) {
        savePages(_workspacePages.value.orEmpty().withoutAppEverywhere(app.key))
    }

    fun removeFromPage(pageIndex: Int, appKey: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        current[pageIndex] = current[pageIndex].withoutApp(appKey)
        savePages(current)
    }

    fun savePageApps(pageIndex: Int, apps: List<AppInfo>) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val enlarged = page.enlargedApps - apps.map { it.key }.toSet()
        val folders = page.folders.mapNotNull { folder ->
            val remaining = folder.appKeys - apps.map { it.key }.toSet()
            if (remaining.isEmpty()) null else folder.copy(appKeys = remaining)
        }
        current[pageIndex] = page.copy(appKeys = apps.map { it.key }, enlargedApps = enlarged, folders = folders)
        savePages(current)
    }

    // ---- Max Icons (Enlarge 2x2) --------------------------------------------------------

    fun toggleEnlargeApp(pageIndex: Int, appKey: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val updated = page.items.map { itm ->
            if (itm.appKey == appKey) {
                val willEnlarge = itm.type != WorkspaceItem.Type.MAX_APP
                val newType = if (willEnlarge) WorkspaceItem.Type.MAX_APP else WorkspaceItem.Type.APP
                val newSpan = if (willEnlarge) 2 else 1
                itm.copy(type = newType, spanX = newSpan, spanY = newSpan)
            } else itm
        }
        current[pageIndex] = WorkspacePage(page.id, updated)
        savePages(current)
    }

    fun isAppEnlarged(pageIndex: Int, appKey: String): Boolean {
        val page = _workspacePages.value?.getOrNull(pageIndex) ?: return false
        return page.items.any { it.type == WorkspaceItem.Type.MAX_APP && it.appKey == appKey }
    }

    fun toggleEnlargeFolder(pageIndex: Int, folderId: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val updated = page.items.map { itm ->
            if (itm.type == WorkspaceItem.Type.FOLDER && itm.folder?.id == folderId) {
                val newEnlarged = !(itm.folder.isEnlarged)
                val newSpan = if (newEnlarged) 2 else 1
                itm.copy(folder = itm.folder.copy(isEnlarged = newEnlarged), spanX = newSpan, spanY = newSpan)
            } else itm
        }
        current[pageIndex] = WorkspacePage(page.id, updated)
        savePages(current)
    }

    fun isFolderEnlarged(pageIndex: Int, folderId: String): Boolean {
        val page = _workspacePages.value?.getOrNull(pageIndex) ?: return false
        val itm = page.items.firstOrNull { it.type == WorkspaceItem.Type.FOLDER && it.folder?.id == folderId }
        return itm?.folder?.isEnlarged == true
    }

    // ---- Workspace Folders -------------------------------------------------------------

    fun createFolder(pageIndex: Int, name: String, initialAppKey: String): String? {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return null
        val page = current[pageIndex].withoutApp(initialAppKey)
        val (c, r) = findFreeCell(page, 1, 1)
        val folderId = "f_${UUID.randomUUID().toString().take(8)}"
        val newFolder = WorkspaceFolder(id = folderId, name = name, appKeys = listOf(initialAppKey))
        val folderItem = WorkspaceItem(
            id = "folder_$folderId",
            type = WorkspaceItem.Type.FOLDER,
            cellX = c,
            cellY = r,
            spanX = 1,
            spanY = 1,
            folder = newFolder
        )
        current[pageIndex] = WorkspacePage(page.id, page.items + folderItem)
        savePages(current)
        return folderId
    }

    fun createFolderFromDrop(
        pageIndex: Int,
        targetAppKey: String,
        droppedAppKey: String,
        targetCol: Int? = null,
        targetRow: Int? = null
    ): String? {
        if (pageIndex !in _workspacePages.value.orEmpty().indices) return null
        val current = _workspacePages.value.orEmpty()
            .map { it.withoutApp(droppedAppKey) }
            .toMutableList()
        val page = current[pageIndex]
        val existingItem = page.items.firstOrNull { it.appKey == targetAppKey }
        val c = targetCol ?: existingItem?.cellX ?: 0
        val r = targetRow ?: existingItem?.cellY ?: 0
        val cleanedPage = page.withoutApp(targetAppKey)
        val folderId = "f_${UUID.randomUUID().toString().take(8)}"
        val newFolder = WorkspaceFolder(
            id = folderId,
            name = "FOLDER",
            appKeys = listOf(targetAppKey, droppedAppKey).distinct()
        )
        val folderItem = WorkspaceItem(
            id = "folder_$folderId",
            type = WorkspaceItem.Type.FOLDER,
            cellX = c,
            cellY = r,
            spanX = 1,
            spanY = 1,
            folder = newFolder
        )
        val updated = cleanedPage.items.filterNot { it.cellX == c && it.cellY == r } + folderItem
        current[pageIndex] = WorkspacePage(cleanedPage.id, updated)
        savePages(current)
        return folderId
    }

    fun addAppToFolder(pageIndex: Int, folderId: String, appKey: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        current.forEachIndexed { i, p ->
            current[i] = p.withoutApp(appKey)
        }
        val target = current[pageIndex]
        val updated = target.items.map { itm ->
            if (itm.type == WorkspaceItem.Type.FOLDER && itm.folder?.id == folderId) {
                val updatedFolder = itm.folder.copy(appKeys = (itm.folder.appKeys + appKey).distinct())
                itm.copy(folder = updatedFolder)
            } else itm
        }
        current[pageIndex] = WorkspacePage(target.id, updated)
        savePages(current)
    }

    /** Finds the folder that currently owns [appKey] on [pageIndex], if any. */
    fun folderIdForApp(pageIndex: Int, appKey: String): String? =
        _workspacePages.value?.getOrNull(pageIndex)?.items
            ?.firstOrNull { it.type == WorkspaceItem.Type.FOLDER && appKey in (it.folder?.appKeys.orEmpty()) }
            ?.folder?.id

    fun removeAppFromFolder(pageIndex: Int, folderId: String, appKey: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val updatedItems = page.items.mapNotNull { itm ->
            if (itm.type == WorkspaceItem.Type.FOLDER && itm.folder?.id == folderId) {
                val rem = itm.folder.appKeys.filterNot { it == appKey }
                if (rem.isEmpty()) null else itm.copy(folder = itm.folder.copy(appKeys = rem))
            } else itm
        }.toMutableList()
        val (c, r) = findFreeCell(WorkspacePage(page.id, updatedItems), 1, 1)
        updatedItems += WorkspaceItem(
            id = "app_$appKey",
            type = WorkspaceItem.Type.APP,
            cellX = c,
            cellY = r,
            spanX = 1,
            spanY = 1,
            appKey = appKey
        )
        current[pageIndex] = WorkspacePage(page.id, updatedItems)
        savePages(current)
    }

    fun dissolveFolder(pageIndex: Int, folderId: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val fItem = page.items.firstOrNull { it.type == WorkspaceItem.Type.FOLDER && it.folder?.id == folderId } ?: return
        val folder = fItem.folder ?: return
        var baseItems = page.items.filterNot { it.id == fItem.id }
        folder.appKeys.forEach { aKey ->
            val (c, r) = findFreeCell(WorkspacePage(page.id, baseItems), 1, 1)
            baseItems = baseItems + WorkspaceItem(
                id = "app_$aKey",
                type = WorkspaceItem.Type.APP,
                cellX = c,
                cellY = r,
                spanX = 1,
                spanY = 1,
                appKey = aKey
            )
        }
        current[pageIndex] = WorkspacePage(page.id, baseItems)
        savePages(current)
    }

    fun renameFolder(pageIndex: Int, folderId: String, newName: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val updated = page.items.map { itm ->
            if (itm.type == WorkspaceItem.Type.FOLDER && itm.folder?.id == folderId) {
                itm.copy(folder = itm.folder.copy(name = newName))
            } else itm
        }
        current[pageIndex] = WorkspacePage(page.id, updated)
        savePages(current)
    }

    fun moveFolderBetweenPages(folder: WorkspaceFolder, fromIndex: Int, toIndex: Int) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val fromPage = current[fromIndex]
        val toPage = current[toIndex]
        val span = if (folder.isEnlarged) 2 else 1
        val (c, r) = findFreeCell(toPage, span, span)
        val folderItem = WorkspaceItem(
            id = "folder_${folder.id}",
            type = WorkspaceItem.Type.FOLDER,
            cellX = c,
            cellY = r,
            spanX = span,
            spanY = span,
            folder = folder
        )
        current[fromIndex] = WorkspacePage(fromPage.id, fromPage.items.filterNot { it.type == WorkspaceItem.Type.FOLDER && it.folder?.id == folder.id })
        current[toIndex] = WorkspacePage(toPage.id, toPage.items + folderItem)
        val folderKeys = folder.appKeys.toSet()
        // Keep the one-copy-per-app invariant when the destination already owns an icon.
        val target = toPage.copy(
            appKeys = toPage.appKeys.filterNot { it in folderKeys },
            enlargedApps = toPage.enlargedApps.filterNot { it in folderKeys },
            folders = toPage.folders.map { f ->
                f.copy(appKeys = f.appKeys.filterNot { it in folderKeys })
            }.filter { it.appKeys.isNotEmpty() } + folder
        )
        current[fromIndex] = fromPage.copy(folders = fromPage.folders.filterNot { it.id == folder.id })
        current[toIndex] = target
        savePages(current)
    }

    fun moveEnlargedAppToPage(app: AppInfo, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val fromPage = current[fromIndex].withoutApp(app.key)
        val toPage = current[toIndex].withoutApp(app.key)
        val (c, r) = findFreeCell(toPage, 2, 2)
        val newItem = WorkspaceItem(
            id = "max_${app.key}",
            type = WorkspaceItem.Type.MAX_APP,
            cellX = c,
            cellY = r,
            spanX = 2,
            spanY = 2,
            appKey = app.key
        )
        current[fromIndex] = fromPage
        current[toIndex] = WorkspacePage(toPage.id, toPage.items + newItem)
        savePages(current)
    }

    private fun savePages(pages: List<WorkspacePage>) {
        prefs.pages = pages
        _workspacePages.value = pages
        val byKey = installed.associateBy { it.key }
        val hidden = prefs.hiddenApps
        val homeKeys = pages.flatMap { page ->
            page.items.flatMap { itm ->
                when (itm.type) {
                    WorkspaceItem.Type.APP, WorkspaceItem.Type.MAX_APP -> listOf(itm.appKey)
                    WorkspaceItem.Type.FOLDER -> itm.folder?.appKeys.orEmpty()
                    else -> emptyList()
                }
            }
        }.filter { it.isNotEmpty() }.distinct()
        _homeApps.value = homeKeys.mapNotNull { byKey[it] }.filter { it.key !in hidden }
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

    companion object {
        const val MAX_DOCK = 5
        private const val DEFAULT_HOME_COUNT = 8
    }
}
