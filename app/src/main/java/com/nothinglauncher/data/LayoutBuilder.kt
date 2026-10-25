package com.nothinglauncher.data

import com.nothinglauncher.model.CellRect
import com.nothinglauncher.model.Containers
import com.nothinglauncher.model.GridOccupancy
import com.nothinglauncher.model.ItemInfo

/**
 * Helper that lays items out on consecutive pages of a fixed grid, spilling onto a new page
 * whenever the current one is full. Used by the v8 → v9 migration, the default layout and
 * layout restores, so nothing a user owned is ever dropped for lack of room.
 */
class LayoutBuilder(
    private val columns: Int,
    private val rows: Int,
    private val idSource: () -> Long,
    private val screenIdSource: () -> Long
) {
    val screens = ArrayList<Long>()
    val items = ArrayList<ItemInfo>()
    private val grids = HashMap<Long, GridOccupancy>()

    /** Starts a new page directly after [after] (or at the end) and returns its id. */
    fun newScreen(after: Long? = null): Long {
        val id = screenIdSource()
        val index = after?.let { screens.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: screens.size
        screens.add(index, id)
        grids[id] = GridOccupancy(columns, rows)
        return id
    }

    fun grid(screen: Long): GridOccupancy = grids.getOrPut(screen) { GridOccupancy(columns, rows) }

    /**
     * Places [item] on [screen], preferring cell ([prefX], [prefY]) when given and vacant.
     * Overflows to the next page created after [screen]. Returns the screen used.
     */
    fun place(item: ItemInfo, screen: Long, prefX: Int = -1, prefY: Int = -1): Long {
        item.spanX = item.spanX.coerceIn(1, columns)
        item.spanY = item.spanY.coerceIn(1, rows)
        var s = screen
        var guard = 0
        while (guard++ < 64) {
            val g = grid(s)
            val spot = if (prefX >= 0 && prefY >= 0 && g.isRegionVacant(prefX, prefY, item.spanX, item.spanY)) {
                CellRect(prefX, prefY, item.spanX, item.spanY)
            } else {
                g.findVacantCell(item.spanX, item.spanY)
            }
            if (spot != null) {
                g.mark(spot)
                put(item, Containers.DESKTOP, s, spot.x, spot.y)
                return s
            }
            // Page full: continue on the page right after it (creating it if needed).
            val idx = screens.indexOf(s)
            s = if (idx >= 0 && idx + 1 < screens.size && overflowOf[s] == screens[idx + 1]) {
                screens[idx + 1]
            } else {
                newScreen(after = s).also { overflowOf[s] = it }
            }
        }
        return s
    }

    private val overflowOf = HashMap<Long, Long>()

    fun placeInHotseat(item: ItemInfo, x: Int) = put(item, Containers.HOTSEAT, -1, x, 0)

    fun placeInFolder(item: ItemInfo, folder: ItemInfo, rank: Int) {
        item.rank = rank
        put(item, folder.id, -1, -1, -1)
    }

    private fun put(item: ItemInfo, container: Long, screen: Long, x: Int, y: Int) {
        if (item.id == ItemInfo.NO_ID) item.id = idSource()
        item.container = container
        item.screenId = screen
        item.cellX = x
        item.cellY = y
        if (container == Containers.HOTSEAT || container > 0) {
            item.spanX = 1
            item.spanY = 1
        }
        items += item
    }

    /** Lowest row index below every item already placed on [screen]. */
    fun firstFreeRow(screen: Long): Int =
        items.filter { it.container == Containers.DESKTOP && it.screenId == screen }
            .maxOfOrNull { it.cellY + it.spanY } ?: 0
}
