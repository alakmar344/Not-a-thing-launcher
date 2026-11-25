package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Sheets
import com.nothinglauncher.Tactile
import com.nothinglauncher.VoiceMemoRepository

/**
 * Essential Note & Voice Memo Tile (2x2 or 4x2): synced live with Essential Space.
 * Tap note to edit right from home; tap play pill to play the newest voice memo.
 */
class EssentialNoteWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_note
    override val descriptionRes = R.string.element_note_sub
    override val previewRes = R.drawable.preview_widget_note
    override val spanX = 2
    override val spanY = 2
    override val minSpanX = 2
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val prefs = NothingPrefs.get(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_essential_note, host, false)
        val dot = root.findViewById<View>(R.id.noteWidgetDot)
        val body = root.findViewById<TextView>(R.id.noteWidgetBody)
        val memoChip = root.findViewById<View>(R.id.noteWidgetMemoChip)
        val memoIcon = root.findViewById<ImageView>(R.id.noteWidgetMemoIcon)
        val memoText = root.findViewById<TextView>(R.id.noteWidgetMemoText)

        fun bind() {
            AccentTheme.tintDot(dot, ctx)
            val txt = prefs.essentialNote
            body.text = if (txt.isBlank()) ctx.getString(R.string.note_empty_hint) else txt
            body.setTextColor(ctx.getColor(if (txt.isBlank()) R.color.text_secondary else R.color.text_primary))

            val latest = VoiceMemoRepository.listMemos(ctx).firstOrNull()
            memoChip.isVisible = latest != null
            if (latest != null) {
                val playing = VoiceMemoRepository.activePlayingPath == latest.file.absolutePath
                memoIcon.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                memoText.text = latest.label
            }
        }

        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (root.isAttachedToWindow) bind()
        }
        val memoListener: () -> Unit = { if (root.isAttachedToWindow) bind() }

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                prefs.addListener(prefListener)
                VoiceMemoRepository.addListener(memoListener)
                bind()
            }

            override fun onViewDetachedFromWindow(v: View) {
                prefs.removeListener(prefListener)
                VoiceMemoRepository.removeListener(memoListener)
            }
        })

        root.setOnClickListener {
            Tactile.tick(it)
            Sheets.showTextInput(
                ctx,
                ctx.getString(R.string.essential_note_title),
                ctx.getString(R.string.quick_note_hint),
                prefs.essentialNote,
                maxLength = 180
            ) { updated ->
                prefs.essentialNote = updated
                bind()
            }
        }

        memoChip.setOnClickListener {
            val latest = VoiceMemoRepository.listMemos(ctx).firstOrNull() ?: return@setOnClickListener
            Tactile.confirm(it)
            VoiceMemoRepository.togglePlayback(latest)
            bind()
        }

        bind()
        return root
    }
}
