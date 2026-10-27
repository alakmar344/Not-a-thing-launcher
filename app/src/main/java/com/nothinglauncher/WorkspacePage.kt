package com.nothinglauncher

enum class BespokeElement(val id: String, val titleRes: Int, val subRes: Int, val iconRes: Int) {
    CLOCK("clock", R.string.element_clock, R.string.element_clock_sub, R.drawable.ic_alarm),
    BATTERY_CALENDAR("battery_cal", R.string.element_battery_cal, R.string.element_battery_cal_sub, R.drawable.ic_bolt),
    WEATHER("weather", R.string.element_weather, R.string.element_weather_sub, R.drawable.ic_weather),
    QUICK_TOGGLES("quick_toggles", R.string.element_toggles, R.string.element_toggles_sub, R.drawable.ic_tune),
    PEDOMETER("pedometer", R.string.element_pedometer, R.string.element_pedometer_sub, R.drawable.ic_walk),
    COMPASS("compass", R.string.element_compass, R.string.element_compass_sub, R.drawable.ic_compass),
    SCREEN_TIME("screen_time", R.string.element_screen_time, R.string.element_screen_time_sub, R.drawable.ic_time),
    STORAGE("storage", R.string.element_storage, R.string.element_storage_sub, R.drawable.ic_storage),
    WORLD_CLOCK("world_clock", R.string.element_world_clock, R.string.element_world_clock_sub, R.drawable.ic_globe),
    BATTERY_DOTS("battery_dots", R.string.element_battery_dots, R.string.element_battery_dots_sub, R.drawable.ic_bolt),
    QUICK_NOTE("quick_note", R.string.element_quick_note, R.string.element_quick_note_sub, R.drawable.ic_pin),
    ANALOG_CLOCK("analog_clock", R.string.element_analog_clock, R.string.element_analog_clock_sub, R.drawable.ic_alarm),
    MEDIA_PLAYER("media_player", R.string.element_media, R.string.element_media_sub, R.drawable.ic_play),
    STOPWATCH("stopwatch", R.string.element_stopwatch, R.string.element_stopwatch_sub, R.drawable.ic_stopwatch),
    SYSTEM_MONITOR("system_monitor", R.string.element_system_monitor, R.string.element_system_monitor_sub, R.drawable.ic_memory),
    HABIT_TRACKER("habit_tracker", R.string.element_habit_tracker, R.string.element_habit_tracker_sub, R.drawable.ic_check),
    COUNTDOWN("countdown", R.string.element_countdown, R.string.element_countdown_sub, R.drawable.ic_calendar_event),
    SOUND_RECORDER("sound_recorder", R.string.element_sound_recorder, R.string.element_sound_recorder_sub, R.drawable.ic_mic);

    companion object {
        fun fromId(id: String): BespokeElement? = values().firstOrNull { it.id == id }
    }
}

data class WorkspaceFolder(
    val id: String,
    val name: String,
    val appKeys: List<String> = emptyList(),
    val isEnlarged: Boolean = false
)

data class WorkspaceItem(
    val id: String,
    val type: Type,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val appKey: String = "",
    val folder: WorkspaceFolder? = null,
    val elementId: String = "",
    val widgetId: Int = -1
) {
    enum class Type {
        APP, FOLDER, MAX_APP, BESPOKE, WIDGET
    }

    fun covers(col: Int, row: Int): Boolean =
        col in cellX until cellX + spanX && row in cellY until cellY + spanY

    fun overlaps(x: Int, y: Int, sx: Int, sy: Int): Boolean =
        x < cellX + spanX && cellX < x + sx && y < cellY + spanY && cellY < y + sy
}

/** Occupancy maths for the workspace grid (fixed column count, rows grow downwards). */
object GridMath {

    fun isAreaFree(items: List<WorkspaceItem>, cols: Int, x: Int, y: Int, sx: Int, sy: Int, ignoreId: String? = null): Boolean {
        if (x < 0 || y < 0 || x + sx > cols) return false
        return items.none { it.id != ignoreId && it.overlaps(x, y, sx, sy) }
    }

    /** First free area scanning rows top-to-bottom, columns left-to-right. */
    fun firstFree(items: List<WorkspaceItem>, cols: Int, sx: Int, sy: Int, ignoreId: String? = null): Pair<Int, Int> {
        val spanX = sx.coerceIn(1, cols)
        var r = 0
        while (true) {
            for (c in 0..cols - spanX) {
                if (isAreaFree(items, cols, c, r, spanX, sy, ignoreId)) return c to r
            }
            r++
        }
    }

    /**
     * Free area closest to ([x], [y]) - the same "nearest vacant area" search Launcher3's
     * CellLayout runs while an item hovers over an occupied cell.
     */
    fun nearestFree(items: List<WorkspaceItem>, cols: Int, x: Int, y: Int, sx: Int, sy: Int, ignoreId: String? = null): Pair<Int, Int> {
        val spanX = sx.coerceIn(1, cols)
        val tx = x.coerceIn(0, cols - spanX)
        val ty = y.coerceAtLeast(0)
        if (isAreaFree(items, cols, tx, ty, spanX, sy, ignoreId)) return tx to ty
        val maxRow = (items.filter { it.id != ignoreId }.maxOfOrNull { it.cellY + it.spanY } ?: 0)
            .coerceAtLeast(ty) + sy + 1
        var best: Pair<Int, Int>? = null
        var bestDist = Double.MAX_VALUE
        for (r in 0..maxRow) {
            for (c in 0..cols - spanX) {
                if (!isAreaFree(items, cols, c, r, spanX, sy, ignoreId)) continue
                val dx = (c - tx).toDouble()
                val dy = (r - ty).toDouble()
                // Slight vertical bias so items prefer to stay on the same row.
                val d = dx * dx + dy * dy * 1.2
                if (d < bestDist) {
                    bestDist = d
                    best = c to r
                }
            }
        }
        return best ?: firstFree(items, cols, spanX, sy, ignoreId)
    }
}

