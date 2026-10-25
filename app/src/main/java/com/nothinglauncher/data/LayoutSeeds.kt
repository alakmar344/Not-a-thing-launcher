package com.nothinglauncher.data

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.nothinglauncher.AppInfo
import com.nothinglauncher.BespokeElement
import com.nothinglauncher.LauncherPrefs
import com.nothinglauncher.WorkspacePage
import com.nothinglauncher.model.ItemInfo
import com.nothinglauncher.model.ItemType
import com.nothinglauncher.widgets.WidgetSizing

/** Result of building a full layout: ordered screens + every item with its final position. */
class SeededLayout(val screens: List<Long>, val items: List<ItemInfo>)

/**
 * Converts the v1–v8 SharedPreferences layout into v9 grid items.
 *
 * v8 pages were vertical stacks (elements → widgets → enlarged apps → folders → app grid);
 * we keep that reading order on the new cell grid and keep the app grid's relative slots.
 * Anything that doesn't fit spills onto an extra page inserted right after its original page,
 * so no app, folder, widget or element a user had is lost.
 */
object V8Migration {

    private const val TAG = "V8Migration"

    fun hasData(prefs: LauncherPrefs): Boolean =
        prefs.v8Pages?.isNotEmpty() == true || prefs.v8DockItems != null || prefs.v8HomeItems != null ||
            prefs.legacyHomePackages != null || prefs.legacyDockPackages != null

    fun migrate(
        context: Context,
        prefs: LauncherPrefs,
        apps: List<AppInfo>,
        columns: Int,
        rows: Int,
        nextId: () -> Long,
        nextScreenId: () -> Long
    ): SeededLayout {
        val byKey = apps.associateBy { it.key }
        fun keyForPackage(pkg: String) = apps.firstOrNull { it.packageName == pkg && !it.isWorkProfile }?.key

        val b = LayoutBuilder(columns, rows, nextId, nextScreenId)
        val awm = try { AppWidgetManager.getInstance(context) } catch (e: Exception) { null }

        var pages: List<WorkspacePage> = prefs.v8Pages.orEmpty()
        if (pages.isEmpty()) {
            val home = prefs.v8HomeItems ?: prefs.legacyHomePackages?.mapNotNull(::keyForPackage) ?: emptyList()
            pages = listOf(
                WorkspacePage(
                    id = "page_0",
                    appKeys = home,
                    elements = listOf(BespokeElement.CLOCK.id, BespokeElement.BATTERY_CALENDAR.id),
                    widgetIds = prefs.v8WidgetIds
                )
            )
        }

        fun appItem(key: String): ItemInfo? = byKey[key]?.let { ItemInfo.forApp(it) }

        pages.forEach { page ->
            val screen = b.newScreen()
            // 1. Bespoke elements, in their stacked order.
            page.elements.distinct().forEach { id ->
                val el = BespokeElement.fromId(id) ?: return@forEach
                val item = ItemInfo(type = ItemType.ELEMENT, elementId = el.id, spanX = el.spanX, spanY = el.spanY)
                b.place(item, screen)
            }
            // 2. Third-party widgets (ids stay bound to the same host id).
            page.widgetIds.distinct().forEach { wid ->
                val info = try { awm?.getAppWidgetInfo(wid) } catch (e: Exception) { null }
                if (info == null) {
                    Log.w(TAG, "Dropping unbound widget $wid")
                    return@forEach
                }
                val span = WidgetSizing.defaultSpan(context, info, columns, rows)
                val item = ItemInfo(
                    type = ItemType.WIDGET,
                    appWidgetId = wid,
                    component = info.provider,
                    packageName = info.provider.packageName,
                    // v8 showed widgets full width; keep that when the provider allows it.
                    spanX = if ((info.resizeMode and android.appwidget.AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0) {
                        columns
                    } else span.first,
                    spanY = span.second
                )
                b.place(item, screen)
            }
            // 3. "Max" (2×2) app icons.
            page.enlargedApps.distinct().forEach { key ->
                val item = appItem(key) ?: return@forEach
                item.spanX = 2
                item.spanY = 2
                b.place(item, screen)
            }
            // 4. Folders.
            page.folders.forEach { f ->
                val contents = f.appKeys.filter { it.isNotEmpty() }.distinct().mapNotNull(::appItem)
                if (contents.isEmpty()) return@forEach
                if (contents.size == 1) {
                    b.place(contents[0], screen)
                    return@forEach
                }
                val folder = ItemInfo(
                    type = ItemType.FOLDER,
                    title = f.name.ifBlank { "FOLDER" },
                    spanX = if (f.isEnlarged) 2 else 1,
                    spanY = if (f.isEnlarged) 2 else 1
                )
                b.place(folder, screen)
                contents.forEachIndexed { rank, item -> b.placeInFolder(item, folder, rank) }
            }
            // 5. App grid, preserving the slot arrangement (empty slots were "" in v8).
            val baseRow = b.firstFreeRow(screen)
            page.appKeys.forEachIndexed { slot, key ->
                if (key.isEmpty()) return@forEachIndexed
                val item = appItem(key) ?: return@forEachIndexed
                b.place(item, screen, slot % columns, baseRow + slot / columns)
            }
        }

        // Dock → hotseat. Extra icons (dock was up to 5 wide) go onto the first page.
        val dockKeys = prefs.v8DockItems
            ?: prefs.legacyDockPackages?.mapNotNull(::keyForPackage)
            ?: emptyList()
        var x = 0
        dockKeys.filter { it.isNotEmpty() }.distinct().forEach { key ->
            val item = appItem(key) ?: return@forEach
            if (x < columns) b.placeInHotseat(item, x++) else b.place(item, b.screens.first())
        }
        if (b.screens.isEmpty()) b.newScreen()
        return SeededLayout(b.screens.toList(), b.items.toList())
    }
}

/** First-run layout: two pages of Nothing elements plus the user's most useful apps. */
object DefaultLayout {

