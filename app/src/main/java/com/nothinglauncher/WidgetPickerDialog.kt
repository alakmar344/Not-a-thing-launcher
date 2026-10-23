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
import java.util.Locale
import kotlin.math.max

/** Lists every installed widget grouped by app, with previews. */
object WidgetPickerDialog {

    private sealed class Row {
        data class Header(val appName: String, val icon: Drawable?, val count: Int) : Row()
        data class Widget(val info: AppWidgetProviderInfo, val label: String, val preview: Drawable?, val cols: Int, val rows: Int) : Row()
    }

    fun show(context: Context, scope: LifecycleCoroutineScope, onPick: (AppWidgetProviderInfo) -> Unit): Dialog {
        val b = DialogListBinding.inflate(LayoutInflater.from(context))
        val dialog = Sheets.create(context, b.root, maxHeightFraction = 0.86f)
        b.listTitle.text = "WIDGETS"
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
                    is Row.Widget -> if (r.cols >= 3) 2 else 1
                }
            }
            b.listRecycler.layoutManager = lm
            b.listRecycler.adapter = Adapter(rows) { info -> dialog.dismiss(); onPick(info) }
        }
        return dialog
    }

    private fun loadRows(context: Context): List<Row> {
        val awm = AppWidgetManager.getInstance(context) ?: return emptyList()
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

        val rows = mutableListOf<Row>()
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

    private class Adapter(private val rows: List<Row>, private val onPick: (AppWidgetProviderInfo) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = if (viewType == 0) ItemWidgetHeaderBinding.inflate(inflater, parent, false)
            else ItemWidgetPreviewBinding.inflate(inflater, parent, false)
            return object : RecyclerView.ViewHolder(binding.root) {}.also { it.itemView.tag = binding }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> {
                    val b = holder.itemView.tag as ItemWidgetHeaderBinding
                    b.widgetAppIcon.setImageDrawable(row.icon)
                    b.widgetAppName.text = row.appName
                    b.widgetAppCount.text = String.format(Locale.US, "%02d", row.count)
                }
                is Row.Widget -> {
                    val b = holder.itemView.tag as ItemWidgetPreviewBinding
                    b.widgetPreview.setImageDrawable(row.preview)
                    b.widgetLabel.text = row.label
                    b.widgetSize.text = holder.itemView.context.getString(R.string.widget_size, row.cols, row.rows)
                    b.root.contentDescription = row.label
                    b.root.setOnClickListener { onPick(row.info) }
                }
            }
        }
    }
}
