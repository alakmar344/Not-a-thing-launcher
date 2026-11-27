package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.HardwareControlRepository
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Tactile

/**
 * Glyph Switchboard (4x1): tactile hardware control pills directly on Home
 * (Torch, Essential Focus Mode, Caffeine Screen-Awake, Sound/Vibrate).
 */
class QuickTogglesWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_toggles
    override val descriptionRes = R.string.element_toggles_sub
    override val previewRes = R.drawable.preview_widget_toggles
    override val spanX = 4
    override val spanY = 1
    override val minSpanX = 4
    override val minSpanY = 1

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val prefs = NothingPrefs.get(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_quick_toggles, host, false)

        val torchBtn = root.findViewById<View>(R.id.wtTorch)
        val focusBtn = root.findViewById<View>(R.id.wtFocus)
        val caffeineBtn = root.findViewById<View>(R.id.wtCaffeine)
        val soundBtn = root.findViewById<View>(R.id.wtSound)

        listOf(torchBtn, focusBtn, caffeineBtn, soundBtn).forEach { Tactile.press(it, 0.94f) }

        fun bind() {
            val accent = AccentTheme.accentColor(ctx)
            val normal = ctx.getColor(R.color.text_primary)
            val muted = ctx.getColor(R.color.text_secondary)

            fun style(btn: View, iconId: Int, labelId: Int, active: Boolean) {
                btn.setBackgroundResource(if (active) R.drawable.bg_tile_active else R.drawable.bg_tile_dark)
                btn.findViewById<ImageView>(iconId).setColorFilter(if (active) accent else normal)
                btn.findViewById<TextView>(labelId).setTextColor(
                    if (active) ctx.getColor(R.color.pill_active_text) else muted
                )
            }

            style(torchBtn, R.id.wtTorchIcon, R.id.wtTorchLabel, HardwareControlRepository.torchOn)
            style(focusBtn, R.id.wtFocusIcon, R.id.wtFocusLabel, prefs.focusMode)
            style(caffeineBtn, R.id.wtCaffeineIcon, R.id.wtCaffeineLabel, prefs.caffeineEnabled)
            val ringer = HardwareControlRepository.ringerMode(ctx)
            style(soundBtn, R.id.wtSoundIcon, R.id.wtSoundLabel, ringer != AudioManager.RINGER_MODE_NORMAL)
            soundBtn.findViewById<TextView>(R.id.wtSoundLabel).setText(
                when (ringer) {
                    AudioManager.RINGER_MODE_VIBRATE -> R.string.sound_vibrate
                    AudioManager.RINGER_MODE_SILENT -> R.string.sound_silent
                    else -> R.string.toggle_sound
                }
            )
        }

        val torchListener: (Boolean) -> Unit = { if (root.isAttachedToWindow) bind() }
        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (root.isAttachedToWindow) bind()
        }

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                HardwareControlRepository.addTorchListener(ctx, torchListener)
                prefs.addListener(prefListener)
                bind()
            }

            override fun onViewDetachedFromWindow(v: View) {
                HardwareControlRepository.removeTorchListener(torchListener)
                prefs.removeListener(prefListener)
            }
        })

        torchBtn.setOnClickListener {
            Tactile.confirm(it)
            HardwareControlRepository.toggleTorch(ctx)
            bind()
        }
        focusBtn.setOnClickListener {
            Tactile.confirm(it)
            prefs.focusMode = !prefs.focusMode
            bind()
        }
        caffeineBtn.setOnClickListener {
            Tactile.confirm(it)
            prefs.caffeineEnabled = !prefs.caffeineEnabled
            bind()
        }
        soundBtn.setOnClickListener {
            Tactile.confirm(it)
            HardwareControlRepository.cycleRingerOrOpenSettings(ctx)
            bind()
        }

        bind()
        return root
    }
}
