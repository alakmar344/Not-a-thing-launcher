package com.nothinglauncher

import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nothinglauncher.databinding.DialogListBinding
import com.nothinglauncher.databinding.ItemWidgetHeaderBinding
import com.nothinglauncher.databinding.ItemWidgetPreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import kotlin.math.max

/** Lists Nothing Bespoke Elements and third-party widgets grouped by app. */
object WidgetPickerDialog {

    private sealed class Row {
        data class Header(val appName: String, val icon: Drawable?, val count: Int) : Row()
        data class Element(val element: BespokeElement) : Row()
        data class Widget(val info: AppWidgetProviderInfo, val label: String, val preview: Drawable?, val cols: Int, val rows: Int) : Row()
    }

    fun show(
        context: Context,
        scope: LifecycleCoroutineScope,
        onPickElement: (BespokeElement) -> Unit,
        onPickWidget: (AppWidgetProviderInfo) -> Unit
    ): Dialog {
        val b = DialogListBinding.inflate(LayoutInflater.from(context))
        val dialog = Sheets.create(context, b.root, maxHeightFraction = 0.86f)
        b.listTitle.text = "ELEMENTS"
        b.listClose.setOnClickListener { dialog.dismiss() }
        b.listEmpty.setText(R.string.no_widgets)
        b.listRecycler.isVisible = false
        dialog.show()

        scope.launch {
            val rows = withContext(Dispatchers.IO) { loadRows(context) }
            if (!dialog.isShowing) return@launch
            b.listEmpty.isVisible = rows.isEmpty()
            b.listRecycler.isVisible = rows.isNotEmpty()
            val lm = GridLayoutManager(context, 2)
            lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = when (val r = rows[position]) {
                    is Row.Header -> 2
                    is Row.Element -> 1
                    is Row.Widget -> if (r.cols >= 3) 2 else 1
                }
            }
            b.listRecycler.layoutManager = lm
            b.listRecycler.adapter = Adapter(
                rows,
                onPickElement = { el -> dialog.dismiss(); onPickElement(el) },
                onPickWidget = { info -> dialog.dismiss(); onPickWidget(info) }
            )
        }
        return dialog
    }

    private fun loadRows(context: Context): List<Row> {
        val rows = mutableListOf<Row>()

        // 1. Nothing Bespoke Elements
        val bespoke = BespokeElement.values().toList()
        rows += Row.Header(context.getString(R.string.bespoke_elements), null, bespoke.size)
        bespoke.forEach { rows += Row.Element(it) }

        // 2. Installed third-party widgets
        val awm = AppWidgetManager.getInstance(context) ?: return rows
        val pm = context.packageManager
        val density = context.resources.displayMetrics.densityDpi
        val providers = try {
            awm.getInstalledProvidersForProfile(Process.myUserHandle())
        } catch (e: Exception) {
            emptyList()
        }
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val byApp = providers.groupBy { it.provider.packageName }
        val apps = byApp.keys.map { pkg ->
            val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: PackageManager.NameNotFoundException) { pkg }
            pkg to name
        }.sortedWith { a, b -> collator.compare(a.second, b.second) }

        apps.forEach { (pkg, name) ->
            val list = byApp[pkg].orEmpty()
            val appIcon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
            rows += Row.Header(name, appIcon, list.size)
            list.forEach { info ->
                val label = try { info.loadLabel(pm) } catch (e: Exception) { null } ?: name
                val preview = try { info.loadPreviewImage(context, density) } catch (e: Exception) { null }
                    ?: try { info.loadIcon(context, density) } catch (e: Exception) { null }
                val d = context.resources.displayMetrics.density
                val cols = max(1, ((info.minWidth / d) + 30).toInt() / 70)
                val rowsN = max(1, ((info.minHeight / d) + 30).toInt() / 70)
                rows += Row.Widget(info, label, preview, cols.coerceAtMost(5), rowsN.coerceAtMost(5))
            }
        }
        return rows
    }

    private class Adapter(
        private val rows: List<Row>,
        private val onPickElement: (BespokeElement) -> Unit,
        private val onPickWidget: (AppWidgetProviderInfo) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Element -> TYPE_ELEMENT
            is Row.Widget -> TYPE_WIDGET
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderHolder(ItemWidgetHeaderBinding.inflate(inf, parent, false))
                else -> PreviewHolder(ItemWidgetPreviewBinding.inflate(inf, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(r)
                is Row.Element -> (holder as PreviewHolder).bindElement(r.element, onPickElement)
                is Row.Widget -> (holder as PreviewHolder).bindWidget(r, onPickWidget)
            }
        }

        private class HeaderHolder(val b: ItemWidgetHeaderBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(r: Row.Header) {
                b.widgetAppName.text = r.appName
                b.widgetAppIcon.setImageDrawable(r.icon)
                b.widgetAppIcon.isVisible = r.icon != null
                b.widgetAppCount.text = r.count.toString()
            }
        }

        private class PreviewHolder(val b: ItemWidgetPreviewBinding) : RecyclerView.ViewHolder(b.root) {
            fun bindElement(el: BespokeElement, onPick: (BespokeElement) -> Unit) {
                val ctx = b.root.context
                b.widgetLabel.text = ctx.getString(el.titleRes)
                b.widgetSize.text = ctx.getString(el.subRes)
                b.widgetPreview.setImageResource(el.iconRes)
                b.widgetPreview.imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_primary)
                b.root.setOnClickListener { onPick(el) }
            }

            fun bindWidget(r: Row.Widget, onPick: (AppWidgetProviderInfo) -> Unit) {
                b.widgetLabel.text = r.label
                b.widgetSize.text = b.root.context.getString(R.string.widget_size, r.cols, r.rows)
                b.widgetPreview.imageTintList = null
                b.widgetPreview.setImageDrawable(r.preview)
                b.root.setOnClickListener { onPick(r.info) }
            }
        }

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_ELEMENT = 1
            private const val TYPE_WIDGET = 2
        }
    }
}
