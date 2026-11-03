package com.nothinglauncher.icons

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import com.android.launcher3.LauncherAppState
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BaseIconFactory.IconOptions
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.LauncherActivityCachingLogic
import com.android.launcher3.icons.LauncherIcons
import com.android.launcher3.util.Themes
import com.nothinglauncher.IconFactory
import com.nothinglauncher.NothingPrefs

/**
 * Launcher3 builds every app icon through this class (R.string.launcher_activity_logic_class).
 * Instead of the system icon shape it renders the Not a Thing v10 icon: the app's glyph in a
 * flat disc (monochrome style) or the artwork clipped to a circle (colour style), for every app
 * in every place – home, dock, folders and the drawer.
 */
class NothingIconLogic(@Suppress("UNUSED_PARAMETER") context: Context) : LauncherActivityCachingLogic() {

    override fun loadIcon(context: Context, info: LauncherActivityInfo): BitmapInfo {
        val app = LauncherAppState.getInstance(context)
        val idp = app.invariantDeviceProfile
        val source = app.iconProvider.getIcon(info, idp.fillResIconDpi)
        val style = currentStyle(context)
        val rendered = IconFactory.render(context.resources, source, style, idp.iconBitmapSize)
        LauncherIcons.obtain(context).use { li ->
            // Already a finished circle: no shrinking, wrapping into the system shape or shadow.
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
        /** Monochrome follows Launcher3's own "themed icons" switch (Home settings). */
        fun currentStyle(context: Context): NothingPrefs.IconStyle =
            if (Themes.isThemedIconEnabled(context)) NothingPrefs.IconStyle.MONO
            else NothingPrefs.IconStyle.COLOR

        fun isNight(context: Context): Int =
            if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES) 1 else 0

        /** Re-renders every cached icon (light/dark switch, icon style change). */
        fun refreshAll(context: Context) {
            val app = LauncherAppState.getInstance(context)
            val idp = app.invariantDeviceProfile
            app.iconCache.updateIconParams(idp.fillResIconDpi, idp.iconBitmapSize)
            app.model.forceReload()
        }
    }
}
