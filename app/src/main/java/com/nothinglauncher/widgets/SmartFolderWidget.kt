package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.IconFactory
import com.nothinglauncher.Intents
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Sheets
import com.nothinglauncher.Tactile
import com.nothinglauncher.icons.NothingIconLogic

/**
 * Interactive 2x2 Smart Folder / Max App Card:
 * Displays up to 4 directly-tappable circular Nothing icons in a 2x2 square tile (or 1 giant
 * Max Icon if a single app is configured). Tap header to rename; tap empty slot/title to cycle preset.
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
        val slots = listOf<ImageView>(
            root.findViewById(R.id.sfApp0),
            root.findViewById(R.id.sfApp1),
            root.findViewById(R.id.sfApp2),
            root.findViewById(R.id.sfApp3)
        )

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

        fun bind() {
            AccentTheme.tintDot(dot, ctx)
            title.text = prefs.quickFolderTitle
            val configured = prefs.quickFolderApps.mapNotNull { ComponentName.unflattenFromString(it) }
            val components = if (configured.isNotEmpty()) configured else resolveDefaultApps()
            val iconSize = (42 * ctx.resources.displayMetrics.density).toInt()
            val style = NothingIconLogic.currentStyle(ctx)
            val la = try { ctx.getSystemService(LauncherApps::class.java) } catch (_: Throwable) { null }

            slots.forEachIndexed { i, iv ->
                val cn = components.getOrNull(i)
                if (cn == null) {
                    iv.visibility = View.INVISIBLE
                    iv.setOnClickListener(null)
                } else {
                    iv.visibility = View.VISIBLE
                    val info = try {
                        la?.getActivityList(cn.packageName, Process.myUserHandle())
                            ?.firstOrNull { it.componentName == cn }
                    } catch (_: Throwable) {
                        null
                    }
                    val raw = info?.getBadgedIcon(0) ?: try {
                        ctx.packageManager.getActivityIcon(cn)
                    } catch (_: Throwable) {
                        null
                    }
                    if (raw != null) {
                        iv.setImageDrawable(IconFactory.render(ctx.resources, raw, style, iconSize))
                    } else {
                        iv.setImageResource(R.drawable.nt_ic_apps)
                    }
                    iv.contentDescription = info?.label ?: cn.packageName
                    Tactile.press(iv, 0.92f)
                    iv.setOnClickListener {
                        Tactile.confirm(it)
                        prefs.recordAppLaunch(cn.flattenToString())
                        val launch = Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(cn)
                        Intents.start(ctx, launch, it)
                    }
                }
            }
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

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = bind()
            override fun onViewDetachedFromWindow(v: View) {}
        })
        bind()
        return root
    }
}
