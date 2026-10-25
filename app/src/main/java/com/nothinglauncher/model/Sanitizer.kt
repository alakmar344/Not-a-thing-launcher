package com.nothinglauncher.model

import com.nothinglauncher.AppInfo
import com.nothinglauncher.BespokeElement

/**
 * Repairs a loaded layout so the UI can trust it: items on unknown pages, positions outside a
 * (possibly shrunk) grid, overlaps, orphaned folder contents, degenerate folders and apps that
 * were uninstalled while the launcher wasn't running. Nothing still installed is ever dropped —
 * misplaced items are moved to the nearest free cell or onto a new page.
 */
class Sanitizer(
    private val apps: List<AppInfo>,
    private val byKey: Map<String, AppInfo>,
    private val columns: Int,
    private val rows: Int,
    private val nextId: () -> Long,
    private val nextScreenId: () -> Long,
    private val packageInstalled: ((String, Long) -> Boolean)? = null
) {
    class Result(val screens: List<Long>, val items: List<ItemInfo>, val changed: Boolean)

    fun run(inScreens: List<Long>, inItems: List<ItemInfo>, canDropMissingApps: Boolean): Result {
        var changed = false
        val screens = inScreens.distinct().toMutableList()
        if (screens.size != inScreens.size) changed = true
        if (screens.isEmpty()) {
            screens += nextScreenId()
            changed = true
        }

        // 1. Validate item identity.
        val valid = ArrayList<ItemInfo>()
        for (item in inItems) {
            when (item.type) {
                ItemType.APP -> {
                    val c = item.component
                    if (c == null) { changed = true; continue }
                    if (item.appKey !in byKey && canDropMissingApps) {
                        val sibling = apps.firstOrNull { it.packageName == c.packageName && it.userSerial == item.profileSerial }
                        if (sibling != null) {
                            // The app renamed its launcher activity in an update: follow it.
                            item.component = sibling.componentName
                            changed = true
                        } else if (packageInstalled?.invoke(c.packageName, item.profileSerial) != true) {
                            changed = true
                            continue
                        }
                    }
                }
                ItemType.SHORTCUT -> if (item.packageName == null || item.shortcutId == null) { changed = true; continue }
                ItemType.ELEMENT -> if (BespokeElement.fromId(item.elementId) == null) { changed = true; continue }
                ItemType.WIDGET -> if (item.appWidgetId <= 0) { changed = true; continue }
                ItemType.FOLDER -> Unit
                else -> { changed = true; continue }
            }
            valid += item
        }

        val byId = valid.associateBy { it.id }.toMutableMap()

        // 2. Folder contents must point at an existing folder on the desktop or dock.
        val orphans = ArrayList<ItemInfo>()
        valid.filter { it.container > 0 }.forEach { child ->
            val folder = byId[child.container]
            if (folder == null || folder.type != ItemType.FOLDER || folder.container > 0 || !child.isIcon) {
                orphans += child
                child.container = Containers.DESKTOP
                child.screenId = -1
                changed = true
            }
        }

        // 3. Collapse folders with fewer than two items.
        valid.filter { it.type == ItemType.FOLDER }.forEach { folder ->
            val contents = valid.filter { it.container == folder.id }.sortedBy { it.rank }
            if (contents.size < 2) {
                byId.remove(folder.id)
                if (contents.size == 1) {
                    contents[0].copyPositionFrom(folder)
                    contents[0].rank = 0
                }
                changed = true
            } else {
                contents.forEachIndexed { i, c -> if (c.rank != i) { c.rank = i; changed = true } }
            }
        }
        val alive = valid.filter { byId.containsKey(it.id) }

        // 4. Dock: one row, icons and folders only.
        val dockGrid = GridOccupancy(columns, 1)
        val homeless = ArrayList<ItemInfo>()
        alive.filter { it.container == Containers.HOTSEAT }.sortedBy { it.cellX }.forEach { item ->
            val ok = (item.isIcon || item.type == ItemType.FOLDER) && dockGrid.isRegionVacant(item.cellX, 0, 1, 1)
            if (ok) {
                if (item.cellY != 0 || item.spanX != 1 || item.spanY != 1) {
                    item.cellY = 0; item.spanX = 1; item.spanY = 1; changed = true
                }
                dockGrid.mark(item.cellX, 0, 1, 1)
            } else {
                val free = dockGrid.findVacantCell(1, 1)
                if (free != null && (item.isIcon || item.type == ItemType.FOLDER)) {
                    item.cellX = free.x; item.cellY = 0; item.spanX = 1; item.spanY = 1
                    dockGrid.mark(free)
                } else {
                    homeless += item
                    item.container = Containers.DESKTOP
                    item.screenId = -1
                }
                changed = true
            }
        }

        // 5. Desktop pages.
        val grids = HashMap<Long, GridOccupancy>()
        fun grid(s: Long) = grids.getOrPut(s) { GridOccupancy(columns, rows) }
        val desktop = alive.filter { it.container == Containers.DESKTOP && it !in homeless && it !in orphans }
        for (item in desktop) {
            val sx = item.spanX.coerceIn(1, columns)
            val sy = item.spanY.coerceIn(1, rows)
            if (sx != item.spanX || sy != item.spanY) { item.spanX = sx; item.spanY = sy; changed = true }
            if (item.screenId !in screens) { homeless += item; changed = true; continue }
            val g = grid(item.screenId)
            if (g.isRegionVacant(item.cellX, item.cellY, sx, sy)) {
                g.mark(item.cellX, item.cellY, sx, sy)
            } else {
                val spot = g.findNearestVacant(item.cellX + sx / 2f, item.cellY + sy / 2f, sx, sy)
                if (spot != null) {
                    item.cellX = spot.x; item.cellY = spot.y
                    g.mark(spot)
                } else {
                    homeless += item
                }
                changed = true
            }
        }

        // 6. Re-home everything that lost its place, appending pages as needed.
        for (item in homeless + orphans) {
            if (item.container > 0) continue
            item.container = Containers.DESKTOP
            item.spanX = item.spanX.coerceIn(1, columns)
            item.spanY = item.spanY.coerceIn(1, rows)
            var placed = false
            for (s in screens) {
                val spot = grid(s).findVacantCell(item.spanX, item.spanY) ?: continue
                grid(s).mark(spot)
                item.screenId = s; item.cellX = spot.x; item.cellY = spot.y
                placed = true
                break
            }
            if (!placed) {
                val s = nextScreenId()
                screens += s
                grid(s).mark(0, 0, item.spanX, item.spanY)
                item.screenId = s; item.cellX = 0; item.cellY = 0
            }
            changed = true
        }

        return Result(screens, alive, changed)
    }
}
