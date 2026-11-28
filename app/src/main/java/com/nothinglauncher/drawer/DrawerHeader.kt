package com.nothinglauncher.drawer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Process
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.android.launcher3.Insettable
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.AllAppsStore
import com.nothinglauncher.IconFactory
import com.nothinglauncher.Intents
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Tactile
import com.nothinglauncher.icons.IconPackManager
import com.nothinglauncher.icons.NothingIconLogic
import com.nothinglauncher.settings.NothingSettingsActivity
import java.util.Locale

/**
 * v13 drawer header: dot-matrix "APPS", live count, Grid/List mode switcher, Hidden Vault lock,
 * Settings button, and optional Quick-Launch Recent Apps strip.
 */
class DrawerHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs), Insettable {

    private val listener = AllAppsStore.OnUpdateListener {
        updateCount()
        bindRecents()
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        bindModeChip()
        bindRecents()
    }
    private var store: AllAppsStore<*>? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        findViewById<View>(R.id.drawerSettings).setOnClickListener {
            Tactile.tick(it)
            Intents.start(context, Intent(context, NothingSettingsActivity::class.java), it)
        }
        findViewById<View>(R.id.drawerDesk)?.setOnClickListener {
            Tactile.confirm(it)
            com.nothinglauncher.DeskHudActivity.launch(context)
        }
        findViewById<View>(R.id.drawerVault)?.setOnClickListener {
            Tactile.tick(it)
            val appsView = try {
                Launcher.getLauncher(context).appsView as? NothingAllAppsContainerView
            } catch (_: RuntimeException) {
                null
            }
            appsView?.openVault() ?: HiddenVaultSheet.openWithAuth(context) { }
        }
        findViewById<View>(R.id.drawerModeChip)?.setOnClickListener {
            Tactile.tick(it)
            val prefs = NothingPrefs.get(context)
            prefs.drawerLayoutMode = when (prefs.drawerLayoutMode) {
                NothingPrefs.DrawerLayoutMode.GRID -> NothingPrefs.DrawerLayoutMode.LIST
                NothingPrefs.DrawerLayoutMode.LIST -> NothingPrefs.DrawerLayoutMode.CATEGORIES
                NothingPrefs.DrawerLayoutMode.CATEGORIES -> NothingPrefs.DrawerLayoutMode.GRID
            }
            bindModeChip()
            try {
                (Launcher.getLauncher(context).appsView as? NothingAllAppsContainerView)?.refreshDrawerLayoutMode()
            } catch (_: RuntimeException) {}
        }
        bindModeChip()
    }

    override fun setInsets(insets: Rect) {
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = insets.top
            layoutParams = it
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        store = try {
            Launcher.getLauncher(context).appsView.appsStore.also { it.addUpdateListener(listener) }
        } catch (_: RuntimeException) {
            null
        }
        NothingPrefs.get(context).addListener(prefListener)
        refresh()
    }

    fun refresh() {
        updateCount()
        bindModeChip()
        bindRecents()
    }

    override fun onDetachedFromWindow() {
        store?.removeUpdateListener(listener)
        store = null
        NothingPrefs.get(context).removeListener(prefListener)
        super.onDetachedFromWindow()
    }

    private fun bindModeChip() {
        val chip = findViewById<TextView>(R.id.drawerModeChip) ?: return
        val resId = when (NothingPrefs.get(context).drawerLayoutMode) {
            NothingPrefs.DrawerLayoutMode.GRID -> R.string.drawer_mode_grid
            NothingPrefs.DrawerLayoutMode.LIST -> R.string.drawer_mode_list
            NothingPrefs.DrawerLayoutMode.CATEGORIES -> R.string.drawer_mode_cats
        }
        chip.setText(resId)
    }

    private var lastRecentsSig: String? = null
    private var lastVisibleCount: Int = -1
    private val drawerCountView: TextView? by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.drawerCount) }
    private val recentsScrollView: View? by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.drawerRecentsScroll) }
    private val recentsRowView: LinearLayout? by lazy(LazyThreadSafetyMode.NONE) { findViewById(R.id.drawerRecentsRow) }

    private fun updateCount() {
        val apps = store?.apps ?: return
        val hidden = NothingPrefs.get(context).hiddenPackages
        var visible = 0
        for (i in apps.indices) {
            val pkg = apps[i].componentName?.packageName
            if (pkg == null || pkg !in hidden) visible++
        }
        if (visible == lastVisibleCount) return
        lastVisibleCount = visible
        drawerCountView?.text = String.format(Locale.US, "%03d", visible)
    }

    private fun bindRecents() {
        val scroll = recentsScrollView ?: return
        val row = recentsRowView ?: return
        val prefs = NothingPrefs.get(context)
        if (!prefs.showDrawerRecents) {
            lastRecentsSig = "OFF"
            scroll.isVisible = false
            return
        }
        val hidden = prefs.hiddenPackages
        val rawRecent = prefs.recentComponents
        val recent = ArrayList<ComponentName>(5)
        for (i in rawRecent.indices) {
            val cn = ComponentName.unflattenFromString(rawRecent[i]) ?: continue
            if (cn.packageName !in hidden) {
                recent += cn
                if (recent.size == 5) break
            }
        }
        if (recent.isEmpty()) {
            lastRecentsSig = "EMPTY"
            scroll.isVisible = false
            return
        }
        val style = NothingIconLogic.currentStyle(context)
        val packPkg = prefs.iconPackPackage
        val night = NothingIconLogic.isNight(context)
        val customLabels = prefs.customAppLabels
        val sig = buildString(128) {
            append(style.ordinal).append('|').append(packPkg).append('|').append(night).append('|')
            for (cn in recent) {
                val flat = cn.flattenToString()
                append(flat).append('=').append(customLabels[flat] ?: "").append(';')
            }
        }
        if (sig == lastRecentsSig && row.childCount > 0) {
            scroll.isVisible = true
            return
        }
        lastRecentsSig = sig

        row.removeAllViews()
        val d = resources.displayMetrics.density
        val iconPx = (24 * d).toInt()
        val la = try { context.getSystemService(LauncherApps::class.java) } catch (_: Throwable) { null }

        for (cn in recent) {
            val info = try {
                la?.getActivityList(cn.packageName, Process.myUserHandle())?.firstOrNull { it.componentName == cn }
            } catch (_: Throwable) {
                null
            } ?: continue
            val pill = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_pill_outline)
                setPadding((8 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
                isClickable = true
                isFocusable = true
            }
            val compFlat = cn.flattenToString()
            val cacheKey = "recent:$compFlat:${style.name}:$packPkg:$night"
            val iv = ImageView(context).apply {
                val cached = IconFactory.getCached(resources, style, iconPx, cacheKey)
                setImageDrawable(
                    cached ?: run {
                        val raw = (if (packPkg.isNotBlank()) IconPackManager.loadIconForComponent(context, packPkg, cn) else null)
                            ?: info.getBadgedIcon(0)
                        IconFactory.render(resources, raw, style, iconPx, cacheKey = cacheKey)
                    }
                )
            }
            val tv = TextView(context).apply {
                text = customLabels[compFlat] ?: info.label
                textSize = 11f
                maxLines = 1
                setTextColor(context.getColor(R.color.text_primary))
                setPadding((6 * d).toInt(), 0, 0, 0)
            }
            pill.addView(iv, LayoutParams(iconPx, iconPx))
            pill.addView(tv)
            Tactile.press(pill, 0.96f)
            pill.setOnClickListener {
                Tactile.tick(it)
                prefs.recordAppLaunch(compFlat)
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(cn)
                Intents.start(context, intent, it)
            }
            row.addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * d).toInt()
            })
        }
        scroll.isVisible = row.childCount > 0
    }
}
