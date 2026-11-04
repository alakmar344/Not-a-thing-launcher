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
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherState
import com.android.launcher3.widget.custom.CustomWidgetManager
import com.android.launcher3.widget.picker.WidgetsFullSheet
import com.nothinglauncher.home.NothingLauncher
import com.nothinglauncher.settings.NothingSettingsActivity
import org.junit.After
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
}
