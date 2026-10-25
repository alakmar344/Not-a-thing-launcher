package com.nothinglauncher.model

import android.app.Application
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.nothinglauncher.AppInfo
import com.nothinglauncher.BespokeElement
import com.nothinglauncher.IconFactory
import com.nothinglauncher.LauncherPrefs
import com.nothinglauncher.R
import com.nothinglauncher.WidgetHostManager
import com.nothinglauncher.data.DefaultLayout
import com.nothinglauncher.data.LauncherDb
import com.nothinglauncher.data.SeededLayout
import com.nothinglauncher.data.V8Migration
import java.io.ByteArrayOutputStream
import java.text.Collator
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Single source of truth for installed apps and the home-screen layout.
 *
 * All state is read and mutated on the main thread. Disk I/O happens on a dedicated serial
 * writer thread so ordering is preserved and the UI never blocks. Listeners are notified after
 * every change; the UI reconciles its views against the model.
 */
class LauncherModel private constructor(private val app: Application) {

    interface Listener {
        fun onModelLoaded() {}
        fun onWorkspaceChanged() {}
        fun onAppsChanged() {}
    }

    /** Items removed in one user action; can be restored until [finalizeRemoval]. */
    class RemovedBatch(val items: List<ItemInfo>, val screens: List<Pair<Int, Long>> = emptyList()) {
        internal var finalized = false
    }

