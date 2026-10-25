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
            val page0 = WorkspacePage(
                id = "page_0",
                appKeys = homeKeys,
                elements = listOf(BespokeElement.CLOCK.id, BespokeElement.BATTERY_CALENDAR.id),
                widgetIds = prefs.widgetIds
            )
            val page1Apps = defaultPage1(visible, dock, homeKeys.toSet()).map { it.key }
            val page1 = WorkspacePage(
                id = "page_1",
                appKeys = page1Apps,
                elements = listOf(BespokeElement.WEATHER.id, BespokeElement.QUICK_TOGGLES.id),
                widgetIds = emptyList()
            )
            savedPages = listOf(page0, page1)
            if (installed.isNotEmpty()) prefs.pages = savedPages
        }

        val sanitizedPages = savedPages.map { page ->
            page.copy(
                appKeys = if (installed.isEmpty()) page.appKeys else page.appKeys.map { key ->
                    if (key.isEmpty() || (key !in hidden && key in byKey)) key else ""
                }
            )
        }
        _workspacePages.value = sanitizedPages

        // All apps on the workspace
        val allHomeKeys = sanitizedPages.flatMap { it.appKeys }.filter { it.isNotEmpty() }.distinct()
        _homeApps.value = allHomeKeys.mapNotNull { byKey[it] }.filter { it.key !in hidden }
        _recentApps.value = prefs.recents.mapNotNull { byKey[it] }.filter { it.key !in hidden }
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
        val calculatedRows = ((page.appKeys.size + validCols - 1) / validCols).coerceAtLeast(minRows)
        val totalSlots = calculatedRows * validCols
        val result = ArrayList<AppInfo?>(totalSlots)
        for (i in 0 until totalSlots) {
            val key = page.appKeys.getOrNull(i)
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

    fun isOnHome(app: AppInfo): Boolean =
        _workspacePages.value.orEmpty().any { page -> page.appKeys.contains(app.key) }

    fun pageIndexForApp(app: AppInfo): Int =
        _workspacePages.value.orEmpty().indexOfFirst { page -> page.appKeys.contains(app.key) }

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
        // Consolidate any apps from the removed page into page 0
        if (removed.appKeys.isNotEmpty()) {
            val p0 = current[0]
            val merged = (p0.appKeys + removed.appKeys).filter { it.isNotEmpty() }.distinct()
            current[0] = p0.copy(appKeys = merged)
        }
        val updated = current.mapIndexed { idx, p -> p.copy(id = "page_$idx") }
        savePages(updated)
        return true
    }

    fun addElementToPage(pageIndex: Int, elementId: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        if (elementId in page.elements) return
        current[pageIndex] = page.copy(elements = page.elements + elementId)
        savePages(current)
    }

    fun removeElementFromPage(pageIndex: Int, elementId: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        current[pageIndex] = page.copy(elements = page.elements - elementId)
        savePages(current)
    }

    fun addWidgetToPage(pageIndex: Int, widgetId: Int) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        current[pageIndex] = page.copy(widgetIds = page.widgetIds + widgetId)
        savePages(current)
    }

    fun removeWidget(widgetId: Int) {
        val current = _workspacePages.value.orEmpty().map { page ->
            if (widgetId in page.widgetIds) {
                page.copy(widgetIds = page.widgetIds - widgetId)
            } else page
        }
        savePages(current)
    }

    fun pinToPage(pageIndex: Int, app: AppInfo) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (current.isEmpty()) addPage()
        val idx = pageIndex.coerceIn(0, current.size - 1)
        val page = current[idx]
        if (app.key in page.appKeys) return
        current[idx] = page.copy(appKeys = page.appKeys + app.key)
        savePages(current)
    }

    fun placeAppAtSlot(pageIndex: Int, app: AppInfo, targetSlot: Int) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (current.isEmpty()) addPage()
        val idx = pageIndex.coerceIn(0, current.size - 1)
        val page = current[idx]
        val list = page.appKeys.toMutableList()
        val existingIdx = list.indexOf(app.key)
        if (existingIdx != -1) {
            list[existingIdx] = ""
        }
        val safeSlot = targetSlot.coerceAtLeast(0)
        while (list.size <= safeSlot) {
            list.add("")
        }
        list[safeSlot] = app.key
        while (list.isNotEmpty() && list.last().isEmpty()) {
            list.removeAt(list.size - 1)
        }
        current[idx] = page.copy(appKeys = list)
        savePages(current)
    }

    fun moveAppToPageSlot(app: AppInfo, fromIndex: Int, toIndex: Int, targetSlot: Int? = null) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return

        if (fromIndex == toIndex) {
            if (targetSlot != null) {
                placeAppAtSlot(toIndex, app, targetSlot)
            }
            return
        }

        val fromPage = current[fromIndex]
        val toPage = current[toIndex]

        val fromList = fromPage.appKeys.map { if (it == app.key) "" else it }.toMutableList()
        while (fromList.isNotEmpty() && fromList.last().isEmpty()) {
            fromList.removeAt(fromList.size - 1)
        }
        current[fromIndex] = fromPage.copy(appKeys = fromList)

        val toList = toPage.appKeys.toMutableList()
        val slot = targetSlot ?: toList.indexOfFirst { it.isEmpty() }.takeIf { it != -1 } ?: toList.size
        while (toList.size <= slot) {
            toList.add("")
        }
        toList[slot] = app.key
        while (toList.isNotEmpty() && toList.last().isEmpty()) {
            toList.removeAt(toList.size - 1)
        }
        current[toIndex] = toPage.copy(appKeys = toList)
        savePages(current)
    }

    fun moveAppBetweenPages(app: AppInfo, fromIndex: Int, toIndex: Int) {
        moveAppToPageSlot(app, fromIndex, toIndex, null)
    }

    fun removeFromHome(app: AppInfo) {
        val current = _workspacePages.value.orEmpty().map { page ->
            page.copy(appKeys = page.appKeys.map { if (it == app.key) "" else it })
        }
        savePages(current)
    }

    fun removeFromPage(pageIndex: Int, appKey: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val updated = page.appKeys.map { if (it == appKey) "" else it }.toMutableList()
        while (updated.isNotEmpty() && updated.last().isEmpty()) {
            updated.removeAt(updated.size - 1)
        }
        current[pageIndex] = page.copy(appKeys = updated)
        savePages(current)
    }

    fun savePageApps(pageIndex: Int, apps: List<AppInfo>) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        current[pageIndex] = current[pageIndex].copy(appKeys = apps.map { it.key })
        savePages(current)
    }

    // ---- Max Icons (Enlarge 2x2) --------------------------------------------------------

    fun toggleEnlargeApp(pageIndex: Int, appKey: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val enlarged = if (appKey in page.enlargedApps) page.enlargedApps - appKey else page.enlargedApps + appKey
        current[pageIndex] = page.copy(enlargedApps = enlarged)
        savePages(current)
    }

    fun isAppEnlarged(pageIndex: Int, appKey: String): Boolean {
        val page = _workspacePages.value?.getOrNull(pageIndex) ?: return false
        return appKey in page.enlargedApps
    }

    // ---- Workspace Folders -------------------------------------------------------------

    fun createFolder(pageIndex: Int, name: String, initialAppKey: String): String? {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return null
        val page = current[pageIndex]
        val folderId = "f_${UUID.randomUUID().toString().take(8)}"
        val newFolder = WorkspaceFolder(id = folderId, name = name, appKeys = listOf(initialAppKey))
        val updatedKeys = page.appKeys.map { if (it == initialAppKey) "" else it }.toMutableList()
        while (updatedKeys.isNotEmpty() && updatedKeys.last().isEmpty()) {
            updatedKeys.removeAt(updatedKeys.size - 1)
        }
        current[pageIndex] = page.copy(
            appKeys = updatedKeys,
            folders = page.folders + newFolder
        )
        savePages(current)
        return folderId
    }

    fun createFolderFromDrop(pageIndex: Int, targetAppKey: String, droppedAppKey: String): String? {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return null
        val page = current[pageIndex]
        val folderId = "f_${UUID.randomUUID().toString().take(8)}"
        val newFolder = WorkspaceFolder(id = folderId, name = "FOLDER", appKeys = listOf(targetAppKey, droppedAppKey))
        val updatedKeys = page.appKeys.map { if (it == targetAppKey || it == droppedAppKey) "" else it }.toMutableList()
        while (updatedKeys.isNotEmpty() && updatedKeys.last().isEmpty()) {
            updatedKeys.removeAt(updatedKeys.size - 1)
        }
        current[pageIndex] = page.copy(
            appKeys = updatedKeys,
            folders = page.folders + newFolder
        )
        savePages(current)
        return folderId
    }

    fun addAppToFolder(pageIndex: Int, folderId: String, appKey: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val fIdx = page.folders.indexOfFirst { it.id == folderId }
        if (fIdx == -1) return
        val folder = page.folders[fIdx]
        val updatedFolder = folder.copy(appKeys = (folder.appKeys + appKey).distinct())
        val updatedFolders = page.folders.toMutableList().apply { set(fIdx, updatedFolder) }
        val updatedKeys = page.appKeys.map { if (it == appKey) "" else it }.toMutableList()
        while (updatedKeys.isNotEmpty() && updatedKeys.last().isEmpty()) {
            updatedKeys.removeAt(updatedKeys.size - 1)
        }
        current[pageIndex] = page.copy(
            appKeys = updatedKeys,
            folders = updatedFolders
        )
        savePages(current)
    }

    fun removeAppFromFolder(pageIndex: Int, folderId: String, appKey: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val fIdx = page.folders.indexOfFirst { it.id == folderId }
        if (fIdx == -1) return
        val folder = page.folders[fIdx]
        val updatedApps = folder.appKeys - appKey
        val updatedFolders = page.folders.toMutableList()
        if (updatedApps.isEmpty()) {
            updatedFolders.removeAt(fIdx)
        } else {
            updatedFolders[fIdx] = folder.copy(appKeys = updatedApps)
        }
        current[pageIndex] = page.copy(
            appKeys = (page.appKeys + appKey).distinct(),
            folders = updatedFolders
        )
        savePages(current)
    }

    fun dissolveFolder(pageIndex: Int, folderId: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val folder = page.folders.firstOrNull { it.id == folderId } ?: return
        current[pageIndex] = page.copy(
            appKeys = (page.appKeys + folder.appKeys).distinct(),
            folders = page.folders.filterNot { it.id == folderId }
        )
        savePages(current)
    }

    fun renameFolder(pageIndex: Int, folderId: String, newName: String) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (pageIndex !in current.indices) return
        val page = current[pageIndex]
        val fIdx = page.folders.indexOfFirst { it.id == folderId }
        if (fIdx == -1) return
        val updatedFolders = page.folders.toMutableList().apply {
            set(fIdx, get(fIdx).copy(name = newName))
        }
        current[pageIndex] = page.copy(folders = updatedFolders)
        savePages(current)
    }

    fun moveFolderBetweenPages(folder: WorkspaceFolder, fromIndex: Int, toIndex: Int) {
        val current = _workspacePages.value.orEmpty().toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val fromPage = current[fromIndex]
        val toPage = current[toIndex]
        current[fromIndex] = fromPage.copy(folders = fromPage.folders.filterNot { it.id == folder.id })
        current[toIndex] = toPage.copy(folders = toPage.folders + folder)
        savePages(current)
    }

    private fun savePages(pages: List<WorkspacePage>) {
        prefs.pages = pages
        _workspacePages.value = pages
        val byKey = installed.associateBy { it.key }
        val hidden = prefs.hiddenApps
        _homeApps.value = pages.flatMap { it.appKeys }.filter { it.isNotEmpty() }.distinct().mapNotNull { byKey[it] }.filter { it.key !in hidden }
    }

    // ---- Dock mutations ------------------------------------------------------------------

    fun addToDock(app: AppInfo): Boolean {
        if (isInDock(app)) return true
        if (isDockFull()) return false
        saveDock(_dockApps.value.orEmpty() + app)
        return true
    }

    fun removeFromDock(app: AppInfo) = saveDock(_dockApps.value.orEmpty().filterNot { it.key == app.key })

    private fun saveDock(apps: List<AppInfo>) {
        prefs.dockItems = apps.map { it.key }
        _dockApps.value = apps
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
