package com.nothinglauncher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Live media session bridge. Android only exposes other apps' MediaSessions to a
 * notification listener, so the controller is dormant (and honest about it) until the
 * user grants notification access in Settings.
 */
object MediaSessionRepository {

    data class State(
        val permissionGranted: Boolean = false,
        val hasSession: Boolean = false,
        val isPlaying: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val packageName: String = ""
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()

    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var started = false
    private var current = State()

    private val sessionCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() {
            main.post { refresh() }
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        main.post { attachBestController(sessions.orEmpty()) }
    }

    private fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, MediaNotificationListenerService::class.java)

    /** Snapshot of the visible playback state; polled or observed by the media element. */
    val state: State
        get() = current

    fun addListener(listener: (State) -> Unit): State {
        listeners += listener
        main.post { listener(current) }
        return current
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        val ctx = appContext ?: return
        if (!started) {
            started = true
            manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        }
        registerManagerListener(ctx)
        if (!LauncherSystem.hasNotificationAccess(ctx)) {
            update(State(permissionGranted = false))
            return
        }
        refresh()
    }

    fun stop() {
        val ctx = appContext
        started = false
        try {
            if (ctx != null) manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(sessionCallback)
        controller = null
        update(State(permissionGranted = ctx != null && LauncherSystem.hasNotificationAccess(ctx)))
    }

    fun refresh() {
        val ctx = appContext ?: return
        registerManagerListener(ctx)
        if (!LauncherSystem.hasNotificationAccess(ctx)) {
            controller?.unregisterCallback(sessionCallback)
            controller = null
            update(State(permissionGranted = false))
            return
        }
        val m = manager ?: return
        val sessions = try {
            m.getActiveSessions(listenerComponent(ctx))
        } catch (e: Exception) {
            null
        }
        if (sessions == null) {
            update(State(permissionGranted = false))
        } else {
            attachBestController(sessions)
        }
    }

    private fun attachBestController(sessions: List<MediaController>) {
        val best = sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: sessions.firstOrNull()
        val previous = controller
        if (previous?.sessionToken == best?.sessionToken) {
            publish()
            return
        }
        previous?.unregisterCallback(sessionCallback)
        controller = best
        best?.registerCallback(sessionCallback, main)
        publish()
    }

    /** Registers the session-change listener whenever notification access is available. */
    private fun registerManagerListener(ctx: Context) {
        if (!LauncherSystem.hasNotificationAccess(ctx)) return
        val m = manager ?: return
        try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent(ctx))
        } catch (e: Exception) {
            // Permission can be revoked between check and call.
        }
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            update(State(permissionGranted = LauncherSystem.hasNotificationAccess(appContext ?: return)))
            return
        }
        val metadata = c.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val state = c.playbackState?.state
        val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_CONNECTING
        update(
            State(
                permissionGranted = true,
                hasSession = true,
                isPlaying = playing,
                title = title,
                artist = artist,
                packageName = c.packageName
            )
        )
    }

    private fun update(state: State) {
        current = state
        main.post { listeners.forEach { it(state) } }
    }

    fun playPause() {
        val c = controller ?: return
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()

    fun previous() = controller?.transportControls?.skipToPrevious()
}

/** Registered in the manifest so this package can host the notification listener role. */
class MediaNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        val ctx = applicationContext ?: return
        MediaSessionRepository.start(ctx)
        MediaSessionRepository.refresh()
    }

    override fun onListenerDisconnected() {
        MediaSessionRepository.stop()
    }
}
