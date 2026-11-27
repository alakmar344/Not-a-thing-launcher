package com.nothinglauncher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import android.os.UserHandle
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import com.nothinglauncher.widgets.DateTimeWidgetProvider
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherState
import com.android.launcher3.widget.custom.CustomWidgetManager
import com.android.launcher3.widget.picker.WidgetsFullSheet
import com.nothinglauncher.home.NothingLauncher
import com.nothinglauncher.settings.NothingSettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

/**
 * Boots the real launcher (stock Launcher3 + the Not a Thing layer) on the JVM, drives the main
 * screens and prints the full logcat, failing on any crash. A stand-in for "run it and watch the
 * logs" where no emulator is available. Run: ./gradlew :app:testDebugUnitTest --tests '*Runtime*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [LauncherRuntimeTest.LauncherAppsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class LauncherRuntimeTest {

    /** Robolectric's LauncherApps lacks install sessions; Launcher3's loader queries them. */
    @Implements(LauncherApps::class)
    class LauncherAppsShadow : ShadowLauncherApps() {
        @Implementation
        override fun getAllPackageInstallerSessions(): List<PackageInstaller.SessionInfo> = emptyList()

        @Implementation
        override fun getShortcuts(query: LauncherApps.ShortcutQuery, user: UserHandle): List<ShortcutInfo> = emptyList()
    }

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        // Robolectric's bitmap shadow can't copy alpha-only bitmaps, which Launcher3 creates for
        // the monochrome layer of system icons when themed icons are on (fine on real devices).
        LauncherPrefs.get(ApplicationProvider.getApplicationContext())
            .putSync(LauncherPrefs.THEMED_ICONS.to(false))
        // A real device always lists our own date & time widget provider.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = AppWidgetProviderInfo().apply {
            provider = ComponentName(context, DateTimeWidgetProvider::class.java)
            label = "Date & time"
            minWidth = 180; minHeight = 80
            resizeMode = AppWidgetProviderInfo.RESIZE_BOTH
            providerInfo = ActivityInfo().apply {
                packageName = context.packageName
                name = DateTimeWidgetProvider::class.java.name
                applicationInfo = context.applicationInfo
            }
        }
        shadowOf(AppWidgetManager.getInstance(context)).apply {
            addInstalledProvider(provider)
            addInstalledProvidersForProfile(Process.myUserHandle(), provider)
        }
        println("[runtime] widget providers for profile: " +
            AppWidgetManager.getInstance(context).getInstalledProvidersForProfile(Process.myUserHandle()).size)
    }

    @After
    fun dumpProblems() {
        val problems = ShadowLog.getLogs().filter { it.type >= Log.WARN && it.throwable != null }
        println("==== ${problems.size} warnings/errors with stack traces ====")
        problems.forEach {
            println("${levelName(it.type)}/${it.tag}: ${it.msg}")
            it.throwable?.printStackTrace(System.out)
        }
    }

    private fun levelName(type: Int) = when (type) {
        Log.ERROR -> "E"; Log.WARN -> "W"; Log.ASSERT -> "A"; else -> type.toString()
    }

    /** Runs main-thread work and gives Launcher3's model thread time to load and bind. */
    private fun pump(rounds: Int = 40) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun count(view: View): Int =
        if (view is ViewGroup) 1 + (0 until view.childCount).sumOf { count(view.getChildAt(it)) } else 1

    @Test
    fun launcherBootsAndMainScreensWork() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val controller = Robolectric.buildActivity(NothingLauncher::class.java, home).setup()
        val launcher = controller.get()
        pump(80)
        println("[runtime] home bound: workspace pages=${launcher.workspace.childCount}, views=${count(launcher.dragLayer)}")

        // Custom widgets registered with Launcher3 (the dot-matrix elements).
        val elements = CustomWidgetManager.INSTANCE.get(launcher).stream().toList()
        println("[runtime] elements in widget picker: ${elements.map { it.label }}")
        assertTrue("Not a Thing elements must be registered", elements.size >= 4)

        // Drawer.
        launcher.stateManager.goToState(LauncherState.ALL_APPS, false)
        pump()
        val header = launcher.appsView.findViewById<View>(R.id.nt_drawer_header)
        println("[runtime] drawer open: header=${header != null}, search=${launcher.appsView.searchUiManager.editText != null}")
        assertTrue("drawer header must be in the drawer", header != null)
        launcher.appsView.searchUiManager.editText?.setText("set")
        pump()
        launcher.appsView.searchUiManager.editText?.setText("")
        launcher.stateManager.goToState(LauncherState.NORMAL, false)
        pump()

        // Long-press menu (v10 customize sheet) and the stock widget picker.
        launcher.showDefaultOptions(100f, 100f)
        pump()
        WidgetsFullSheet.show(launcher, false)
        pump()
        launcher.onBackPressed()
        pump()

        // Pause/resume (refreshes media and weather), configuration round-trip.
        controller.pause().stop()
        pump()
        controller.start().resume()
        pump()

        // v10 settings screen and the stock advanced settings.
        Robolectric.buildActivity(NothingSettingsActivity::class.java).setup().also { pump() }
            .pause().stop().destroy()
        Robolectric.buildActivity(com.android.launcher3.settings.SettingsActivity::class.java).setup()
            .also { pump() }.pause().stop().destroy()

        controller.pause().stop().destroy()
        pump()
        println("[runtime] done")
    }

    @Test
    fun rightSwipeRevealsVisibleOverlayOutsideFadingDragLayer() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val controller = Robolectric.buildActivity(NothingLauncher::class.java, home).setup()
        val launcher = controller.get()
        pump(80)

        // Ensure view hierarchy has realistic screen dimensions even under Robolectric's headless display.
        val root = launcher.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)

        val proxy = launcher.overlayManager as com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayTouchProxy
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 80f, 1200f, 0)
        val move = MotionEvent.obtain(now, now + 40L, MotionEvent.ACTION_MOVE, 600f, 1200f, 0)
        val up = MotionEvent.obtain(now, now + 80L, MotionEvent.ACTION_UP, 600f, 1200f, 0)
        try {
            proxy.onOverlayMotionEvent(down, 0f)
            proxy.onOverlayMotionEvent(move, 0.52f)
            proxy.onFlingVelocity(2400f)
            proxy.onOverlayMotionEvent(up, 0.52f)
        } finally {
            down.recycle()
            move.recycle()
            up.recycle()
        }
        pump(20)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)

        val overlayRoot = launcher.findViewById<View>(R.id.essentialRoot)
        assertNotNull("Essential/Dock Space overlay must be mounted", overlayRoot)
        val overlayHost = overlayRoot.parent as View
        assertFalse(
            "Overlay must NOT sit inside DragLayer, which AOSP fades to alpha=0 & shifts off-screen on scroll",
            overlayHost === launcher.dragLayer || overlayHost.parent === launcher.dragLayer
        )
        assertTrue("DragLayer should have faded/slid out when overlay opened", launcher.dragLayer.alpha == 0f)
        assertTrue("Overlay root must be visible", overlayRoot.isShown)
        assertEquals(1f, overlayHost.alpha, 0.001f)
        assertEquals(0f, overlayHost.translationX, 0.5f)
        assertTrue("Overlay must occupy positive width/height", overlayRoot.width > 0 && overlayRoot.height > 0)
        val headline = overlayRoot.findViewById<TextView>(R.id.essentialBriefingHeadline)
        assertNotNull(headline)
        assertTrue("Overlay content must be bound", headline.text.isNotBlank())

        // Tapping the overlay's Close control restores DragLayer to full visibility at x=0.
        overlayRoot.findViewById<View>(R.id.essentialCloseBtn).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        pump(10)
        assertFalse("Overlay should hide on back", overlayRoot.isShown)
        assertEquals(1f, launcher.dragLayer.alpha, 0.001f)
        assertEquals(0f, launcher.dragLayer.translationX, 0.5f)

        controller.pause().stop().destroy()
        pump()
    }
}
