package com.nothinglauncher.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.nothinglauncher.AccentTheme
import com.nothinglauncher.Intents
import com.nothinglauncher.MediaArtDotsView
import com.nothinglauncher.MediaDotsView
import com.nothinglauncher.MediaSessionRepository
import com.nothinglauncher.R
import com.nothinglauncher.perf.VisualTier
import java.util.Locale

/**
 * Now playing + transport controls + Bayer halftone dot-matrix album art + output route pill.
 */
class MediaWidget(context: Context) : NothingWidget(context) {
    override val labelRes = R.string.element_media
    override val descriptionRes = R.string.element_media_sub
    override val previewRes = R.drawable.preview_widget_media
    override val spanX = 4
    override val spanY = 2
    override val minSpanX = 3
    override val minSpanY = 2

    override fun createView(host: AppWidgetHostView): View {
        val ctx = host.context
        val root = LayoutInflater.from(ctx).inflate(R.layout.widget_media_player, host, false)
        val dot = root.findViewById<View>(R.id.mediaDot)
        val label = root.findViewById<TextView>(R.id.mediaLabel)
        val route = root.findViewById<TextView>(R.id.mediaRoute)
        val title = root.findViewById<TextView>(R.id.mediaTitle)
        val artist = root.findViewById<TextView>(R.id.mediaArtist)
        val artView = root.findViewById<MediaArtDotsView>(R.id.mediaArtDots)
        val visualizer = root.findViewById<MediaDotsView>(R.id.mediaVisualizer)
        val playPause = root.findViewById<ImageView>(R.id.mediaPlayPause)
        val next = root.findViewById<ImageView>(R.id.mediaNext)
        val prev = root.findViewById<ImageView>(R.id.mediaPrev)
        val useHalftone = VisualTier.current(ctx) != VisualTier.Tier.LITE

        fun apply(state: MediaSessionRepository.State) {
            val accent = AccentTheme.accentColor(ctx)
            AccentTheme.applyWidgetSurface(root, ctx, "media", defaultLight = false)
            val sc = AccentTheme.surfaceColors(ctx, "media", defaultLight = false)
            AccentTheme.tintDot(dot, ctx)
            label.setTextColor(sc.textSecondary)
            route.setTextColor(sc.textSecondary)
            title.setTextColor(sc.textPrimary)
            artist.setTextColor(sc.textSecondary)
            visualizer.accentColor = accent
            visualizer.applySurfaceColors(sc)
            artView.accentColor = accent
            artView.applySurfaceColors(sc)

            val canControl = state.permissionGranted && state.hasSession
            label.setText(if (state.permissionGranted) R.string.media_label else R.string.media_label_empty)
            dot.alpha = if (state.isPlaying) 1f else 0.4f
            route.text = if (canControl) state.outputRoute else ""
            route.isVisible = canControl && state.outputRoute.isNotBlank()
            title.text = when {
                !state.permissionGranted -> ctx.getString(R.string.media_permission_needed)
                !state.hasSession -> ctx.getString(R.string.media_no_session)
                state.title.isBlank() -> ctx.getString(R.string.media_connect_hint)
                else -> state.title
            }
            artist.text = if (canControl) state.artist.uppercase(Locale.getDefault()) else ""
            artist.isVisible = canControl && state.artist.isNotBlank()
            val showArt = useHalftone && canControl && state.artHalftone != null
            artView.isVisible = showArt
            if (showArt) artView.matrix = state.artHalftone
            visualizer.isPlaying = canControl && state.isPlaying
            playPause.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            playPause.contentDescription = ctx.getString(
                if (state.isPlaying) R.string.media_pause else R.string.media_play
            )
            listOf(playPause, next, prev).forEach {
                it.isEnabled = canControl
                it.alpha = if (canControl) 1f else 0.4f
            }
        }

        val prefs = com.nothinglauncher.NothingPrefs.get(ctx)
        val listener: (MediaSessionRepository.State) -> Unit = { state ->
            if (root.isAttachedToWindow) apply(state)
        }
        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (root.isAttachedToWindow) apply(MediaSessionRepository.state)
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                prefs.addListener(prefListener)
                MediaSessionRepository.start(ctx.applicationContext)
                apply(MediaSessionRepository.addListener(listener))
            }

            override fun onViewDetachedFromWindow(v: View) {
                prefs.removeListener(prefListener)
                MediaSessionRepository.removeListener(listener)
            }
        })
        apply(MediaSessionRepository.state)

        root.setOnClickListener {
            val state = MediaSessionRepository.state
            when {
                !state.permissionGranted -> Intents.openNotificationAccess(it.context, it)
                state.packageName.isNotBlank() -> Intents.openApp(it.context, state.packageName, it)
            }
        }
        playPause.setOnClickListener {
            MediaSessionRepository.playPause()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        next.setOnClickListener {
            MediaSessionRepository.next()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        prev.setOnClickListener {
            MediaSessionRepository.previous()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        return root
    }
}
