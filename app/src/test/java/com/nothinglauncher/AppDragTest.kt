package com.nothinglauncher

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
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

/** Drag & drop of app icons that are already placed on the home screen / in the dock. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [29], qualifiers = "w411dp-h891dp-xxhdpi")
class AppDragTest {

    private lateinit var ctx: Context
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    private fun key(n: String) = "com.test.$n/com.test.$n.Main"

    private fun appItem(n: String, x: Int, y: Int) = WorkspaceItem(
        id = "app_${key(n)}", type = WorkspaceItem.Type.APP, cellX = x, cellY = y, appKey = key(n)
    )

    private fun fakeActivity(n: String): LauncherActivityInfo {
        val ai = ApplicationInfo().apply {
            packageName = "com.test.$n"
            name = "App$n"
            nonLocalizedLabel = "App $n"
            flags = 0
        }
        val info = ActivityInfo().apply {
            packageName = "com.test.$n"
            name = "com.test.$n.Main"
            nonLocalizedLabel = "App $n"
            applicationInfo = ai
            exported = true
        }
        val all = LauncherActivityInfo::class.java.declaredConstructors
        val ctor = all.firstOrNull { c ->
            c.parameterTypes.contentEquals(arrayOf(Context::class.java, ActivityInfo::class.java, UserHandle::class.java))
        } ?: throw AssertionError(all.joinToString { it.parameterTypes.joinToString(",") { t -> t.name } })
        ctor.isAccessible = true
        return ctor.newInstance(ctx, info, Process.myUserHandle()) as LauncherActivityInfo
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        try {
            val f = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("sInstance")
            f.isAccessible = true
            f.set(null, null)
        } catch (_: Exception) {
        }
        val la = ctx.getSystemService(LauncherApps::class.java)
        listOf("a", "b", "c", "d").forEach { shadowOf(la).addActivity(Process.myUserHandle(), fakeActivity(it)) }

        val prefs = LauncherPrefs(ctx)
        prefs.onboardingComplete = true
        prefs.dockItems = listOf(key("d"))
        prefs.pages = listOf(
            WorkspacePage("page_0", listOf(appItem("a", 0, 0), appItem("b", 1, 0), appItem("c", 2, 0))),
            WorkspacePage("page_1", emptyList())
        )
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (activity.viewModel.loaded.value == true && activity.viewModel.allApps.value.orEmpty().isNotEmpty()) return@repeat
            Thread.sleep(20)
        }
        settle()
        settle()
    }

    @After
    fun tearDown() {
        if (!::controller.isInitialized) return
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
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

    private fun cellLayout(): CellLayout {
        val recycler = activity.findViewById<RecyclerView>(R.id.workspaceRecycler)
        return recycler.layoutManager!!.findViewByPosition(0)!!.findViewById(R.id.cellLayout)
    }

    /** Screen centre of grid cell ([col], [row]). */
    private fun cellCentre(col: Int, row: Int): Pair<Float, Float> {
        val cl = cellLayout()
        val loc = IntArray(2)
        cl.getLocationOnScreen(loc)
        val x = loc[0] + cl.paddingStart + col * (cl.cellWidth + cl.cellGapX) + cl.cellWidth / 2f
        val y = loc[1] + cl.paddingTop + row * (cl.cellHeight + cl.cellGapY) + cl.cellHeight / 2f
        return x to y
    }

    private fun send(action: Int, x: Float, y: Float, down: Long) {
        val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        activity.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** [to] is evaluated after the drag started, once the spring-loaded grid has settled. */
    private fun longPressDrag(from: Pair<Float, Float>, to: () -> Pair<Float, Float>) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, from.first, from.second, down)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        send(MotionEvent.ACTION_MOVE, from.first + 40f, from.second + 40f, down)
        settle(300)
        val dest = to()
        val sx = from.first + 40f
        val sy = from.second + 40f
        for (i in 1..12) {
            val f = i / 12f
            send(MotionEvent.ACTION_MOVE, sx + (dest.first - sx) * f, sy + (dest.second - sy) * f, down)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        send(MotionEvent.ACTION_UP, dest.first, dest.second, down)
        settle(400)
    }

    private fun page0() = activity.viewModel.workspacePages.value!![0]

    @Test
    fun placedAppCanBeMovedToAnotherCell() {
        assertEquals(3, page0().items.size)
        longPressDrag(cellCentre(0, 0)) { cellCentre(3, 2) }
        val a = page0().items.first { it.appKey == key("a") }
        assertEquals(3 to 2, a.cellX to a.cellY)
        assertEquals(3, page0().items.size)
    }

    @Test
    fun appCanBeMovedAgainAfterBeingMoved() {
        longPressDrag(cellCentre(0, 0)) { cellCentre(3, 2) }
        longPressDrag(cellCentre(3, 2)) { cellCentre(1, 3) }
        val a = page0().items.first { it.appKey == key("a") }
        assertEquals(1 to 3, a.cellX to a.cellY)
    }

    @Test
    fun droppingAppOnAnotherAppCreatesFolder() {
        longPressDrag(cellCentre(0, 0)) { cellCentre(1, 0) }
        val folder = page0().items.single { it.type == WorkspaceItem.Type.FOLDER }
        assertEquals(listOf(key("b"), key("a")), folder.folder!!.appKeys)
        assertEquals(1 to 0, folder.cellX to folder.cellY)
    }

    @Test
    fun dockAppCanBeDraggedOntoWorkspace() {
        val dock = activity.findViewById<DockView>(R.id.dock)
        val icon = (dock.getChildAt(0) as android.view.ViewGroup).getChildAt(0)
        val loc = IntArray(2)
        icon.getLocationOnScreen(loc)
        longPressDrag(loc[0] + icon.width / 2f to loc[1] + icon.height / 2f) { cellCentre(2, 3) }
        val d = page0().items.firstOrNull { it.appKey == key("d") }
        assertNotNull(d)
        assertEquals(2 to 3, d!!.cellX to d.cellY)
        assertTrue(activity.viewModel.dockApps.value.orEmpty().none { it.key == key("d") })
    }

    @Test
    fun tapLaunchesInsteadOfDragging() {
        val (x, y) = cellCentre(0, 0)
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, x, y, down)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        send(MotionEvent.ACTION_UP, x, y, down)
        settle()
        assertTrue(!activity.dragController.isDragging)
        val a = page0().items.first { it.appKey == key("a") }
        assertEquals(0 to 0, a.cellX to a.cellY)
    }
}