    val prefs = LauncherPrefs(app)
    private val db = LauncherDb(app)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "launcher-db").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "launcher-loader") }
    private val main = Handler(Looper.getMainLooper())
    private val launcherApps = app.getSystemService(LauncherApps::class.java)
    private val userManager = app.getSystemService(UserManager::class.java)
    private val listeners = CopyOnWriteArraySet<Listener>()

    var isLoaded = false
        private set

    /** Every launchable activity, including hidden ones. */
    var installedApps: List<AppInfo> = emptyList()
        private set
    /** Apps shown in the drawer (hidden apps filtered out), alphabetical. */
    var visibleApps: List<AppInfo> = emptyList()
        private set
    private var appsByKey: Map<String, AppInfo> = emptyMap()

    private val items = LinkedHashMap<Long, ItemInfo>()
    private val _screens = ArrayList<Long>()
    val screens: List<Long> get() = _screens

    private var maxItemId = 0L
    private var maxScreenId = 0L
    private var renderedStyle: Pair<LauncherPrefs.IconStyle, Boolean>? = null
    private var loadGeneration = 0
    private var appsQueryGeneration = 0
    private val pendingNewPackages = LinkedHashSet<Pair<String, UserHandle>>()

    val columns get() = prefs.gridColumns
    val rows get() = prefs.gridRows

    // ---- Lifecycle --------------------------------------------------------------------------

    init {
        AppInfo.mainUserSerial = serialOf(Process.myUserHandle())
        try {
            launcherApps?.registerCallback(packageCallback, main)
        } catch (e: Exception) {
            Log.e(TAG, "registerCallback failed", e)
        }
    }

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    /** Loads (or reloads) apps + layout. Safe to call repeatedly. */
    fun startLoading() {
        val gen = ++loadGeneration
        val style = currentStyle()
        val cols = columns
        val rws = rows
        loader.execute {
            val result = try {
                loadInBackground(style, cols, rws)
            } catch (e: Exception) {
                Log.e(TAG, "Load failed", e)
                null
            }
            main.post {
                if (gen != loadGeneration || result == null) return@post
                applyLoadResult(result, style)
            }
        }
    }

    private class LoadResult(
        val apps: List<AppInfo>,
        val screens: List<Long>,
        val items: List<ItemInfo>,
        val maxItemId: Long,
        val maxScreenId: Long
    )

    private fun loadInBackground(style: Pair<LauncherPrefs.IconStyle, Boolean>, cols: Int, rws: Int): LoadResult {
        val apps = queryApps(style)
        var dbItems = db.loadItems()
        var dbScreens = db.loadScreens()
        var maxId = dbItems.maxOfOrNull { it.id } ?: 0L
        var maxScreen = dbScreens.maxOrNull() ?: 0L

        val needsSeed = dbItems.isEmpty() && dbScreens.isEmpty()
        if (needsSeed && apps.isNotEmpty()) {
            // Fresh install, or first run after upgrading from the SharedPreferences layout (v8).
            val seeded: SeededLayout = if (prefs.layoutSchema < 9 && V8Migration.hasData(prefs)) {
                Log.i(TAG, "Migrating v8 layout to database")
                V8Migration.migrate(app, prefs, apps, cols, rws, { ++maxId }, { ++maxScreen })
            } else {
                DefaultLayout.build(app, apps, cols, rws, { ++maxId }, { ++maxScreen })
            }
            db.replaceAll(seeded.screens, seeded.items)
            prefs.layoutSchema = 9
            dbItems = db.loadItems()
            dbScreens = db.loadScreens()
        } else if (!needsSeed && prefs.layoutSchema < 9) {
            prefs.layoutSchema = 9
        }

        val byKey = apps.associateBy { it.key }
        val sanitizer = Sanitizer(apps, byKey, cols, rws, { ++maxId }, { ++maxScreen }, ::packageExists)
        val fixed = sanitizer.run(dbScreens, dbItems, canDropMissingApps = apps.isNotEmpty())
        if (fixed.changed) {
            db.replaceAll(fixed.screens, fixed.items)
        }
        resolveShortcuts(fixed.items, style)
        if (apps.isNotEmpty()) cleanupOrphanWidgets(fixed.items)
        return LoadResult(apps, fixed.screens, fixed.items, maxId, maxScreen)
    }

    private fun applyLoadResult(r: LoadResult, style: Pair<LauncherPrefs.IconStyle, Boolean>) {
        setApps(r.apps)
        renderedStyle = style
        items.clear()
        r.items.forEach { items[it.id] = it }
        _screens.clear()
        _screens.addAll(r.screens)
        maxItemId = maxOf(maxItemId, r.maxItemId)
        maxScreenId = maxOf(maxScreenId, r.maxScreenId)
        bindAppsToItems()
        isLoaded = true
        listeners.forEach { it.onModelLoaded() }
    }

    /** Re-applies settings that affect rendering or visibility. */
    fun onSettingsChanged() {
        if (!isLoaded) return
        if (renderedStyle != currentStyle()) {
            reloadApps()
        } else {
            setApps(installedApps)
            listeners.forEach { it.onAppsChanged() }
        }
    }

    private fun currentStyle(): Pair<LauncherPrefs.IconStyle, Boolean> {
        val light = when (prefs.themeMode) {
            LauncherPrefs.ThemeMode.LIGHT -> true
            LauncherPrefs.ThemeMode.DARK -> false
            LauncherPrefs.ThemeMode.SYSTEM ->
                (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
        }
        return prefs.iconStyle to light
    }

    val isLightIcons: Boolean get() = currentStyle().second

    // ---- Apps -------------------------------------------------------------------------------

    private fun reloadApps(onDone: (() -> Unit)? = null) {
        val gen = ++appsQueryGeneration
        val style = currentStyle()
        val shortcuts = items.values.filter { it.type == ItemType.SHORTCUT }.map { it.copy() }
        loader.execute {
            val apps = try { queryApps(style) } catch (e: Exception) { null }
            resolveShortcuts(shortcuts, style)
            main.post {
                if (gen != appsQueryGeneration || apps == null) return@post
                renderedStyle = style
                setApps(apps)
                shortcuts.forEach { s ->
                    items[s.id]?.let { live ->
                        live.icon = s.icon
                        live.label = s.label
                        live.disabled = s.disabled
                    }
                }
                if (!isLoaded) return@post
                bindAppsToItems()
                onDone?.invoke()
                listeners.forEach { it.onAppsChanged() }
                listeners.forEach { it.onWorkspaceChanged() }
            }
        }
    }

    private fun setApps(apps: List<AppInfo>) {
        installedApps = apps
        appsByKey = apps.associateBy { it.key }
        val hidden = prefs.hiddenApps
        visibleApps = apps.filter { it.key !in hidden }
    }

    fun findApp(key: String?): AppInfo? = key?.let { appsByKey[it] }

    val recentApps: List<AppInfo>
        get() {
            val hidden = prefs.hiddenApps
            return prefs.recents.mapNotNull { appsByKey[it] }.filter { it.key !in hidden }
        }

    fun recordLaunch(app: AppInfo) {
        prefs.recents = listOf(app.key) + prefs.recents.filterNot { it == app.key }
        listeners.forEach { it.onAppsChanged() }
    }

    fun hideApp(app: AppInfo) {
        prefs.hiddenApps = prefs.hiddenApps + app.key
        setApps(installedApps)
        listeners.forEach { it.onAppsChanged() }
    }

    fun unhideApp(key: String) {
        prefs.hiddenApps = prefs.hiddenApps - key
        setApps(installedApps)
        listeners.forEach { it.onAppsChanged() }
    }

    private fun queryApps(style: Pair<LauncherPrefs.IconStyle, Boolean>): List<AppInfo> {
        val la = launcherApps ?: return emptyList()
        val res = app.resources
        val density = res.displayMetrics.densityDpi
        val sizePx = res.getDimensionPixelSize(R.dimen.icon_render_size)
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val profiles = try { userManager?.userProfiles } catch (e: Exception) { null } ?: listOf(Process.myUserHandle())
        val out = ArrayList<AppInfo>()
        for (user in profiles) {
            val serial = serialOf(user)
            val isMain = user == Process.myUserHandle()
            val list = try { la.getActivityList(null, user) } catch (e: Exception) { emptyList() }
            for (info in list) {
                if (info.componentName.packageName == app.packageName && isMain) continue
                try {
                    val raw: Drawable = try { info.getIcon(density) } catch (e: Exception) { null }
                        ?: app.packageManager.defaultActivityIcon
                    var icon = IconFactory.render(res, raw, style.first, sizePx, style.second)
                    if (!isMain) icon = app.packageManager.getUserBadgedIcon(icon, user)
                    val flags = info.applicationInfo.flags
                    out += AppInfo(
                        label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                        packageName = info.componentName.packageName,
                        activityName = info.componentName.className,
                        icon = icon,
                        user = user,
                        isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
                        userSerial = serial,
                        firstInstallTime = info.firstInstallTime
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping ${info.componentName}", e)
                }
            }
        }
        out.sortWith { a, b ->
            val c = collator.compare(a.label, b.label)
            if (c != 0) c else a.userSerial.compareTo(b.userSerial)
        }
        return out
    }

    private fun bindAppsToItems() {
        items.values.forEach { item ->
            if (item.type == ItemType.APP) {
                val a = findApp(item.appKey)
                item.app = a
                item.disabled = a == null
                if (a != null) {
                    item.icon = a.icon
                    item.label = item.title?.takeIf { it.isNotBlank() } ?: a.label
                } else if (item.icon == null) {
                    item.icon = placeholderIcon(item.component?.packageName)
                    item.label = item.title ?: item.component?.packageName
                }
            }
        }
    }

    private fun placeholderIcon(pkg: String?): Drawable {
        val style = currentStyle()
        val raw = try {
            pkg?.let { app.packageManager.getApplicationIcon(it) }
        } catch (e: Exception) { null } ?: app.packageManager.defaultActivityIcon
        return IconFactory.render(app.resources, raw, style.first, app.resources.getDimensionPixelSize(R.dimen.icon_render_size), style.second)
    }

    // ---- Users ------------------------------------------------------------------------------

    fun serialOf(user: UserHandle?): Long = try {
        userManager?.getSerialNumberForUser(user ?: Process.myUserHandle()) ?: 0L
    } catch (e: Exception) { 0L }

    fun userFor(serial: Long): UserHandle? = try {
        userManager?.getUserForSerialNumber(serial)
    } catch (e: Exception) { null }

    // ---- Queries ----------------------------------------------------------------------------

    fun getItem(id: Long): ItemInfo? = items[id]

    val allItems: Collection<ItemInfo> get() = items.values

    fun desktopItems(screenId: Long): List<ItemInfo> =
        items.values.filter { it.container == Containers.DESKTOP && it.screenId == screenId }

    fun hotseatItems(): List<ItemInfo> = items.values.filter { it.container == Containers.HOTSEAT }

    fun folderItems(folderId: Long): List<ItemInfo> =
        items.values.filter { it.container == folderId }.sortedWith(compareBy({ it.rank }, { it.id }))

    fun isOnHome(app: AppInfo): Boolean = items.values.any { it.type == ItemType.APP && it.appKey == app.key }

    fun screenIndex(screenId: Long) = _screens.indexOf(screenId)

    /** Occupancy of a page or the dock, optionally ignoring some items (e.g. the one dragged). */
    fun occupancy(container: Long, screenId: Long, ignore: Set<Long> = emptySet()): GridOccupancy {
        val g = if (container == Containers.HOTSEAT) GridOccupancy(columns, 1) else GridOccupancy(columns, rows)
        items.values.forEach {
            if (it.container == container && (container == Containers.HOTSEAT || it.screenId == screenId) && it.id !in ignore) {
                g.mark(it.cellX, it.cellY, it.spanX, it.spanY)
            }
        }
        return g
    }

    // ---- Screens ----------------------------------------------------------------------------

    fun addScreen(index: Int = _screens.size): Long {
        val id = ++maxScreenId
        _screens.add(index.coerceIn(0, _screens.size), id)
        persistScreens()
        notifyWorkspace()
        return id
    }

    fun moveScreen(from: Int, to: Int) {
        if (from !in _screens.indices || to !in _screens.indices || from == to) return
        val id = _screens.removeAt(from)
        _screens.add(to, id)
        val def = prefs.defaultPage
        prefs.defaultPage = when (def) {
            from -> to
            in minOf(from, to)..maxOf(from, to) -> if (from < to) def - 1 else def + 1
            else -> def
        }
        persistScreens()
        notifyWorkspace()
    }

    /** Removes a page and everything on it (undoable through the returned batch). */
    fun removeScreen(screenId: Long): RemovedBatch? {
        val index = _screens.indexOf(screenId)
        if (index < 0 || _screens.size <= 1) return null
        val onScreen = desktopItems(screenId)
        val batch = collectForRemoval(onScreen, screenEntry = index to screenId)
        batch.items.forEach { items.remove(it.id) }
        _screens.removeAt(index)
        val def = prefs.defaultPage
        if (def >= _screens.size || def > index) prefs.defaultPage = (def - 1).coerceAtLeast(0)
        val ids = batch.items.map { it.id }
        val screensSnapshot = _screens.toList()
        writer.execute {
            db.delete(ids)
            db.saveScreens(screensSnapshot)
        }
        notifyWorkspace()
        return batch
    }

    /** Drops trailing pages that hold nothing (used after a drag created a spare page). */
    fun removeEmptyScreen(screenId: Long) {
        if (_screens.size <= 1 || desktopItems(screenId).isNotEmpty()) return
        val index = _screens.indexOf(screenId)
        if (index < 0) return
        _screens.removeAt(index)
        if (prefs.defaultPage >= _screens.size) prefs.defaultPage = _screens.size - 1
        persistScreens()
        notifyWorkspace()
    }

    private fun persistScreens() {
        val snapshot = _screens.toList()
        writer.execute { db.saveScreens(snapshot) }
    }

    // ---- Item mutations ---------------------------------------------------------------------

    /** Adds a new item or moves an existing one, then persists. */
    fun placeItem(
        item: ItemInfo,
        container: Long,
        screenId: Long,
        x: Int,
        y: Int,
        spanX: Int = item.spanX,
        spanY: Int = item.spanY,
        notify: Boolean = true
    ): ItemInfo {
        val wasFolder = item.container.takeIf { it > 0 }
        if (item.id == ItemInfo.NO_ID) item.id = ++maxItemId
        item.container = container
        item.screenId = if (container == Containers.DESKTOP) screenId else -1
        item.cellX = x
        item.cellY = y
        item.spanX = spanX
        item.spanY = spanY
        if (container == Containers.HOTSEAT || container > 0) {
            item.spanX = 1
            item.spanY = 1
        }
        items[item.id] = item
        if (item.type == ItemType.APP && item.app == null) bindAppsToItems()
        persist(item)
        if (wasFolder != null && wasFolder != container) {
            normalizeFolderRanks(wasFolder)
        }
        if (notify) notifyWorkspace()
        return item
    }

    /** Persists items whose positions the UI already updated (drag reorder commit). */
    fun commitItems(changed: Collection<ItemInfo>, notify: Boolean = true) {
        changed.forEach { items[it.id] = it }
        val snapshots = changed.map { it.copy() }
        writer.execute { db.upsertAll(snapshots) }
        if (notify) notifyWorkspace()
    }

    fun updateItem(item: ItemInfo) {
        if (item.id == ItemInfo.NO_ID) return
        items[item.id] = item
        persist(item)
        notifyWorkspace()
    }

    private fun persist(item: ItemInfo) {
        val snapshot = item.copy()
        writer.execute { db.upsert(snapshot) }
    }

    /** Removes items (and folder contents); everything is restorable until finalized. */
    fun removeItems(toRemove: Collection<ItemInfo>): RemovedBatch {
        val batch = collectForRemoval(toRemove, null)
        batch.items.forEach { items.remove(it.id) }
        val ids = batch.items.map { it.id }
        writer.execute { db.delete(ids) }
        // Removing the last-but-one item of a folder turns the folder back into an icon.
        toRemove.mapNotNull { it.container.takeIf { c -> c > 0 } }.distinct().forEach { checkFolder(it, notify = false) }
        notifyWorkspace()
        return batch
    }

    private fun collectForRemoval(roots: Collection<ItemInfo>, screenEntry: Pair<Int, Long>?): RemovedBatch {
        val all = LinkedHashMap<Long, ItemInfo>()
        roots.forEach { r ->
            all[r.id] = r.copy()
            if (r.type == ItemType.FOLDER) folderItems(r.id).forEach { all[it.id] = it.copy() }
        }
        return RemovedBatch(all.values.toList(), listOfNotNull(screenEntry))
    }

    /** Undo for [removeItems] / [removeScreen]. */
    fun restore(batch: RemovedBatch) {
        if (batch.finalized) return
        batch.screens.forEach { (index, id) ->
            if (id !in _screens) _screens.add(index.coerceIn(0, _screens.size), id)
        }
        val restored = ArrayList<ItemInfo>()
        batch.items.filter { it.container <= 0 }.forEach { item ->
            if (item.container == Containers.DESKTOP && item.screenId !in _screens) {
                item.screenId = _screens.lastOrNull() ?: addScreen()
            }
            val occ = occupancy(item.container, item.screenId)
            if (!occ.isRegionVacant(item.cellX, item.cellY, item.spanX, item.spanY)) {
                val spot = findSpace(item.spanX, item.spanY, screenIndex(item.screenId).coerceAtLeast(0))
                item.container = Containers.DESKTOP
                item.screenId = spot.first
                item.cellX = spot.second.x
                item.cellY = spot.second.y
            }
            items[item.id] = item
            restored += item
        }
        batch.items.filter { it.container > 0 }.forEach { item ->
            if (items[item.container] != null) {
                items[item.id] = item
                restored += item
            }
        }
        bindAppsToItems()
        val snapshots = restored.map { it.copy() }
        val screensSnapshot = _screens.toList()
        writer.execute {
            db.upsertAll(snapshots)
            db.saveScreens(screensSnapshot)
        }
        batch.finalized = true
        notifyWorkspace()
    }

    /** Releases system resources (widget ids, pinned shortcuts) of removed items. */
    fun finalizeRemoval(batch: RemovedBatch) {
        if (batch.finalized) return
        batch.finalized = true
        val host = AppWidgetHost(app, WidgetHostManager.HOST_ID)
        batch.items.forEach { item ->
            when (item.type) {
                ItemType.WIDGET -> if (item.appWidgetId > 0 && items.values.none { it.appWidgetId == item.appWidgetId }) {
                    try { host.deleteAppWidgetId(item.appWidgetId) } catch (e: Exception) { Log.w(TAG, "deleteAppWidgetId", e) }
                }
                ItemType.SHORTCUT -> syncPinnedShortcuts(item.packageName, item.profileSerial)
                ItemType.ELEMENT -> com.nothinglauncher.elements.ElementStore.clear(app, item.id)
            }
        }
    }

    // ---- Folders ----------------------------------------------------------------------------

    /** Turns [target] into a folder containing it and [dropped]. Returns the folder. */
    fun createFolder(target: ItemInfo, dropped: ItemInfo, title: String): ItemInfo {
        val folder = ItemInfo(
            type = ItemType.FOLDER,
            title = title,
            container = target.container,
            screenId = target.screenId,
            cellX = target.cellX,
            cellY = target.cellY,
            spanX = target.spanX,
            spanY = target.spanY
        )
        folder.id = ++maxItemId
        items[folder.id] = folder
        val sourceFolder = dropped.container.takeIf { it > 0 && it != folder.id }
        listOf(target, dropped).forEachIndexed { i, child ->
            if (child.id == ItemInfo.NO_ID) child.id = ++maxItemId
            child.container = folder.id
            child.screenId = -1
            child.cellX = -1
            child.cellY = -1
            child.spanX = 1
            child.spanY = 1
            child.rank = i
            items[child.id] = child
        }
        bindAppsToItems()
        commitItems(listOf(folder, target, dropped), notify = false)
        sourceFolder?.let { checkFolder(it, notify = false) }
        notifyWorkspace()
        return folder
    }

    fun addToFolder(folder: ItemInfo, item: ItemInfo, rank: Int = Int.MAX_VALUE) {
        val sourceFolder = item.container.takeIf { it > 0 && it != folder.id }
        val contents = folderItems(folder.id).filter { it.id != item.id }.toMutableList()
        contents.add(rank.coerceIn(0, contents.size), item)
        if (item.id == ItemInfo.NO_ID) item.id = ++maxItemId
        item.container = folder.id
        item.screenId = -1
        item.cellX = -1
        item.cellY = -1
        item.spanX = 1
        item.spanY = 1
        contents.forEachIndexed { i, it -> it.rank = i; items[it.id] = it }
        bindAppsToItems()
        commitItems(contents, notify = false)
        sourceFolder?.let { checkFolder(it, notify = false) }
        notifyWorkspace()
    }

    fun reorderFolder(folderId: Long, ordered: List<ItemInfo>) {
        ordered.forEachIndexed { i, it -> it.rank = i }
        commitItems(ordered)
    }

    fun renameFolder(folder: ItemInfo, name: String) {
        folder.title = name.trim().ifEmpty { app.getString(R.string.folder_default_name) }
        updateItem(folder)
    }

    private fun normalizeFolderRanks(folderId: Long) {
        val contents = folderItems(folderId)
        contents.forEachIndexed { i, it -> it.rank = i }
        commitItems(contents, notify = false)
        checkFolder(folderId, notify = false)
    }

    /**
     * Folders with a single item collapse into that item; empty folders disappear.
     * Returns the item that replaced the folder, if any.
     */
    fun checkFolder(folderId: Long, notify: Boolean = true): ItemInfo? {
        val folder = items[folderId] ?: return null
        if (folder.type != ItemType.FOLDER) return null
        val contents = folderItems(folderId)
        if (contents.size >= 2) return null
        items.remove(folderId)
        val deleted = arrayListOf(folderId)
        var replacement: ItemInfo? = null
        if (contents.size == 1) {
            val only = contents[0]
            only.container = folder.container
            only.screenId = folder.screenId
            only.cellX = folder.cellX
            only.cellY = folder.cellY
            only.spanX = if (only.isIcon) folder.spanX else 1
            only.spanY = if (only.isIcon) folder.spanY else 1
            only.rank = 0
            replacement = only
        }
        val snapshot = replacement?.copy()
        writer.execute {
            db.delete(deleted)
            snapshot?.let { db.upsert(it) }
        }
        if (notify) notifyWorkspace()
        return replacement
    }

    // ---- Placement --------------------------------------------------------------------------

    /**
     * Finds room for a [spanX]×[spanY] item, starting at page [startIndex] and wrapping
     * around; appends a new page when every page is full.
     */
    fun findSpace(spanX: Int, spanY: Int, startIndex: Int = 0): Pair<Long, CellRect> {
        val sx = spanX.coerceIn(1, columns)
        val sy = spanY.coerceIn(1, rows)
        if (_screens.isEmpty()) addScreen()
        val order = _screens.indices.map { (it + startIndex.coerceIn(0, _screens.size - 1)) % _screens.size }
        for (i in order) {
            val s = _screens[i]
            occupancy(Containers.DESKTOP, s).findVacantCell(sx, sy)?.let { return s to it }
        }
        val s = addScreen()
        return s to CellRect(0, 0, sx, sy)
    }

    fun addAppToHome(app: AppInfo, startIndex: Int): ItemInfo {
        val (screen, cell) = findSpace(1, 1, startIndex)
        return placeItem(ItemInfo.forApp(app), Containers.DESKTOP, screen, cell.x, cell.y)
    }

    fun addElement(element: BespokeElement, startIndex: Int): ItemInfo {
        val sx = element.spanX.coerceAtMost(columns)
        val sy = element.spanY.coerceAtMost(rows)
        val (screen, cell) = findSpace(sx, sy, startIndex)
        val item = ItemInfo(type = ItemType.ELEMENT, elementId = element.id)
        return placeItem(item, Containers.DESKTOP, screen, cell.x, cell.y, sx, sy)
    }

    fun addWidget(appWidgetId: Int, provider: ComponentName, profileSerial: Long, spanX: Int, spanY: Int, startIndex: Int, cell: Pair<Long, CellRect>? = null): ItemInfo {
        val target = cell ?: findSpace(spanX, spanY, startIndex)
        val item = ItemInfo(
            type = ItemType.WIDGET,
            appWidgetId = appWidgetId,
            component = provider,
            packageName = provider.packageName,
            profileSerial = profileSerial
        )
        return placeItem(item, Containers.DESKTOP, target.first, target.second.x, target.second.y, target.second.w, target.second.h)
    }

    /** Builds (but does not place) an item for a deep shortcut. */
    fun shortcutItem(si: ShortcutInfo): ItemInfo {
        val style = currentStyle()
        val raw = try {
            launcherApps?.getShortcutIconDrawable(si, app.resources.displayMetrics.densityDpi)
        } catch (e: Exception) { null }
        val blob = raw?.let { toPng(it) }
        val item = ItemInfo(
            type = ItemType.SHORTCUT,
            packageName = si.`package`,
            shortcutId = si.id,
            profileSerial = serialOf(si.userHandle),
            title = (si.shortLabel ?: si.longLabel ?: "").toString(),
            iconBlob = blob
        )
        item.label = item.title
        item.icon = renderBlob(blob, style) ?: placeholderIcon(si.`package`)
        return item
    }

    fun addShortcut(si: ShortcutInfo, startIndex: Int): ItemInfo {
        val item = shortcutItem(si)
        val (screen, cell) = findSpace(1, 1, startIndex)
        placeItem(item, Containers.DESKTOP, screen, cell.x, cell.y)
        syncPinnedShortcuts(item.packageName, item.profileSerial)
        return item
    }

    /** Keeps the system's pinned shortcut set in sync with what is on the home screen. */
    fun syncPinnedShortcuts(pkg: String?, serial: Long) {
        pkg ?: return
        val la = launcherApps ?: return
        val user = userFor(serial) ?: return
        val ids = items.values.filter { it.type == ItemType.SHORTCUT && it.packageName == pkg && it.profileSerial == serial }
            .mapNotNull { it.shortcutId }.distinct()
        try {
            if (la.hasShortcutHostPermission()) la.pinShortcuts(pkg, ids, user)
        } catch (e: Exception) {
            Log.w(TAG, "pinShortcuts failed for $pkg", e)
        }
    }

    private fun resolveShortcuts(list: List<ItemInfo>, style: Pair<LauncherPrefs.IconStyle, Boolean>) {
        val shortcuts = list.filter { it.type == ItemType.SHORTCUT }
        if (shortcuts.isEmpty()) return
        val la = launcherApps
        val canQuery = try { la?.hasShortcutHostPermission() == true } catch (e: Exception) { false }
        shortcuts.groupBy { it.packageName to it.profileSerial }.forEach { (key, group) ->
            val (pkg, serial) = key
            val user = userFor(serial)
            val infos: Map<String, ShortcutInfo> = if (canQuery && pkg != null && user != null) {
                try {
                    val q = LauncherApps.ShortcutQuery().setPackage(pkg)
                        .setShortcutIds(group.mapNotNull { it.shortcutId })
                        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
                    la!!.getShortcuts(q, user).orEmpty().associateBy { it.id }
                } catch (e: Exception) { emptyMap() }
            } else emptyMap()
            group.forEach { item ->
                val si = infos[item.shortcutId]
                if (si != null) {
                    item.label = (si.shortLabel ?: si.longLabel ?: item.title ?: "").toString()
                    item.disabled = !si.isEnabled
                    if (item.title.isNullOrBlank()) item.title = item.label?.toString()
                } else {
                    item.label = item.title
                    item.disabled = canQuery && pkg != null && user != null && !isPackageAvailable(pkg, user)
                }
                item.icon = renderBlob(item.iconBlob, style) ?: placeholderIcon(pkg)
            }
        }
    }

    /** True when [pkg] is installed for the profile with [serial] (enabled or not). */
    private fun packageExists(pkg: String, serial: Long): Boolean {
        val user = userFor(serial) ?: return false
        return try {
            launcherApps?.isPackageEnabled(pkg, user) == true ||
                launcherApps?.getApplicationInfo(pkg, 0, user) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            true // Unknown state: never drop user data on a transient failure.
        }
    }

    private fun isPackageAvailable(pkg: String, user: UserHandle): Boolean = try {
        launcherApps?.isPackageEnabled(pkg, user) == true
    } catch (e: Exception) { false }

    private fun renderBlob(blob: ByteArray?, style: Pair<LauncherPrefs.IconStyle, Boolean>): Drawable? {
        blob ?: return null
        val bmp = try { BitmapFactory.decodeByteArray(blob, 0, blob.size) } catch (e: Exception) { null } ?: return null
        val size = app.resources.getDimensionPixelSize(R.dimen.icon_render_size)
        return IconFactory.render(app.resources, BitmapDrawable(app.resources, bmp), style.first, size, style.second)
    }

    private fun toPng(d: Drawable): ByteArray? = try {
        val size = (app.resources.displayMetrics.density * 72).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    } catch (e: Exception) { null }

    // ---- Grid & reset -------------------------------------------------------------------------

    /** Re-validates every position after the grid size changed. */
    fun applyGridChange() {
        if (!isLoaded) return
        var nextId = maxItemId
        var nextScreen = maxScreenId
        val sanitizer = Sanitizer(installedApps, appsByKey, columns, rows, { ++nextId }, { ++nextScreen })
        val fixed = sanitizer.run(_screens.toList(), items.values.map { it }, canDropMissingApps = false)
        maxItemId = nextId
        maxScreenId = nextScreen
        if (fixed.changed) {
            items.clear()
            fixed.items.forEach { items[it.id] = it }
            _screens.clear()
            _screens.addAll(fixed.screens)
            val s = fixed.screens.toList()
            val i = fixed.items.map { it.copy() }
            writer.execute { db.replaceAll(s, i) }
        }
        notifyWorkspace()
    }

    /** Replaces the layout wholesale (reset to defaults or restore from a backup). */
    fun replaceLayout(builder: (nextId: () -> Long, nextScreen: () -> Long) -> SeededLayout) {
        val old = items.values.map { it.copy() }
        var nextId = maxItemId
        var nextScreen = maxScreenId
        val seeded = builder({ ++nextId }, { ++nextScreen })
        maxItemId = nextId
        maxScreenId = nextScreen
        items.clear()
        seeded.items.forEach { items[it.id] = it }
        _screens.clear()
        _screens.addAll(seeded.screens)
        bindAppsToItems()
        val s = seeded.screens.toList()
        val i = seeded.items.map { it.copy() }
        writer.execute { db.replaceAll(s, i) }
        // Free widget ids that are no longer used.
        val stillUsed = seeded.items.filter { it.type == ItemType.WIDGET }.map { it.appWidgetId }.toSet()
        val host = AppWidgetHost(app, WidgetHostManager.HOST_ID)
        old.filter { it.type == ItemType.WIDGET && it.appWidgetId !in stillUsed }.forEach {
            try { host.deleteAppWidgetId(it.appWidgetId) } catch (_: Exception) {}
        }
        prefs.defaultPage = 0
        notifyWorkspace()
    }

    fun resetLayout() {
        replaceLayout { nextId, nextScreen -> DefaultLayout.build(app, installedApps, columns, rows, nextId, nextScreen) }
    }

    private fun cleanupOrphanWidgets(list: List<ItemInfo>) {
        try {
            val host = AppWidgetHost(app, WidgetHostManager.HOST_ID)
            val used = list.filter { it.type == ItemType.WIDGET }.map { it.appWidgetId }.toMutableSet()
            used += prefs.pendingWidgetId
            host.appWidgetIds.filter { it !in used }.forEach {
                Log.i(TAG, "Releasing orphan widget id $it")
                host.deleteAppWidgetId(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Orphan widget cleanup failed", e)
        }
    }

    private fun notifyWorkspace() = listeners.forEach { it.onWorkspaceChanged() }

    // ---- Package changes ----------------------------------------------------------------------

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            reloadApps { removePackageItems(packageName, user) }
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) {
            pendingNewPackages += packageName to user
            reloadApps { addPendingNewApps() }
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) = reloadApps()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reloadApps()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reloadApps()
        override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) = reloadApps()
        override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) = reloadApps()

        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) {
            if (items.values.any { it.type == ItemType.SHORTCUT && it.packageName == packageName }) reloadApps()
        }
    }

    private fun removePackageItems(pkg: String, user: UserHandle) {
        if (!isLoaded) return
        // Still installed for this user (e.g. an update) → keep everything.
        if (isPackageAvailable(pkg, user)) return
        val serial = serialOf(user)
        val doomed = items.values.filter { it.targetPackage == pkg && it.profileSerial == serial && it.type != ItemType.FOLDER }
        if (doomed.isEmpty()) return
        val batch = removeItems(doomed)
        finalizeRemoval(batch)
        prefs.recents = prefs.recents.filterNot { it.startsWith("$pkg/") }
    }

    private fun addPendingNewApps() {
        if (!isLoaded) return
        val pending = pendingNewPackages.toList()
        pendingNewPackages.clear()
        if (!prefs.addNewAppsToHome) return
        pending.forEach { (pkg, user) ->
            val serial = serialOf(user)
            installedApps.filter { it.packageName == pkg && it.userSerial == serial }
                .filter { !isOnHome(it) && it.key !in prefs.hiddenApps }
                .forEach { addAppToHome(it, 0) }
        }
    }

    companion object {
        private const val TAG = "LauncherModel"

        @Volatile
        private var instance: LauncherModel? = null

        fun get(context: Context): LauncherModel =
            instance ?: synchronized(this) {
                instance ?: LauncherModel(context.applicationContext as Application).also { instance = it }
            }
    }
}
