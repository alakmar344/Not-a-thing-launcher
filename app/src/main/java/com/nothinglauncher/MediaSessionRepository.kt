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
    private var managerListenerRegistered = false
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
        main.post { if (listener in listeners) listener(current) }
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
        unregisterManagerListener()
        detachController()
        update(State(permissionGranted = ctx != null && LauncherSystem.hasNotificationAccess(ctx)))
    }

    fun refresh() {
        val ctx = appContext ?: return
        registerManagerListener(ctx)
        if (!LauncherSystem.hasNotificationAccess(ctx)) {
            // Access was revoked: drop the stale controller and the (now dead) session
            // listener so it is registered again once access is granted back.
            detachController()
            unregisterManagerListener()
            update(State(permissionGranted = false))
            return
        }
        val m = manager ?: return
        val sessions = try {
            m.getActiveSessions(listenerComponent(ctx))
        } catch (e: Exception) {
            // SecurityException when access is revoked between the check and the call.
            null
        }
        if (sessions == null) {
            detachController()
            unregisterManagerListener()
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
        detachController()
        controller = best
        try {
            best?.registerCallback(sessionCallback, main)
        } catch (_: Exception) {
            controller = null
        }
        publish()
    }

    private fun detachController() {
        val c = controller ?: return
        controller = null
        try {
            c.unregisterCallback(sessionCallback)
        } catch (_: Exception) {
        }
    }

    private fun unregisterManagerListener() {
        if (!managerListenerRegistered) return
        managerListenerRegistered = false
        try {
            manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (_: Exception) {
        }
    }

    /** Registers the session-change listener whenever notification access is available. */
    private fun registerManagerListener(ctx: Context) {
        if (managerListenerRegistered) return
        if (!LauncherSystem.hasNotificationAccess(ctx)) return
        val m = manager ?: return
        try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent(ctx))
            managerListenerRegistered = true
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
        val metadata = try {
            c.metadata
        } catch (_: Exception) {
            null
        }
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val playing = isActivelyPlaying(c)
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

    private fun isActivelyPlaying(c: MediaController): Boolean {
        val state = try {
            c.playbackState?.state
        } catch (_: Exception) {
            null
        }
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_CONNECTING
    }

    fun playPause() {
        val c = controller ?: return
        // Same notion of "playing" as the UI, so a buffering track shows PAUSE and pauses.
        try {
            if (isActivelyPlaying(c)) c.transportControls.pause() else c.transportControls.play()
        } catch (_: Exception) {
            // The session died or access was revoked mid-call.
            refresh()
        }
    }

    fun next() {
        try {
            controller?.transportControls?.skipToNext()
        } catch (_: Exception) {
            refresh()
        }
    }

    fun previous() {
        try {
            controller?.transportControls?.skipToPrevious()
        } catch (_: Exception) {
            refresh()
        }
    }
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
