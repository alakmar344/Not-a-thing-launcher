package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.DotMatrixView
import com.nothinglauncher.IconFactory
import com.nothinglauncher.Intents
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Sheets
import com.nothinglauncher.Tactile
import com.nothinglauncher.icons.NothingIconLogic

/**
 * Interactive 2x2 Essential Stack / Max Icon Card:
 * - Pick 1 app -> displays a giant 68dp centered Nothing Max Icon + label.
 * - Pick 2..4 apps -> displays a 2x2 directly-tappable circular Nothing icon stack.
 * - Tap header to rename; tap gear icon (or long-press title) to pick apps.
 */
class SmartFolderWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_folder
    override val descriptionRes = R.string.element_folder_sub
    override val previewRes = R.drawable.preview_widget_folder
    override val spanX = 2
    override val spanY = 2
    override val minSpanX = 2
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val prefs = NothingPrefs.get(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_smart_folder, host, false)
        val dot = root.findViewById<View>(R.id.sfDot)
        val title = root.findViewById<TextView>(R.id.sfTitle)
        val editBtn = root.findViewById<View>(R.id.sfEditBtn)
        val maxContainer = root.findViewById<View>(R.id.sfMaxContainer)
        val maxIcon = root.findViewById<ImageView>(R.id.sfMaxIcon)
        val maxLabel = root.findViewById<TextView>(R.id.sfMaxLabel)
        val gridContainer = root.findViewById<View>(R.id.sfGridContainer)
        val slots = listOf<ImageView>(
            root.findViewById(R.id.sfApp0),
            root.findViewById(R.id.sfApp1),
            root.findViewById(R.id.sfApp2),
            root.findViewById(R.id.sfApp3)
        )
        Tactile.press(maxContainer, 0.94f)
        slots.forEach { Tactile.press(it, 0.92f) }

        fun resolveDefaultApps(): List<ComponentName> {
            return try {
                val la = ctx.getSystemService(LauncherApps::class.java) ?: return emptyList()
                la.getActivityList(null, Process.myUserHandle())
                    .asSequence()
                    .filter { it.applicationInfo.packageName != ctx.packageName }
                    .take(4)
                    .map { it.componentName }
                    .toList()
            } catch (_: Throwable) {
                emptyList()
            }
        }

        fun loadActivityInfo(la: LauncherApps?, cn: ComponentName): LauncherActivityInfo? = try {
            la?.getActivityList(cn.packageName, Process.myUserHandle())
                ?.firstOrNull { it.componentName == cn }
        } catch (_: Throwable) {
            null
        }

        fun launchComponent(v: View, cn: ComponentName) {
            Tactile.confirm(v)
            prefs.recordAppLaunch(cn.flattenToString())
            val launch = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
            Intents.start(ctx, launch, v)
        }

        fun bind() {
            AccentTheme.applyWidgetSurface(root, ctx, "folder", defaultLight = false)
            val sc = AccentTheme.surfaceColors(ctx, "folder", defaultLight = false)
            AccentTheme.tintDot(dot, ctx)
            title.setTextColor(sc.textPrimary)
            title.text = prefs.quickFolderTitle
            val configured = prefs.quickFolderApps.mapNotNull { ComponentName.unflattenFromString(it) }
            val style = NothingIconLogic.currentStyle(ctx)
            val la = try { ctx.getSystemService(LauncherApps::class.java) } catch (_: Throwable) { null }

            fun resolveRawIcon(cn: ComponentName, info: LauncherActivityInfo?): android.graphics.drawable.Drawable? {
                val packPkg = prefs.iconPackPackage
                if (packPkg.isNotBlank()) {
                    com.nothinglauncher.icons.IconPackManager.loadIconForComponent(ctx, packPkg, cn)?.let { return it }
                }
                return info?.getBadgedIcon(0) ?: try { ctx.packageManager.getActivityIcon(cn) } catch (_: Throwable) { null }
            }

            if (configured.size == 1) {
                // Single-app Max Icon hero mode
                val cn = configured.first()
                maxContainer.isVisible = true
                gridContainer.isVisible = false
                val info = loadActivityInfo(la, cn)
                val iconSize = (68 * ctx.resources.displayMetrics.density).toInt()
                val raw = resolveRawIcon(cn, info)
                if (raw != null) {
                    val key = "folder:${cn.flattenToString()}:${prefs.iconPackPackage}"
                    maxIcon.setImageDrawable(IconFactory.render(ctx.resources, raw, style, iconSize, cacheKey = key))
                } else {
                    maxIcon.setImageResource(R.drawable.nt_ic_apps)
                }
                val label = prefs.customAppLabels[cn.flattenToString()] ?: info?.label ?: cn.packageName
                maxLabel.text = label
                maxLabel.setTextColor(sc.textPrimary)
                maxContainer.setOnClickListener { launchComponent(it, cn) }
                return
            }

            maxContainer.isVisible = false
            gridContainer.isVisible = true
            val components = if (configured.isNotEmpty()) configured else resolveDefaultApps()
            val iconSize = (42 * ctx.resources.displayMetrics.density).toInt()

            slots.forEachIndexed { i, iv ->
                val cn = components.getOrNull(i)
                if (cn == null) {
                    iv.visibility = View.INVISIBLE
                    iv.setOnClickListener(null)
                } else {
                    iv.visibility = View.VISIBLE
                    val info = loadActivityInfo(la, cn)
                    val raw = resolveRawIcon(cn, info)
                    if (raw != null) {
                        val key = "folder:${cn.flattenToString()}:${prefs.iconPackPackage}"
                        iv.setImageDrawable(IconFactory.render(ctx.resources, raw, style, iconSize, cacheKey = key))
                    } else {
                        iv.setImageResource(R.drawable.nt_ic_apps)
                    }
                    iv.contentDescription = prefs.customAppLabels[cn.flattenToString()] ?: info?.label ?: cn.packageName
                    iv.setOnClickListener { launchComponent(it, cn) }
                }
            }
        }

        fun showAppPicker() {
            val la = try { ctx.getSystemService(LauncherApps::class.java) } catch (_: Throwable) { null } ?: return
            val allApps = try {
                la.getActivityList(null, Process.myUserHandle())
                    .filter { it.applicationInfo.packageName != ctx.packageName }
                    .sortedBy { it.label.toString().lowercase() }
            } catch (_: Throwable) {
                emptyList()
            }
            val selected = prefs.quickFolderApps.toMutableList()
            val d = ctx.resources.displayMetrics.density
            val pad = (16 * d).toInt()
            val sheet = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_sheet)
                setPadding(pad, (14 * d).toInt(), pad, pad)
            }
            sheet.addView(DotMatrixView(ctx).apply {
                text = ctx.getString(R.string.nt_folder_pick_title)
                dotColor = ctx.getColor(R.color.dot_on)
                dotScale = 0.40f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (18 * d).toInt())
            })
            sheet.addView(TextView(ctx).apply {
                setText(R.string.nt_folder_pick_sub)
                textSize = 12f
                setTextColor(ctx.getColor(R.color.text_secondary))
                setPadding(0, (6 * d).toInt(), 0, (10 * d).toInt())
            })
            val scroll = NestedScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (300 * d).toInt())
            }
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            scroll.addView(list)
            sheet.addView(scroll)
            val dlg = Sheets.create(ctx, sheet)
            val style = NothingIconLogic.currentStyle(ctx)
            val iconPx = (30 * d).toInt()
            val accent = AccentTheme.accentColor(ctx)

            data class PickerRow(
                val flat: String,
                val row: LinearLayout,
                val titleTv: TextView,
                val badgeTv: TextView
            )
            val rowRefs = ArrayList<PickerRow>(allApps.size)

            fun updateSelectionUi() {
                rowRefs.forEach { ref ->
                    val idx = selected.indexOf(ref.flat)
                    val picked = idx >= 0
                    ref.row.setBackgroundResource(if (picked) R.drawable.bg_tile_active else R.drawable.bg_tile_dark)
                    ref.titleTv.setTextColor(
                        ctx.getColor(if (picked) R.color.pill_active_text else R.color.text_primary)
                    )
                    ref.badgeTv.isVisible = picked
                    if (picked) ref.badgeTv.text = "#${idx + 1}"
                }
            }

            allApps.forEach { info ->
                val flat = info.componentName.flattenToString()
                val titleTv = TextView(ctx).apply {
                    text = prefs.customAppLabels[flat] ?: info.label
                    textSize = 13.5f
                    setPadding((12 * d).toInt(), 0, (8 * d).toInt(), 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val badgeTv = TextView(ctx).apply {
                    textSize = 11f
                    setTextColor(accent)
                    isVisible = false
                }
                val packPkg = prefs.iconPackPackage
                val rawIcon = if (packPkg.isNotBlank()) {
                    com.nothinglauncher.icons.IconPackManager.loadIconForComponent(ctx, packPkg, info.componentName)
                        ?: info.getBadgedIcon(0)
                } else {
                    info.getBadgedIcon(0)
                }
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (6 * d).toInt() }
                    addView(ImageView(ctx).apply {
                        setImageDrawable(
                            IconFactory.render(ctx.resources, rawIcon, style, iconPx, cacheKey = "picker:$flat:$packPkg")
                        )
                    }, LinearLayout.LayoutParams(iconPx, iconPx))
                    addView(titleTv)
                    addView(badgeTv)
                }
                row.setOnClickListener {
                    Tactile.tick(it)
                    if (selected.contains(flat)) selected.remove(flat)
                    else if (selected.size < 4) selected.add(flat)
                    else {
                        selected.removeAt(0)
                        selected.add(flat)
                    }
                    prefs.quickFolderApps = selected
                    updateSelectionUi()
                    bind()
                }
                rowRefs += PickerRow(flat, row, titleTv, badgeTv)
                list.addView(row)
            }
            updateSelectionUi()
            Sheets.showSafely(ctx, dlg)
        }

        title.setOnClickListener {
            Tactile.tick(it)
            Sheets.showTextInput(
                ctx,
                ctx.getString(R.string.folder_rename_title),
                ctx.getString(R.string.folder_rename_hint),
                prefs.quickFolderTitle,
                maxLength = 16
            ) { name ->
                if (name.isNotBlank()) prefs.quickFolderTitle = name.uppercase()
                bind()
            }
        }
        editBtn.setOnClickListener {
            Tactile.tick(it)
            showAppPicker()
        }

        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (root.isAttachedToWindow) bind()
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                prefs.addListener(prefListener)
                bind()
            }

            override fun onViewDetachedFromWindow(v: View) {
                prefs.removeListener(prefListener)
            }
        })
        bind()
        return root
    }
}
