package com.nothinglauncher.data

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.nothinglauncher.model.ItemInfo

/**
 * Persistent home-screen layout (items + screens), modelled on AOSP Launcher3's favorites table.
 *
 * Schema changes must only ever be additive and handled in [onUpgrade]; [onDowngrade] keeps the
 * data instead of crashing so a user can roll back to an older APK without losing the layout.
 */
class LauncherDb(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $T_ITEMS (
                _id INTEGER PRIMARY KEY,
                itemType INTEGER NOT NULL,
                container INTEGER NOT NULL,
                screen INTEGER NOT NULL DEFAULT -1,
                cellX INTEGER NOT NULL DEFAULT -1,
                cellY INTEGER NOT NULL DEFAULT -1,
                spanX INTEGER NOT NULL DEFAULT 1,
                spanY INTEGER NOT NULL DEFAULT 1,
                rank INTEGER NOT NULL DEFAULT 0,
                title TEXT,
                component TEXT,
                packageName TEXT,
                shortcutId TEXT,
                profileId INTEGER NOT NULL DEFAULT 0,
                appWidgetId INTEGER NOT NULL DEFAULT -1,
                elementId TEXT,
                icon BLOB,
                options TEXT,
                modified INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS $T_SCREENS (_id INTEGER PRIMARY KEY, rank INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema (v9.0). Future versions add `if (oldVersion < N)` steps.
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Newer schemas are additive, so the columns we know about are still valid. Keep the data.
        Log.w(TAG, "Downgrade $oldVersion -> $newVersion: keeping existing layout data")
    }

    // ---- Reads (background thread) --------------------------------------------------------

    fun loadScreens(): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.query(T_SCREENS, arrayOf("_id"), null, null, null, null, "rank ASC").use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    fun loadItems(): List<ItemInfo> {
        val out = ArrayList<ItemInfo>()
        readableDatabase.query(T_ITEMS, null, null, null, null, null, "container ASC, rank ASC, _id ASC").use { c ->
            val idx = Columns(c)
            while (c.moveToNext()) {
                try {
                    out += idx.read(c)
                } catch (e: Exception) {
                    Log.e(TAG, "Skipping unreadable row", e)
                }
            }
        }
        return out
    }

    fun isEmpty(): Boolean = try {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $T_ITEMS", null).use { c ->
            !c.moveToFirst() || c.getLong(0) == 0L
        } && readableDatabase.rawQuery("SELECT COUNT(*) FROM $T_SCREENS", null).use { c ->
            !c.moveToFirst() || c.getLong(0) == 0L
        }
    } catch (e: Exception) {
        true
    }

    // ---- Writes (writer thread) -----------------------------------------------------------

    fun upsert(item: ItemInfo) {
        writableDatabase.insertWithOnConflict(T_ITEMS, null, values(item), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertAll(items: Collection<ItemInfo>) = transaction { db ->
        items.forEach { db.insertWithOnConflict(T_ITEMS, null, values(it), SQLiteDatabase.CONFLICT_REPLACE) }
    }

    fun delete(ids: Collection<Long>) = transaction { db ->
        ids.forEach { db.delete(T_ITEMS, "_id=?", arrayOf(it.toString())) }
    }

    fun saveScreens(screens: List<Long>) = transaction { db ->
        db.delete(T_SCREENS, null, null)
        screens.forEachIndexed { rank, id ->
            db.insert(T_SCREENS, null, ContentValues().apply { put("_id", id); put("rank", rank) })
        }
    }

    /** Atomically replaces the whole layout (migration, restore from backup, reset). */
    fun replaceAll(screens: List<Long>, items: Collection<ItemInfo>) = transaction { db ->
        db.delete(T_ITEMS, null, null)
        db.delete(T_SCREENS, null, null)
        screens.forEachIndexed { rank, id ->
            db.insert(T_SCREENS, null, ContentValues().apply { put("_id", id); put("rank", rank) })
        }
        items.forEach { db.insertWithOnConflict(T_ITEMS, null, values(it), SQLiteDatabase.CONFLICT_REPLACE) }
    }

    private inline fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun values(item: ItemInfo) = ContentValues().apply {
        put("_id", item.id)
        put("itemType", item.type)
        put("container", item.container)
        put("screen", item.screenId)
        put("cellX", item.cellX)
        put("cellY", item.cellY)
        put("spanX", item.spanX)
        put("spanY", item.spanY)
        put("rank", item.rank)
        put("title", item.title)
        put("component", item.component?.flattenToString())
        put("packageName", item.packageName)
        put("shortcutId", item.shortcutId)
        put("profileId", item.profileSerial)
        put("appWidgetId", item.appWidgetId)
        put("elementId", item.elementId)
        put("icon", item.iconBlob)
        put("options", item.options)
        put("modified", System.currentTimeMillis())
    }

    private class Columns(c: Cursor) {
        val id = c.getColumnIndexOrThrow("_id")
        val type = c.getColumnIndexOrThrow("itemType")
        val container = c.getColumnIndexOrThrow("container")
        val screen = c.getColumnIndexOrThrow("screen")
        val cellX = c.getColumnIndexOrThrow("cellX")
        val cellY = c.getColumnIndexOrThrow("cellY")
        val spanX = c.getColumnIndexOrThrow("spanX")
        val spanY = c.getColumnIndexOrThrow("spanY")
        val rank = c.getColumnIndexOrThrow("rank")
        val title = c.getColumnIndexOrThrow("title")
        val component = c.getColumnIndexOrThrow("component")
        val pkg = c.getColumnIndexOrThrow("packageName")
        val shortcut = c.getColumnIndexOrThrow("shortcutId")
        val profile = c.getColumnIndexOrThrow("profileId")
        val widget = c.getColumnIndexOrThrow("appWidgetId")
        val element = c.getColumnIndexOrThrow("elementId")
        val icon = c.getColumnIndexOrThrow("icon")
        val options = c.getColumnIndexOrThrow("options")

        fun read(c: Cursor) = ItemInfo(
            id = c.getLong(id),
            type = c.getInt(type),
            container = c.getLong(container),
            screenId = c.getLong(screen),
            cellX = c.getInt(cellX),
            cellY = c.getInt(cellY),
            spanX = c.getInt(spanX).coerceAtLeast(1),
            spanY = c.getInt(spanY).coerceAtLeast(1),
            rank = c.getInt(rank),
            title = c.getStringOrNull(title),
            component = c.getStringOrNull(component)?.let { ComponentName.unflattenFromString(it) },
            packageName = c.getStringOrNull(pkg),
            shortcutId = c.getStringOrNull(shortcut),
            profileSerial = c.getLong(profile),
            appWidgetId = c.getInt(widget),
            elementId = c.getStringOrNull(element),
            iconBlob = if (c.isNull(icon)) null else c.getBlob(icon),
            options = c.getStringOrNull(options)
        )

        private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    }

    companion object {
        const val NAME = "launcher.db"
        const val VERSION = 1
        private const val T_ITEMS = "items"
        private const val T_SCREENS = "screens"
        private const val TAG = "LauncherDb"
    }
}
