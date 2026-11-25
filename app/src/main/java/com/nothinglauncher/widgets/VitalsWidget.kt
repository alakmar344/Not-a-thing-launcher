package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.DotRingGaugeView
import com.nothinglauncher.HardwareControlRepository
import com.nothinglauncher.Intents
import com.nothinglauncher.R
import com.nothinglauncher.Tactile
import java.util.Locale

/**
 * 2x2 System Vitals tile: Storage ring + free GB + RAM + opt-in Screen Time.
 * Tap cycles Storage / RAM / Screen Time view; long-press/button opens Storage settings.
 */
class VitalsWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_vitals
    override val descriptionRes = R.string.element_vitals_sub
    override val previewRes = R.drawable.preview_widget_vitals
    override val spanX = 2
    override val spanY = 2
    override val minSpanX = 2
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_vitals, host, false)
        val dot = root.findViewById<View>(R.id.vitalsDot)
        val label = root.findViewById<TextView>(R.id.vitalsLabel)
        val ring = root.findViewById<DotRingGaugeView>(R.id.vitalsRing)
        val footer = root.findViewById<TextView>(R.id.vitalsFooter)
        var mode = 0 // 0 = storage, 1 = ram, 2 = screen time

        fun bind() {
            val v = HardwareControlRepository.readVitals(ctx)
            ring.accentColor = AccentTheme.accentColor(ctx)
            AccentTheme.tintDot(dot, ctx)
            when (mode) {
                0 -> {
                    label.setText(R.string.vitals_storage)
                    ring.progress01 = v.storageUsedPct / 100f
                    ring.highlightActive = v.storageUsedPct >= 88
                    ring.centerText = "${v.storageUsedPct}%"
                    ring.subtitle = "USED"
                    footer.text = String.format(Locale.US, "%.1f GB FREE", v.freeStorageGb)
                }
                1 -> {
                    label.setText(R.string.vitals_ram)
                    ring.progress01 = v.ramUsedPct / 100f
                    ring.highlightActive = v.ramUsedPct >= 85
                    ring.centerText = "${v.ramUsedPct}%"
                    ring.subtitle = "MEM"
                    footer.text = String.format(Locale.US, "%.1f / %.1f GB", v.availRamGb, v.totalRamGb)
                }
                else -> {
                    label.setText(R.string.vitals_screen)
                    val mins = v.screenTimeMinutesToday
                    if (mins == null) {
                        ring.progress01 = 0f
                        ring.highlightActive = false
                        ring.centerText = "--"
                        ring.subtitle = "ACCESS"
                        footer.setText(R.string.vitals_tap_usage)
                    } else {
                        val h = mins / 60
                        val m = mins % 60
                        ring.progress01 = (mins / 360f).coerceIn(0f, 1f)
                        ring.highlightActive = mins >= 240
                        ring.centerText = if (h > 0) "${h}H" else "${m}M"
                        ring.subtitle = if (h > 0) "${m}M" else "TODAY"
                        footer.text = "${h}H ${m}M ACTIVE"
                    }
                }
            }
        }

        root.setOnClickListener {
            Tactile.tick(it)
            if (mode == 2 && !HardwareControlRepository.hasUsageAccess(ctx)) {
                Intents.start(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), it)
                return@setOnClickListener
            }
            mode = (mode + 1) % 3
            bind()
        }
        footer.setOnClickListener {
            Tactile.tick(it)
            val intent = when (mode) {
                2 -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                else -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            }
            Intents.start(ctx, intent, it)
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = bind()
            override fun onViewDetachedFromWindow(v: View) {}
        })
        bind()
        return root
    }
}
