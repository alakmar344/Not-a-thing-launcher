package com.nothinglauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridMathTest {

    private fun app(key: String, x: Int, y: Int) =
        WorkspaceItem(id = "app_$key", type = WorkspaceItem.Type.APP, cellX = x, cellY = y, appKey = key)

    @Test
    fun freeCellIsUsedAsIs() {
        val items = listOf(app("a", 0, 0))
        assertEquals(2 to 1, GridMath.nearestFree(items, 4, 2, 1, 1, 1))
    }

    @Test
    fun occupiedCellFindsNeighbour() {
        val items = listOf(app("a", 1, 0))
        val (c, r) = GridMath.nearestFree(items, 4, 1, 0, 1, 1)
        assertTrue(GridMath.isAreaFree(items, 4, c, r, 1, 1))
        assertEquals(0, r)
    }

    @Test
    fun itemIgnoresItselfWhenMoving() {
        val items = listOf(app("a", 1, 1))
        assertEquals(1 to 1, GridMath.nearestFree(items, 4, 1, 1, 1, 1, ignoreId = "app_a"))
    }

    @Test
    fun wideItemIsClampedInsideColumns() {
        val (c, _) = GridMath.nearestFree(emptyList(), 4, 3, 0, 2, 2)
        assertEquals(2, c)
    }

    @Test
    fun areaOutOfBoundsIsNotFree() {
        assertFalse(GridMath.isAreaFree(emptyList(), 4, 3, 0, 2, 1))
        assertFalse(GridMath.isAreaFree(emptyList(), 4, -1, 0, 1, 1))
    }

    @Test
    fun firstFreeSkipsFullRows() {
        val items = (0 until 4).map { app("k$it", it, 0) }
        assertEquals(0 to 1, GridMath.firstFree(items, 4, 1, 1))
    }

    @Test
    fun pageDerivesLegacyLists() {
        val folder = WorkspaceFolder("f1", "F", listOf("x", "y"))
        val page = WorkspacePage(
            "p",
            listOf(
                app("a", 0, 0),
                WorkspaceItem("folder_f1", WorkspaceItem.Type.FOLDER, 1, 0, folder = folder)
            )
        )
        assertEquals(listOf("a"), page.appKeys)
        assertEquals(listOf("a", "x", "y"), page.allAppKeys)
        assertEquals("folder_f1", page.itemAt(1, 0)?.id)
    }
}