/**
 * One home-screen page. [items] (cell positions + spans) is the single source of truth; the
 * legacy list views below are derived from it so older call sites and the v3 JSON schema keep
 * working without ever drifting out of sync with the grid.
 */
data class WorkspacePage(
    val id: String,
    val items: List<WorkspaceItem> = emptyList()
) {
    val appKeys: List<String> get() = items.filter { it.type == WorkspaceItem.Type.APP }.map { it.appKey }
    val elements: List<String> get() = items.filter { it.type == WorkspaceItem.Type.BESPOKE }.map { it.elementId }
    val widgetIds: List<Int> get() = items.filter { it.type == WorkspaceItem.Type.WIDGET }.map { it.widgetId }
    val enlargedApps: List<String> get() = items.filter { it.type == WorkspaceItem.Type.MAX_APP }.map { it.appKey }
    val folders: List<WorkspaceFolder> get() = items.filter { it.type == WorkspaceItem.Type.FOLDER }.mapNotNull { it.folder }

    /** Every app key shown on this page, including folder contents. */
    val allAppKeys: List<String>
        get() = items.flatMap { itm ->
            when (itm.type) {
                WorkspaceItem.Type.APP, WorkspaceItem.Type.MAX_APP -> listOf(itm.appKey)
                WorkspaceItem.Type.FOLDER -> itm.folder?.appKeys.orEmpty()
                else -> emptyList()
            }
        }.filter { it.isNotEmpty() }

    fun itemAt(col: Int, row: Int, ignoreId: String? = null): WorkspaceItem? =
        items.firstOrNull { it.id != ignoreId && it.covers(col, row) }

    companion object {
        /**
         * Allocates non-overlapping (cellX, cellY) positions for legacy items on a grid of [columns].
         */
        fun buildItemsFromLegacy(
            columns: Int,
            elements: List<String>,
            widgetIds: List<Int>,
            enlargedApps: List<String>,
            folders: List<WorkspaceFolder>,
            appKeys: List<String>
        ): List<WorkspaceItem> {
            val cols = columns.coerceAtLeast(1)
            val occupied = mutableMapOf<Pair<Int, Int>, Boolean>()

            fun isFree(c: Int, r: Int, spanX: Int, spanY: Int): Boolean {
                if (c + spanX > cols) return false
                for (dx in 0 until spanX) {
                    for (dy in 0 until spanY) {
                        if (occupied[Pair(c + dx, r + dy)] == true) return false
                    }
                }
                return true
            }

            fun occupy(c: Int, r: Int, spanX: Int, spanY: Int) {
                for (dx in 0 until spanX) {
                    for (dy in 0 until spanY) {
                        occupied[Pair(c + dx, r + dy)] = true
                    }
                }
            }

            fun findNextSlot(spanX: Int, spanY: Int): Pair<Int, Int> {
                var r = 0
                while (true) {
                    for (c in 0..cols - spanX) {
                        if (isFree(c, r, spanX, spanY)) {
                            occupy(c, r, spanX, spanY)
                            return Pair(c, r)
                        }
                    }
                    r++
                }
            }

            val result = mutableListOf<WorkspaceItem>()

            // 1. Bespoke elements: span full width, 1 or 2 rows
            elements.forEach { elId ->
                val spanY = if (elId == BespokeElement.CLOCK.id || elId == BespokeElement.BATTERY_CALENDAR.id) 2 else 1
                val (c, r) = findNextSlot(cols, spanY)
                result += WorkspaceItem(
                    id = "bespoke_$elId",
                    type = WorkspaceItem.Type.BESPOKE,
                    cellX = c,
                    cellY = r,
                    spanX = cols,
                    spanY = spanY,
                    elementId = elId
                )
            }

            // 2. Widgets: span full width, 2 rows
            widgetIds.forEach { wId ->
                val (c, r) = findNextSlot(cols, 2)
                result += WorkspaceItem(
                    id = "widget_$wId",
                    type = WorkspaceItem.Type.WIDGET,
                    cellX = c,
                    cellY = r,
                    spanX = cols,
                    spanY = 2,
                    widgetId = wId
                )
            }

            // 3. Enlarged 2x2 apps
            enlargedApps.forEach { key ->
                val span = if (cols >= 2) 2 else 1
                val (c, r) = findNextSlot(span, span)
                result += WorkspaceItem(
                    id = "max_$key",
                    type = WorkspaceItem.Type.MAX_APP,
                    cellX = c,
                    cellY = r,
                    spanX = span,
                    spanY = span,
                    appKey = key
                )
            }

            // 4. Folders
            folders.forEach { f ->
                val span = if (f.isEnlarged && cols >= 2) 2 else 1
                val (c, r) = findNextSlot(span, span)
                result += WorkspaceItem(
                    id = "folder_${f.id}",
                    type = WorkspaceItem.Type.FOLDER,
                    cellX = c,
                    cellY = r,
                    spanX = span,
                    spanY = span,
                    folder = f
                )
            }

            // 5. Apps
            appKeys.filter { it.isNotEmpty() }.forEach { key ->
                val (c, r) = findNextSlot(1, 1)
                result += WorkspaceItem(
                    id = "app_$key",
                    type = WorkspaceItem.Type.APP,
                    cellX = c,
                    cellY = r,
                    spanX = 1,
                    spanY = 1,
                    appKey = key
                )
            }

            return result
        }
    }
}
