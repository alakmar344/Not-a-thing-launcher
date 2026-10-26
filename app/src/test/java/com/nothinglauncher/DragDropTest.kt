package com.nothinglauncher

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * End-to-end touch tests for home-screen drag & drop: real MotionEvents are dispatched into
 * MainActivity, exactly as the window would deliver them.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class DragDropTest {

    private lateinit var ctx: Context
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private var time = 0L

    private fun element(id: String, row: Int, cols: Int = 4) = WorkspaceItem(
        id = "bespoke_$id", type = WorkspaceItem.Type.BESPOKE,
        cellX = 0, cellY = row, spanX = cols, spanY = 1, elementId = id
    )

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // AndroidViewModelFactory caches the first Application statically; every Robolectric
        // test gets a fresh Application, so drop the stale one.
        try {
            val f = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("sInstance")
            f.isAccessible = true
            f.set(null, null)
        } catch (_: Exception) {
        }
        ctx.getSharedPreferences(LauncherPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = LauncherPrefs(ctx)
        prefs.onboardingComplete = true
        prefs.dockItems = emptyList()
        prefs.pages = listOf(
            WorkspacePage(
                "page_0",
                listOf(
                    element(BespokeElement.QUICK_NOTE.id, 0),
                    element(BespokeElement.STORAGE.id, 1)
                )
            ),
            WorkspacePage("page_1", emptyList())
        )
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        waitUntil { activity.viewModel.loaded.value == true }
        settle()
        settle()
    }

    @org.junit.After
    fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun dump() = activity.viewModel.workspacePages.value?.joinToString(" | ") { p ->
        p.items.joinToString { "${it.id}@${it.cellX},${it.cellY} ${it.spanX}x${it.spanY}" }
    }

    private fun settle(ms: Long = 50) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(decor.width.takeIf { it > 0 } ?: 1233, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(decor.height.takeIf { it > 0 } ?: 2673, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitUntil(cond: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met")
    }

    private fun cellLayout(page: Int = 0): CellLayout {
        val recycler = activity.findViewById<RecyclerView>(R.id.workspaceRecycler)
        val holder = recycler.layoutManager!!.findViewByPosition(page)
        assertNotNull("page $page not laid out", holder)
        return holder!!.findViewById(R.id.cellLayout)
    }

    private fun childWithTag(tag: String): View {
        val cl = cellLayout()
        for (i in 0 until cl.childCount) if (cl.getChildAt(i).tag == tag) return cl.getChildAt(i)
        throw AssertionError("no child $tag")
    }

    private fun centreOf(v: View): Pair<Float, Float> {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return (loc[0] + v.width / 2f) to (loc[1] + v.height / 2f)
    }

    private fun send(action: Int, x: Float, y: Float, down: Long) {
        val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        activity.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun longPressDrag(fromX: Float, fromY: Float, toX: Float, rawToY: Float) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, fromX, fromY, down)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        send(MotionEvent.ACTION_MOVE, fromX, fromY + 40f, down)
        settle(300)
        // The grid moves down below the drop targets while dragging (spring-loaded).
        val toY = rawToY + activity.findViewById<View>(R.id.workspaceRecycler).translationY
        val steps = 12
        for (i in 1..steps) {
            val f = i / steps.toFloat()
            send(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * f, fromY + (toY - fromY) * f, down)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        send(MotionEvent.ACTION_UP, toX, toY, down)
        settle(400)
    }

    private fun itemRow(id: String, page: Int = 0): Int? =
        activity.viewModel.workspacePages.value?.getOrNull(page)?.items?.firstOrNull { it.id == id }?.cellY

    @Test
    fun placedElementCanBeDraggedToAnotherRow() {
        val note = childWithTag("bespoke_quick_note")
        val (x, y) = centreOf(note)
        val cl = cellLayout()
        val rowStep = cl.cellHeight + cl.cellGapY
        val storageRow = itemRow("bespoke_storage")!!
        val storageRows = activity.viewModel.itemById(0, "bespoke_storage")!!.spanY
        // Directly below the other element (far enough from the bottom edge not to auto-scroll).
        val target = storageRow + storageRows
        // Drag it below the other element, into empty space.
        longPressDrag(x, y, x, y + rowStep * target)
        assertTrue(activity.dragController.isDragging.not())
        assertEquals(target, itemRow("bespoke_quick_note"))
        // The other element is untouched.
        assertEquals(storageRow, itemRow("bespoke_storage"))
    }

    @Test
    fun elementDroppedOnOccupiedRowGoesToNearestFreeRow() {
        val note = childWithTag("bespoke_quick_note")
        val (x, y) = centreOf(note)
        val storage = childWithTag("bespoke_storage")
        val (_, sy) = centreOf(storage)
        longPressDrag(x, y, x, sy)
        val noteRow = itemRow("bespoke_quick_note")!!
        val storageRow = itemRow("bespoke_storage")!!
        assertTrue("rows must not overlap", noteRow != storageRow)
    }

    @Test
    fun releasingWithoutMovingShowsMenuInsteadOfDragging() {
        val note = childWithTag("bespoke_quick_note")
        val (x, y) = centreOf(note)
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, x, y, down)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        send(MotionEvent.ACTION_UP, x, y, down)
        settle()
        assertTrue(activity.isPopupShowing())
        assertEquals(0, itemRow("bespoke_quick_note"))
    }

    @Test
    fun holdingAtScreenEdgeMovesElementToNextPage() {
        val note = childWithTag("bespoke_quick_note")
        val (x, y) = centreOf(note)
        val width = activity.window.decorView.width.toFloat()
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, x, y, down)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        for (i in 1..10) {
            send(MotionEvent.ACTION_MOVE, x + (width - 5 - x) * i / 10f, y, down)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        // Dwell at the edge long enough for one page flip, then let the scroll settle.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650))
        send(MotionEvent.ACTION_MOVE, width / 2f, y, down)
        settle(600)
        send(MotionEvent.ACTION_UP, width / 2f, y, down)
        settle(400)
        assertEquals(null, itemRow("bespoke_quick_note", 0))
        assertNotNull(itemRow("bespoke_quick_note", 1))
    }

    @Test
    fun editingDoesNotLoseItemsWhenColumnsShrink() {
        val prefs = LauncherPrefs(ctx)
        prefs.gridColumns = 3
        activity.viewModel.onSettingsChanged()
        settle()
        val items = activity.viewModel.workspacePages.value!![0].items
        assertEquals(2, items.size)
        assertTrue(items.all { it.cellX + it.spanX <= 3 })
    }
}
