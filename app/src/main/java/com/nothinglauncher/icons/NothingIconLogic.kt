package com.nothinglauncher.icons

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BaseIconFactory.IconOptions
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.LauncherActivityCachingLogic
import com.android.launcher3.icons.LauncherIcons
import com.android.launcher3.util.Themes
import com.nothinglauncher.IconFactory
import com.nothinglauncher.NothingPrefs

/**
 * Launcher3 builds every app icon and label through this class (`R.string.launcher_activity_logic_class`).
 * Supports:
 * - Built-in Nothing styles (`MONO`, `COLOR`, `DOTS` dot-glyph matrix)
 * - Installed third-party icon packs (`NothingPrefs.iconPackPackage`)
 * - Per-app custom display labels (`NothingPrefs.customAppLabels`)
 */
class NothingIconLogic(@Suppress("UNUSED_PARAMETER") context: Context) : LauncherActivityCachingLogic() {

    private var appContext: Context? = context.applicationContext ?: context

    override fun getLabel(objectInfo: LauncherActivityInfo): CharSequence {
        val ctx = appContext
        if (ctx != null) {
            val custom = NothingPrefs.get(ctx).customAppLabels[objectInfo.componentName.flattenToString()]
            if (!custom.isNullOrBlank()) return custom
        }
        return super.getLabel(objectInfo)
    }

    override fun loadIcon(context: Context, info: LauncherActivityInfo): BitmapInfo {
        appContext = context.applicationContext ?: context
        val prefs = NothingPrefs.get(context)
        val app = LauncherAppState.getInstance(context)
        val idp = app.invariantDeviceProfile
        val packDrawable = prefs.iconPackPackage.takeIf { it.isNotBlank() }?.let { pack ->
            IconPackManager.loadIconForComponent(context, pack, info.componentName)
        }
        val source = packDrawable ?: app.iconProvider.getIcon(info, idp.fillResIconDpi)
        val style = currentStyle(context)
        val rendered = IconFactory.render(
            context.resources,
            source,
            style,
            idp.iconBitmapSize,
            cacheKey = info.componentName.flattenToString() + "_" + prefs.iconPackPackage
        )
        LauncherIcons.obtain(context).use { li ->
            return li.createBadgedIconBitmap(
                rendered as? BitmapDrawable ?: return super.loadIcon(context, info),
                IconOptions()
                    .setUser(info.user)
                    .setShrinkNonAdaptiveIcons(false)
                    .setBitmapGenerationMode(BaseIconFactory.MODE_DEFAULT)
            )
        }
    }

    companion object {
        fun currentStyle(context: Context): NothingPrefs.IconStyle {
            val prefs = NothingPrefs.get(context)
            // Keep monochrome/dots synced with Launcher3's themed-icons toggle when changed externally.
            return when {
                !Themes.isThemedIconEnabled(context) && prefs.iconStyle == NothingPrefs.IconStyle.MONO ->
                    NothingPrefs.IconStyle.COLOR
                else -> prefs.iconStyle
            }
        }

        fun applyStyle(context: Context, style: NothingPrefs.IconStyle) {
            val prefs = NothingPrefs.get(context)
            prefs.iconStyle = style
            IconFactory.clearCache()
            val themed = style != NothingPrefs.IconStyle.COLOR
            if (Themes.isThemedIconEnabled(context) != themed) {
                LauncherPrefs.get(context).put(LauncherPrefs.THEMED_ICONS.to(themed))
            } else {
                refreshAll(context)
            }
        }

        fun isNight(context: Context): Int =
            if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES) 1 else 0

        /** Re-renders every cached icon (light/dark switch, icon style, icon pack, or custom rename). */
        fun refreshAll(context: Context) {
            IconFactory.clearCache()
            val app = LauncherAppState.getInstance(context)
            val idp = app.invariantDeviceProfile
            app.iconCache.updateIconParams(idp.fillResIconDpi, idp.iconBitmapSize)
            app.model.forceReload()
        }
    }
}
