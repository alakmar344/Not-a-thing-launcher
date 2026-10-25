package com.nothinglauncher.model

/** A rectangle of grid cells. */
data class CellRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h

    fun intersects(o: CellRect) = x < o.right && o.x < right && y < o.bottom && o.y < bottom

    fun centerDistanceSq(cx: Float, cy: Float): Float {
        val dx = x + w / 2f - cx
        val dy = y + h / 2f - cy
        return dx * dx + dy * dy
    }
}

/**
 * Boolean occupancy map of a [countX] × [countY] cell grid. Pure Kotlin so it can be unit tested
 * and shared by the model (auto placement) and the UI (drag & drop, resizing).
 */
class GridOccupancy(val countX: Int, val countY: Int) {

    private val cells = Array(countX.coerceAtLeast(0)) { BooleanArray(countY.coerceAtLeast(0)) }

    fun clear() = cells.forEach { it.fill(false) }

    fun mark(r: CellRect, value: Boolean = true) = mark(r.x, r.y, r.w, r.h, value)

    fun mark(x: Int, y: Int, w: Int, h: Int, value: Boolean = true) {
        for (cx in x.coerceAtLeast(0) until (x + w).coerceAtMost(countX)) {
            for (cy in y.coerceAtLeast(0) until (y + h).coerceAtMost(countY)) {
                cells[cx][cy] = value
            }
        }
    }

    fun isOccupied(x: Int, y: Int): Boolean =
        x in 0 until countX && y in 0 until countY && cells[x][y]

    fun fits(x: Int, y: Int, w: Int, h: Int) = x >= 0 && y >= 0 && x + w <= countX && y + h <= countY

    fun isRegionVacant(x: Int, y: Int, w: Int, h: Int): Boolean {
        if (!fits(x, y, w, h)) return false
        for (cx in x until x + w) for (cy in y until y + h) if (cells[cx][cy]) return false
        return true
    }

    fun isRegionVacant(r: CellRect) = isRegionVacant(r.x, r.y, r.w, r.h)

    /** First vacant region in reading order (top row first, left to right). */
    fun findVacantCell(w: Int, h: Int): CellRect? {
        for (y in 0..countY - h) for (x in 0..countX - w) {
            if (isRegionVacant(x, y, w, h)) return CellRect(x, y, w, h)
        }
        return null
    }

    /**
     * Vacant region whose centre is nearest to ([cx], [cy]) expressed in cell units
     * (e.g. 1.5, 0.5 is the middle of cell 1,0).
     */
    fun findNearestVacant(cx: Float, cy: Float, w: Int, h: Int): CellRect? {
        var best: CellRect? = null
        var bestDist = Float.MAX_VALUE
        for (y in 0..countY - h) for (x in 0..countX - w) {
            if (!isRegionVacant(x, y, w, h)) continue
            val r = CellRect(x, y, w, h)
            val d = r.centerDistanceSq(cx, cy)
            if (d < bestDist) {
                bestDist = d
                best = r
            }
        }
        return best
    }

    /** Region (vacant or not) of size w×h whose centre is nearest to ([cx], [cy]). */
    fun findNearestArea(cx: Float, cy: Float, w: Int, h: Int): CellRect? {
        if (w > countX || h > countY) return null
        val x = Math.round(cx - w / 2f).coerceIn(0, countX - w)
        val y = Math.round(cy - h / 2f).coerceIn(0, countY - h)
        return CellRect(x, y, w, h)
    }

    fun copy(): GridOccupancy = GridOccupancy(countX, countY).also { o ->
        for (x in 0 until countX) cells[x].copyInto(o.cells[x])
    }

    val vacantCount: Int get() = cells.sumOf { col -> col.count { !it } }
}

/**
 * Computes how existing items make room when something is dropped onto occupied cells.
 *
 * Single-row grids (the dock) shift neighbours sideways like a stock hotseat; regular pages
 * push each overlapped item to the nearest free area. Returns null when there is no room.
 */
object ReorderSolver {

    fun <K> solve(
        countX: Int,
        countY: Int,
        items: Map<K, CellRect>,
        target: CellRect
    ): Map<K, CellRect>? {
        if (!GridOccupancy(countX, countY).fits(target.x, target.y, target.w, target.h)) return null
        val overlapping = items.filterValues { it.intersects(target) }
        if (overlapping.isEmpty()) return items
        if (countY == 1) shiftRow(countX, items, target)?.let { return it }
        return pushToNearest(countX, countY, items, target, overlapping)
    }

    private fun <K> pushToNearest(
        countX: Int,
        countY: Int,
        items: Map<K, CellRect>,
        target: CellRect,
        overlapping: Map<K, CellRect>
    ): Map<K, CellRect>? {
        val occ = GridOccupancy(countX, countY)
        items.forEach { (k, r) -> if (k !in overlapping) occ.mark(r) }
        occ.mark(target)
        val result = LinkedHashMap(items)
        // Place big items first: they are the hardest to fit.
        val ordered = overlapping.entries.sortedByDescending { it.value.w * it.value.h }
        for ((k, r) in ordered) {
            val spot = occ.findNearestVacant(r.x + r.w / 2f, r.y + r.h / 2f, r.w, r.h) ?: return null
            occ.mark(spot)
            result[k] = spot
        }
        return result
    }

    /** Dock behaviour: slide 1-cell items towards the closest gap. */
    private fun <K> shiftRow(countX: Int, items: Map<K, CellRect>, target: CellRect): Map<K, CellRect>? {
        if (items.values.any { it.w != 1 } || target.w != 1) return null
        val slots = arrayOfNulls<Any>(countX)
        @Suppress("UNCHECKED_CAST")
        items.forEach { (k, r) -> if (r.x in 0 until countX) slots[r.x] = k as Any }
        val t = target.x
        val rightGap = (t until countX).firstOrNull { slots[it] == null }
        val leftGap = (t downTo 0).firstOrNull { slots[it] == null }
        val useRight = when {
            rightGap == null && leftGap == null -> return null
            rightGap == null -> false
            leftGap == null -> true
            else -> (rightGap - t) <= (t - leftGap)
        }
        val result = LinkedHashMap(items)
        if (useRight) {
            for (x in rightGap!! downTo t + 1) {
                val k = slots[x - 1] ?: continue
                @Suppress("UNCHECKED_CAST")
                result[k as K] = CellRect(x, 0, 1, 1)
            }
        } else {
            for (x in leftGap!! until t) {
                val k = slots[x + 1] ?: continue
                @Suppress("UNCHECKED_CAST")
                result[k as K] = CellRect(x, 0, 1, 1)
            }
        }
        return result
    }
}