    fun build(
        context: Context,
        apps: List<AppInfo>,
        columns: Int,
        rows: Int,
        nextId: () -> Long,
        nextScreenId: () -> Long
    ): SeededLayout {
        val b = LayoutBuilder(columns, rows, nextId, nextScreenId)
        val main = apps.filter { !it.isWorkProfile }
        val dock = defaultDock(context, main).take(columns)
        val used = dock.map { it.key }.toMutableSet()

        val s0 = b.newScreen()
        b.place(element(BespokeElement.CLOCK), s0)
        if (rows >= 6) b.place(element(BespokeElement.BATTERY_CALENDAR), s0)
        val home = pick(main, used, PREFERRED_HOME, (rows - b.firstFreeRow(s0)).coerceAtLeast(1) * columns)
        home.forEach { b.place(ItemInfo.forApp(it), s0) }

        val s1 = b.newScreen()
        b.place(element(BespokeElement.WEATHER), s1)
        b.place(element(BespokeElement.QUICK_TOGGLES), s1)
        pick(main, used, PREFERRED_SECONDARY, columns).forEach { b.place(ItemInfo.forApp(it), s1) }

        dock.forEachIndexed { i, app -> b.placeInHotseat(ItemInfo.forApp(app), i) }
        return SeededLayout(b.screens.toList(), b.items.toList())
    }

    private fun element(e: BespokeElement) =
        ItemInfo(type = ItemType.ELEMENT, elementId = e.id, spanX = e.spanX, spanY = e.spanY)

    private fun pick(apps: List<AppInfo>, used: MutableSet<String>, preferred: List<String>, count: Int): List<AppInfo> {
        val picked = preferred.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg && it.key !in used } }
            .distinctBy { it.key }
            .toMutableList()
        apps.filter { it.key !in used && picked.none { p -> p.key == it.key } }.forEach { picked += it }
        return picked.take(count).also { list -> list.forEach { used += it.key } }
    }

    private fun defaultDock(context: Context, apps: List<AppInfo>): List<AppInfo> {
        val pm = context.packageManager
        fun pkgFor(intent: Intent): String? = try {
            val ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val pkg = ri?.activityInfo?.packageName
            if (pkg == null || pkg == "android") pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.packageName else pkg
        } catch (e: Exception) {
            null
        }
        val wanted = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://nothing.tech")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        ).mapNotNull(::pkgFor)
        val dock = wanted.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }.distinctBy { it.key }
        return if (dock.isNotEmpty()) dock else apps.take(4)
    }

    private val PREFERRED_HOME = listOf(
        "com.google.android.apps.photos", "com.google.android.gm", "com.google.android.apps.maps",
        "com.google.android.youtube", "com.google.android.calendar", "com.google.android.deskclock",
        "com.android.vending", "com.spotify.music", "com.nothing.camera", "com.android.settings"
    )

    private val PREFERRED_SECONDARY = listOf(
        "com.whatsapp", "com.google.android.apps.docs", "com.android.settings", "com.google.android.apps.nbu.files",
        "com.netflix.mediaclient", "com.slack", "com.discord"
    )
}
