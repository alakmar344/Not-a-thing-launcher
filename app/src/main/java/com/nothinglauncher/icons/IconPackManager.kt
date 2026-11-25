package com.nothinglauncher.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers installed third-party Android icon packs (ADW/Nova/Apex/Nothing icon packs) and
 * resolves `appfilter.xml` component -> drawable mappings on demand.
 */
object IconPackManager {

    data class IconPack(val packageName: String, val label: String)

    private val THEME_ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME"
    )

    private val componentMapCache = ConcurrentHashMap<String, Map<String, String>>()

    fun listInstalledPacks(context: Context): List<IconPack> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, IconPack>()
        for (action in THEME_ACTIONS) {
            val resolved = try {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
            } catch (_: Throwable) {
                emptyList()
            }
            for (ri in resolved) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || found.containsKey(pkg)) continue
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                found[pkg] = IconPack(pkg, label)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun clearCache() {
        componentMapCache.clear()
    }

    fun loadIconForComponent(context: Context, packPackage: String, component: ComponentName): Drawable? {
        if (packPackage.isBlank()) return null
        return try {
            val packRes = context.packageManager.getResourcesForApplication(packPackage)
            val map = componentMapCache.getOrPut(packPackage) { parseAppFilter(packRes, packPackage) }
            val key = "ComponentInfo{${component.packageName}/${component.className}}"
            val drawableName = map[key]
                ?: map["ComponentInfo{${component.packageName}/${component.shortClassName}}"]
                ?: return null
            val resId = packRes.getIdentifier(drawableName, "drawable", packPackage)
            if (resId != 0) androidx.core.content.res.ResourcesCompat.getDrawable(packRes, resId, null)
            else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseAppFilter(res: Resources, pkg: String): Map<String, String> {
        val out = HashMap<String, String>(256)
        val xmlId = res.getIdentifier("appfilter", "xml", pkg)
        if (xmlId != 0) {
            try {
                res.getXml(xmlId).use { parser -> readParser(parser, out) }
                if (out.isNotEmpty()) return out
            } catch (_: Throwable) {}
        }
        try {
            res.assets.open("appfilter.xml").use { stream ->
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(stream, "UTF-8")
                readParser(parser, out)
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun readParser(parser: XmlPullParser, out: MutableMap<String, String>) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val comp = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (!comp.isNullOrBlank() && !drawable.isNullOrBlank()) {
                    out[comp] = drawable
                }
            }
            event = parser.next()
        }
    }
}
