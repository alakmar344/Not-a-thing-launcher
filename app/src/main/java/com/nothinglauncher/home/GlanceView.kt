package com.nothinglauncher.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.android.launcher3.util.Themes
import com.nothinglauncher.DotMatrixClockView
import com.nothinglauncher.Intents
import com.nothinglauncher.R
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Not a Thing "glance" pinned to the top row of the first home screen, in the slot stock
 * Launcher3 reserves for the search bar: a dot-matrix clock, the date and the battery level.
 * Colours follow Launcher3's workspace text colour, so it reads on light and dark wallpapers.
 */
class GlanceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val clock = DotMatrixClockView(context)
    private val date = TextView(context)
    private val battery = TextView(context)
    private val accent = ContextCompat.getColor(context, R.color.nothing_red)

    private var batteryLevel = -1
    private var charging = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent) else bindDate()
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(8)
        setPadding(pad, 0, pad, 0)

        val textColor = Themes.getAttrColor(context, R.attr.workspaceTextColor)
        val dim = ColorUtils.setAlphaComponent(textColor, 0x40)
        clock.dotColor = textColor
        clock.offColor = dim
        clock.accentColor = accent
        clock.panel = false
        clock.contentDescription = null
        clock.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        clock.setOnClickListener { Intents.openAlarms(it.context, it) }
        clock.onMinuteChanged = { bindDate() }

        val font = ResourcesCompat.getFont(context, R.font.space_mono_bold)
        val shadow = Themes.getAttrColor(context, R.attr.workspaceShadowColor)
        for (tv in listOf(date, battery)) {
            tv.typeface = font
            tv.setTextColor(textColor)
            tv.letterSpacing = 0.08f
            tv.isSingleLine = true
            if (Color.alpha(shadow) > 0) tv.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), shadow)
        }
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        battery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        battery.alpha = 0.8f
        date.setOnClickListener { Intents.openCalendar(it.context, it) }
        battery.setOnClickListener { Intents.openBatterySettings(it.context, it) }

        addView(clock, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.1f).apply {
            topMargin = dp(10); bottomMargin = dp(10)
        })
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(date, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(battery, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
        }
        addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bindDate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        // ACTION_BATTERY_CHANGED is sticky: registering returns the current state immediately.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let(::readBattery)
        bindDate()
    }

    override fun onDetachedFromWindow() {
        try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        super.onDetachedFromWindow()
    }

    private fun bindDate() {
        val now = Calendar.getInstance()
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM")
        date.text = DateFormat.format(pattern, now).toString().uppercase(Locale.getDefault())
        updateDescription()
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        batteryLevel = if (level < 0) -1 else (level * 100f / scale).roundToInt()
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        battery.text = when {
            batteryLevel < 0 -> ""
            charging -> context.getString(R.string.nt_glance_battery_charging, batteryLevel)
            else -> context.getString(R.string.nt_glance_battery, batteryLevel)
        }
        // Red is reserved for things that need attention: a low, unplugged battery.
        battery.setTextColor(
            if (!charging && batteryLevel in 0..LOW_BATTERY) accent
            else Themes.getAttrColor(context, R.attr.workspaceTextColor)
        )
        updateDescription()
    }

    private fun updateDescription() {
        contentDescription = listOf(
            DateFormat.getTimeFormat(context).format(Calendar.getInstance().time),
            DateFormat.getLongDateFormat(context).format(Calendar.getInstance().time),
            battery.text
        ).filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val LOW_BATTERY = 15
    }
}
