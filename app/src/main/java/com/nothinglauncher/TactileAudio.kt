package com.nothinglauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Zero-asset tactile click synthesizer. Generates a crisp 4 ms damped mechanical relay pulse
 * in memory once, pads it safely to HAL minimum buffer size, and reuses a single static
 * [AudioTrack] on a background audio thread when tactile sound is enabled.
 */
object TactileAudio {

    private const val SAMPLE_RATE = 22050
    private const val SAMPLES = 88 // ~4 ms

    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tactile-audio").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY - 1
        }
    }
    private val trackLock = Any()

    @Volatile private var pcm: ShortArray? = null
    @Volatile private var lastPlayedMs = 0L
    private var cachedTrack: AudioTrack? = null
    private var cachedFrameCount = 0

    private fun buildClickPcm(minFrames: Int): ShortArray {
        val targetFrames = maxOf(SAMPLES, minFrames)
        pcm?.let { if (it.size >= targetFrames) return it }
        val buf = ShortArray(targetFrames)
        for (i in 0 until SAMPLES) {
            val t = i / SAMPLE_RATE.toDouble()
            val env = exp(-t * 1150.0)
            val tone = sin(2.0 * PI * 1950.0 * t) * 0.65 + sin(2.0 * PI * 4200.0 * t) * 0.35
            buf[i] = (tone * env * 9000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        pcm = buf
        return buf
    }

    fun playTick(context: Context) {
        val prefs = NothingPrefs.get(context)
        if (!prefs.tactileSound) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPlayedMs < 24L) return
        lastPlayedMs = now
        val appContext = context.applicationContext ?: context
        audioExecutor.execute { playInternal(appContext) }
    }

    private fun playInternal(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        if (am != null && am.ringerMode == AudioManager.RINGER_MODE_SILENT) return

        synchronized(trackLock) {
            try {
                val minBytes = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBytes <= 0) return
                val minFrames = ((minBytes + 1) / 2).coerceAtLeast(SAMPLES)
                val track = cachedTrack?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } ?: run {
                    val data = buildClickPcm(minFrames)
                    val bufferBytes = data.size * 2
                    val created = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferBytes)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    if (created.state != AudioTrack.STATE_INITIALIZED) {
                        created.release()
                        return
                    }
                    created.write(data, 0, data.size)
                    cachedFrameCount = data.size
                    cachedTrack = created
                    created
                }
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.stop()
                track.reloadStaticData()
                track.play()
            } catch (_: Throwable) {
                try { cachedTrack?.release() } catch (_: Throwable) {}
                cachedTrack = null
            }
        }
    }
}
