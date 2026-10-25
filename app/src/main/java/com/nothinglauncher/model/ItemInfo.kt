package com.nothinglauncher.model

import android.content.ComponentName
import android.graphics.drawable.Drawable
import com.nothinglauncher.AppInfo

/** Item kinds stored in the layout database. Values are persisted — never renumber. */
object ItemType {
    const val APP = 0
    const val SHORTCUT = 1
    const val FOLDER = 2
    const val WIDGET = 4
    const val ELEMENT = 5

    fun isIcon(type: Int) = type == APP || type == SHORTCUT
}

/** Special container ids. Any positive container id is the id of a folder item. */
object Containers {
    const val DESKTOP = -100L
    const val HOTSEAT = -101L
    const val NONE = -1L
}

/**
 * One thing placed by the user: an app icon, a pinned shortcut, a folder, a hosted
 * AppWidget or a bespoke Nothing element.
 *
 * Instances are owned by [LauncherModel] and only mutated on the main thread; the database
 * receives immutable snapshots ([copy]) on its own writer thread.
 */
class ItemInfo(
    var id: Long = NO_ID,
    var type: Int,
    var container: Long = Containers.NONE,
    var screenId: Long = -1,
    var cellX: Int = -1,
    var cellY: Int = -1,
    var spanX: Int = 1,
    var spanY: Int = 1,
    /** Order inside a folder. */
    var rank: Int = 0,
    /** Folder name, shortcut label or a user label override. */
    var title: String? = null,
    /** Activity for [ItemType.APP], provider for [ItemType.WIDGET]. */
    var component: ComponentName? = null,
    var packageName: String? = null,
    var shortcutId: String? = null,
    /** UserManager serial number of the profile the item belongs to. */
    var profileSerial: Long = 0,
    var appWidgetId: Int = -1,
    var elementId: String? = null,
    /** Cached PNG of a pinned shortcut icon (shortcut APIs are unavailable when not default home). */
    var iconBlob: ByteArray? = null,
    /** Free-form JSON options (reserved for item specific settings). */
    var options: String? = null
) {
    // ---- Runtime only, never persisted ----
    @Transient var app: AppInfo? = null
    @Transient var icon: Drawable? = null
    @Transient var label: CharSequence? = null
    /** Shortcut is currently disabled/unavailable (app removed shortcut, profile paused…). */
    @Transient var disabled: Boolean = false

    val isDesktop get() = container == Containers.DESKTOP
    val isHotseat get() = container == Containers.HOTSEAT
    val isInFolder get() = container > 0
    val isIcon get() = ItemType.isIcon(type)

    /** Key matching [AppInfo.key] for app items. */
    val appKey: String?
        get() = component?.let { AppInfo.makeKey(it.packageName, it.className, profileSerial) }

    val targetPackage: String?
        get() = when (type) {
            ItemType.APP -> component?.packageName
            ItemType.WIDGET -> component?.packageName ?: packageName
            else -> packageName
        }

    fun copy(): ItemInfo = ItemInfo(
        id, type, container, screenId, cellX, cellY, spanX, spanY, rank, title, component,
        packageName, shortcutId, profileSerial, appWidgetId, elementId, iconBlob, options
    ).also {
        it.app = app
        it.icon = icon
        it.label = label
        it.disabled = disabled
    }

    fun copyPositionFrom(other: ItemInfo) {
        container = other.container
        screenId = other.screenId
        cellX = other.cellX
        cellY = other.cellY
        spanX = other.spanX
        spanY = other.spanY
        rank = other.rank
    }

    override fun toString(): String =
        "ItemInfo(id=$id type=$type c=$container s=$screenId [$cellX,$cellY ${spanX}x$spanY] r=$rank " +
            "${component?.flattenToShortString() ?: shortcutId ?: elementId ?: appWidgetId})"

    companion object {
        const val NO_ID = -1L

        fun forApp(app: AppInfo): ItemInfo = ItemInfo(
            type = ItemType.APP,
            component = app.componentName,
            packageName = app.packageName,
            profileSerial = app.userSerial,
            title = null
        ).also {
            it.app = app
            it.icon = app.icon
            it.label = app.label
        }
    }
}
