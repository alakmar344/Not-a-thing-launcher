package com.nothinglauncher.elements

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.nothinglauncher.DotFont
import com.nothinglauncher.R
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Local time plus up to two user-chosen time zones. Each row shows the city, HH:MM in dot
 * font, the hour difference from local time and a ±1 day marker. Until configured the second
 * row is UTC.
 */
class WorldClockElement(context: Context) : ElementView(context) {

    private val d = resources.displayMetrics.density

    private val onPaint = ElementUi.dotOn(context)
    private val accentPaint = ElementUi.accent(accentColor)
    private val dividerPaint = Paint().apply {
        color = ElementUi.color(context, R.color.hairline)
        strokeWidth = maxOf(1f, d * 0.75f)
    }
    private val cityPaint = ElementUi.labelPaint(context, 11f, primary = true)
    private val subPaint = ElementUi.labelPaint(context, 9.5f)
    private val cityH = EcKit.lineHeight(cityPaint)
    private val subH = EcKit.lineHeight(subPaint)

    private class Row(val city: String, val isLocal: Boolean) {
        val time = EcKit.DotLine()
        var detail = ""
    }

    private var zones: List<String> = listOf(UTC)
    private var rows: List<Row> = emptyList()
    private var bound = false

    private val receiver = EcKit.TimeReceiver(
        context,
        arrayOf(
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED
        )
    ) { refresh() }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        setOnClickListener { if (isHostReady) EcKit.openAlarms(host, this) }
    }

    override fun onBound() {
        bound = true
        zones = loadZones()
        refresh()
    }

    override fun onLiveChanged(live: Boolean) {
        if (live) {
            receiver.register()
            refresh()
        } else {
            receiver.unregister()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        receiver.unregister()
    }

    override fun onAccentChanged(color: Int) {
        accentPaint.color = color
        invalidate()
    }

    override fun onSpanChanged(spanX: Int, spanY: Int) {
        invalidate()
    }

    private fun loadZones(): List<String> {
        if (!store.contains(KEY_ZONES)) return listOf(UTC)
        return store.getString(KEY_ZONES, "").orEmpty()
            .split('|')
            .filter { it.isNotBlank() && isValidZone(it) }
            .distinct()
            .take(MAX_ZONES)
    }

    private fun refresh() {
        if (!bound) return
        val is24 = EcKit.is24(context)
        val now = System.currentTimeMillis()
        val localTz = TimeZone.getDefault()
        val localCal = Calendar.getInstance(localTz).apply { timeInMillis = now }
        val localOffset = localTz.getOffset(now)

        val list = ArrayList<Row>(zones.size + 1)
        val local = Row(cityOf(localTz.id), true)
        local.time.set(EcKit.hm(localCal, is24))
        local.detail = joinDetail(if (is24) "" else EcKit.amPm(localCal), context.getString(R.string.ec_world_local))
        list.add(local)

        for (id in zones) {
            val tz = TimeZone.getTimeZone(id)
            val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
            val row = Row(cityOf(id), false)
            row.time.set(EcKit.hm(cal, is24))
            val minutes = (tz.getOffset(now) - localOffset) / 60_000
            val dayDiff = compareDays(cal, localCal)
            val diffText = if (minutes == 0) context.getString(R.string.ec_world_same) else formatDiff(minutes)
            val dayText = when {
                dayDiff > 0 -> "+1"
                dayDiff < 0 -> "-1"
                else -> ""
            }
            row.detail = joinDetail(if (is24) "" else EcKit.amPm(cal), diffText, dayText)
            list.add(row)
        }
        rows = list
        contentDescription = list.joinToString(". ") { r ->
            context.getString(R.string.ec_world_cd, r.city, r.time.text, r.detail)
        }
        invalidate()
    }

    private fun joinDetail(vararg parts: String): String =
        parts.filter { it.isNotEmpty() }.joinToString(" · ").uppercase(Locale.getDefault())

    private fun compareDays(a: Calendar, b: Calendar): Int {
        val ya = a.get(Calendar.YEAR)
        val yb = b.get(Calendar.YEAR)
        if (ya != yb) return ya.compareTo(yb)
        return a.get(Calendar.DAY_OF_YEAR).compareTo(b.get(Calendar.DAY_OF_YEAR))
    }

    private fun formatDiff(minutes: Int): String {
        val sign = if (minutes > 0) "+" else "-"
        val h = abs(minutes) / 60
        val m = abs(minutes) % 60
        return if (m == 0) "$sign${h}H" else String.format(Locale.US, "%s%dH%02d", sign, h, m)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val list = rows
        if (w <= 0f || h <= 0f || list.isEmpty()) return

        val minRowH = 34f * d
        val maxRows = maxOf(1, floor(h / minRowH).toInt())
        val count = min(list.size, maxRows)
        val rowH = h / count
        val gap = 8f * d

        for (i in 0 until count) {
            val row = list[i]
            val top = i * rowH
            if (i > 0) canvas.drawLine(0f, top, w, top, dividerPaint)

            val cols = row.time.columns.coerceAtLeast(1)
            val pitch = min(min(rowH * 0.6f / DotFont.ROWS, w * 0.55f / cols), 6f * d)
            val timeW = row.time.width(pitch)
            val timeY = top + (rowH - DotFont.ROWS * pitch) / 2f
            row.time.draw(canvas, w - timeW, timeY, pitch, onPaint)

            var textX = 0f
            val textW = w - timeW - gap
            val showDetail = rowH >= cityH + subH + 6f * d && row.detail.isNotEmpty()
            val blockH = if (showDetail) cityH + 2f * d + subH else cityH
            var y = top + (rowH - blockH) / 2f
            if (row.isLocal) {
                val r = cityPaint.textSize * 0.2f
                canvas.drawCircle(r, y + EcKit.ascent(cityPaint) - cityPaint.textSize * 0.33f, r, accentPaint)
                textX = r * 2 + 5f * d
            }
            canvas.drawText(EcKit.ellipsize(row.city, cityPaint, textW - textX), textX, y + EcKit.ascent(cityPaint), cityPaint)
            if (showDetail) {
                y += cityH + 2f * d
                canvas.drawText(EcKit.ellipsize(row.detail, subPaint, textW - textX), textX, y + EcKit.ascent(subPaint), subPaint)
            }
        }
    }

    override val hasSettings: Boolean get() = true

    override fun openSettings() {
        if (!isHostReady) return
        val ctx = host.activity
        val dp = ctx.resources.displayMetrics.density
        val sheet = ElementSheet(ctx, ctx.getString(R.string.ec_world_title))
        val chosen = loadZones().toMutableList()

        sheet.addText(ctx.getString(R.string.ec_world_help))
        val chosenBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        sheet.addView(chosenBox)
        val search = sheet.addField(ctx.getString(R.string.ec_world_search), "", ctx.getString(R.string.ec_world_search_hint))
        val results = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(results, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        sheet.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (220 * dp).toInt()).apply { topMargin = (4 * dp).toInt() }
        )

        val now = System.currentTimeMillis()
        fun describe(id: String): String {
            val region = id.substringBefore('/', "").replace('_', ' ')
            val offset = TimeZone.getTimeZone(id).getOffset(now) / 60_000
            val sign = if (offset >= 0) "+" else "-"
            val utc = if (offset % 60 == 0) "UTC$sign${abs(offset) / 60}"
            else String.format(Locale.US, "UTC%s%d:%02d", sign, abs(offset) / 60, abs(offset) % 60)
            return listOf(cityOf(id), region, utc).filter { it.isNotEmpty() }.joinToString("  ·  ")
        }

        lateinit var renderChosen: () -> Unit
        lateinit var renderResults: () -> Unit
        renderChosen = {
            chosenBox.removeAllViews()
            if (chosen.isEmpty()) {
                chosenBox.addView(
                    TextView(ctx).apply {
                        text = ctx.getString(R.string.ec_world_none)
                        setTextAppearance(R.style.Text_Body_Secondary)
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = (8 * dp).toInt() }
                )
            } else {
                for (id in chosen.toList()) {
                    chosenBox.addView(EcKit.sheetPill(ctx, describe(id), true) {
                        chosen.remove(id)
                        renderChosen()
                        renderResults()
                    })
                }
            }
        }
        renderResults = {
            results.removeAllViews()
            val q = search.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
            val matches = allZones().asSequence()
                .filter { id ->
                    q.isEmpty() || cityOf(id).lowercase(Locale.getDefault()).contains(q) ||
                        id.replace('_', ' ').lowercase(Locale.getDefault()).contains(q)
                }
                .take(MAX_RESULTS)
                .toList()
            if (matches.isEmpty()) {
                results.addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.ec_world_no_results)
                    setTextAppearance(R.style.Text_Body_Secondary)
                })
            } else {
                for (id in matches) {
                    results.addView(EcKit.sheetPill(ctx, describe(id), id in chosen) {
                        if (id !in chosen) {
                            if (chosen.size >= MAX_ZONES) chosen.removeAt(chosen.lastIndex)
                            chosen.add(id)
                        }
                        renderChosen()
                        renderResults()
                    })
                }
            }
        }

        renderChosen()
        renderResults()
        search.doAfterTextChanged { renderResults() }

        sheet.buttons(ctx.getString(R.string.ec_save), {
            store.putString(KEY_ZONES, chosen.joinToString("|"))
            zones = chosen.toList()
            refresh()
            true
        })
        sheet.show(host)
    }

    companion object {
        private const val KEY_ZONES = "zones"
        private const val UTC = "UTC"
        private const val MAX_ZONES = 2
        private const val MAX_RESULTS = 60
        private val REGIONS = arrayOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

        private var cachedZones: List<String>? = null

        private fun allZones(): List<String> {
            cachedZones?.let { return it }
            val list = TimeZone.getAvailableIDs()
                .filter { id -> id == UTC || REGIONS.any { id.startsWith("$it/") } }
                .distinct()
                .sortedBy { cityOf(it) }
            cachedZones = list
            return list
        }

        private fun isValidZone(id: String): Boolean = TimeZone.getTimeZone(id).id == id

        /** "America/New_York" → "NEW YORK", "UTC" → "UTC". */
        fun cityOf(id: String): String =
            id.substringAfterLast('/').replace('_', ' ').uppercase(Locale.getDefault())
    }
}
