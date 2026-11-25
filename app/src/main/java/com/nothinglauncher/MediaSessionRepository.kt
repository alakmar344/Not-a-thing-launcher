package com.nothinglauncher

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.android.launcher3.notification.NotificationListener
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Live media session bridge with background Bayer-halftone album-art quantization and output route detection.
 */
object MediaSessionRepository {

    data class State(
        val permissionGranted: Boolean = false,
        val hasSession: Boolean = false,
        val isPlaying: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val packageName: String = "",
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val outputRoute: String = "",
        val artHalftone: ByteArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is State) return false
            return permissionGranted == other.permissionGranted &&
                hasSession == other.hasSession &&
                isPlaying == other.isPlaying &&
                title == other.title &&
                artist == other.artist &&
                packageName == other.packageName &&
                durationMs == other.durationMs &&
                outputRoute == other.outputRoute &&
                ((artHalftone == null && other.artHalftone == null) ||
                    (artHalftone != null && other.artHalftone != null && artHalftone.contentEquals(other.artHalftone)))
        }

        override fun hashCode(): Int {
            var res = permissionGranted.hashCode()
            res = 31 * res + hasSession.hashCode()
            res = 31 * res + isPlaying.hashCode()
            res = 31 * res + title.hashCode()
            res = 31 * res + artist.hashCode()
            res = 31 * res + packageName.hashCode()
            res = 31 * res + durationMs.hashCode()
            res = 31 * res + outputRoute.hashCode()
            res = 31 * res + (artHalftone?.contentHashCode() ?: 0)
            return res
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nt-media-halftone").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()

    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var started = false
    private var managerListenerRegistered = false
    private var current = State()
    private var lastArtKey = ""
    private var lastHalftone: ByteArray? = null

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
        ComponentName(context, NotificationListener::class.java)

    fun hasNotificationAccess(context: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (_: Exception) {
        false
    }

    val state: State get() = current

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
            manager = try {
                ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            } catch (_: Throwable) {
                null
            }
        }
        registerManagerListener(ctx)
        if (!hasNotificationAccess(ctx)) {
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
        lastArtKey = ""
        lastHalftone = null
        update(State(permissionGranted = ctx != null && hasNotificationAccess(ctx)))
    }

    fun refresh() {
        val ctx = appContext ?: return
        registerManagerListener(ctx)
        if (!hasNotificationAccess(ctx)) {
            detachController()
            unregisterManagerListener()
            update(State(permissionGranted = false))
            return
        }
        val m = manager ?: return
        val sessions = try {
            m.getActiveSessions(listenerComponent(ctx))
        } catch (_: Exception) {
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

    private fun registerManagerListener(ctx: Context) {
        if (managerListenerRegistered || !hasNotificationAccess(ctx)) return
        val m = manager ?: return
        try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent(ctx))
            managerListenerRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun detectRoute(): String {
        val ctx = appContext ?: return ""
        return try {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return ""
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val bt = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID
            }
            if (bt != null) bt.productName?.toString()?.trim()?.uppercase()?.take(14) ?: "BT AUDIO"
            else if (devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }) "HEADSET"
            else "SPEAKER"
        } catch (_: Throwable) {
            ""
        }
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            lastArtKey = ""
            lastHalftone = null
            update(State(permissionGranted = hasNotificationAccess(appContext ?: return)))
            return
        }
        val metadata = try { c.metadata } catch (_: Exception) { null }
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        val pos = try { c.playbackState?.position?.coerceAtLeast(0L) ?: 0L } catch (_: Exception) { 0L }
        val playing = isActivelyPlaying(c)
        val artKey = "${c.packageName}|$title|$artist"

        val artBmp = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (artKey != lastArtKey) {
            lastArtKey = artKey
            lastHalftone = null
            if (artBmp != null && !artBmp.isRecycled) {
                worker.execute {
                    val matrix = try { MediaArtHalftone.quantize(artBmp) } catch (_: Throwable) { null }
                    main.post {
                        if (lastArtKey == artKey && matrix != null) {
                            lastHalftone = matrix
                            current = current.copy(artHalftone = matrix)
                            listeners.forEach { it(current) }
                        }
                    }
                }
            }
        }

        update(
            State(
                permissionGranted = true,
                hasSession = true,
                isPlaying = playing,
                title = title,
                artist = artist,
                packageName = c.packageName,
                durationMs = duration,
                positionMs = pos,
                outputRoute = detectRoute(),
                artHalftone = lastHalftone
            )
        )
    }

    private fun update(state: State) {
        if (state == current) return
        current = state
        main.post { listeners.forEach { it(state) } }
    }

    private fun isActivelyPlaying(c: MediaController): Boolean {
        val state = try { c.playbackState?.state } catch (_: Exception) { null }
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_CONNECTING
    }

    fun playPause() {
        val c = controller ?: return
        try {
            if (isActivelyPlaying(c)) c.transportControls.pause() else c.transportControls.play()
        } catch (_: Exception) {
            refresh()
        }
    }

    fun next() {
        try { controller?.transportControls?.skipToNext() } catch (_: Exception) { refresh() }
    }

    fun previous() {
        try { controller?.transportControls?.skipToPrevious() } catch (_: Exception) { refresh() }
    }
}
