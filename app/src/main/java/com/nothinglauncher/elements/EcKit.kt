package com.nothinglauncher.elements

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.AlarmClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nothinglauncher.DotFont
import com.nothinglauncher.R
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Shared helpers for the time / productivity elements (clock, world clock, stopwatch…). */
object EcKit {

    const val DOT_SCALE = 0.38f

    fun is24(context: Context): Boolean = try {
        DateFormat.is24HourFormat(context)
    } catch (e: Exception) {
        true
    }

    /** "07:30" in 24h, "7:30" in 12h — digits only so it renders in [DotFont]. */
    fun hm(cal: Calendar, is24: Boolean): String {
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        return if (is24) {
            String.format(Locale.US, "%02d:%02d", h, m)
        } else {
            String.format(Locale.US, "%d:%02d", if (h % 12 == 0) 12 else h % 12, m)
        }
    }

    fun amPm(cal: Calendar): String = try {
        DateFormatSymbols.getInstance(Locale.getDefault()).amPmStrings.getOrNull(cal.get(Calendar.AM_PM)).orEmpty()
    } catch (e: Exception) {
        ""
    }

    /** Locale-aware formatter from a skeleton such as "EEEEdMMMM". */
    fun skeleton(skeleton: String): SimpleDateFormat {
        val locale = Locale.getDefault()
        val pattern = try {
            DateFormat.getBestDateTimePattern(locale, skeleton)
        } catch (e: Exception) {
            skeleton
        }
        return try {
            SimpleDateFormat(pattern, locale)
        } catch (e: Exception) {
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        }
    }

    /** Opens the clock app's alarms, falling back to the system date & time settings. */
    fun openAlarms(host: ElementHost, source: View) {
        val pm = source.context.packageManager
        val alarms = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val canShowAlarms = try {
            alarms.resolveActivity(pm) != null
        } catch (e: Exception) {
            false
        }
        if (canShowAlarms && host.startActivitySafely(alarms, source)) return
        host.startActivitySafely(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), source)
    }

    fun ellipsize(text: String, paint: TextPaint, width: Float): String {
        if (width <= 0f) return ""
        return TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()
    }

    fun lineHeight(paint: Paint): Float {
        val fm = paint.fontMetrics
        return fm.descent - fm.ascent
    }

    fun ascent(paint: Paint): Float = -paint.fontMetrics.ascent

    /** Number of dot columns [text] occupies in [DotFont]. */
    fun columns(text: String, spacing: Int = 1): Int {
        if (text.isEmpty()) return 0
        var c = 0
        for (ch in text) c += DotFont.glyph(ch)[0].length
        return c + spacing * (text.length - 1)
    }

    /** Draws a rasterised dot line; the character at [accentIndex] uses [accent] (or unlit when ![accentVisible]). */
    fun drawRaster(
        canvas: Canvas,
        r: DotFont.Raster,
        x: Float,
        y: Float,
        pitch: Float,
        on: Paint,
        off: Paint?,
        accent: Paint? = null,
        accentIndex: Int = -1,
        accentVisible: Boolean = true,
        scale: Float = DOT_SCALE
    ): Float {
        if (pitch <= 0f) return 0f
        val radius = pitch * scale
        for (row in 0 until DotFont.ROWS) {
            val cy = y + (row + 0.5f) * pitch
            val lit = r.lit[row]
            for (col in 0 until r.columns) {
                val cx = x + (col + 0.5f) * pitch
                if (lit[col]) {
                    val isAccent = accent != null && accentIndex >= 0 && r.charIndexAtColumn[col] == accentIndex
                    when {
                        !isAccent -> canvas.drawCircle(cx, cy, radius, on)
                        accentVisible -> canvas.drawCircle(cx, cy, radius, accent!!)
                        off != null -> canvas.drawCircle(cx, cy, radius * 0.8f, off)
                    }
                } else if (off != null) {
                    canvas.drawCircle(cx, cy, radius * 0.8f, off)
                }
            }
        }
        return r.columns * pitch
    }

    /** A line of dot text whose raster is cached until the text changes. */
    class DotLine(private val spacing: Int = 1) {
        var text: String = ""
            private set
        var raster: DotFont.Raster = DotFont.rasterize("", spacing)
            private set
        val columns: Int get() = raster.columns

        /** Returns true when the text changed. */
        fun set(value: String): Boolean {
            if (value == text) return false
            text = value
            raster = DotFont.rasterize(value, spacing)
            return true
        }

        fun width(pitch: Float) = columns * pitch

        fun draw(
            canvas: Canvas, x: Float, y: Float, pitch: Float, on: Paint, off: Paint? = null,
            accent: Paint? = null, accentIndex: Int = -1, accentVisible: Boolean = true
        ): Float = drawRaster(canvas, raster, x, y, pitch, on, off, accent, accentIndex, accentVisible)
    }

    /** Registers a receiver for (protected) system broadcasts only while needed. */
    class TimeReceiver(context: Context, private val actions: Array<String>, private val onEvent: (String) -> Unit) {
        private val appContext = context.applicationContext
        private var registered = false
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                onEvent(intent.action.orEmpty())
            }
        }

        fun register() {
            if (registered) return
            val filter = IntentFilter()
            actions.forEach { filter.addAction(it) }
            registered = try {
                ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun unregister() {
            if (!registered) return
            registered = false
            try {
                appContext.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // already unregistered
            }
        }
    }

    /** A pill row used inside settings sheets (mirrors [ElementSheet.addOption]). */
    fun sheetPill(context: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val d = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            setTextAppearance(R.style.Text_Body)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            stylePill(this, selected)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt()).apply {
                topMargin = (8 * d).toInt()
            }
        }
    }

    fun stylePill(tv: TextView, selected: Boolean) {
        tv.setBackgroundResource(if (selected) R.drawable.bg_pill_white else R.drawable.bg_pill)
        tv.setTextColor(ContextCompat.getColor(tv.context, if (selected) R.color.pill_active_text else R.color.text_primary))
    }

    /** An uppercase label row for settings sheets. */
    fun sheetLabel(context: Context, label: String): TextView {
        val d = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label.uppercase(Locale.getDefault())
            setTextAppearance(R.style.Text_Label)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * d).toInt()
            }
        }
    }
}
