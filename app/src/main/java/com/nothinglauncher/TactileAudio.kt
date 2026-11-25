package com.nothinglauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Zero-asset tactile click synthesizer. Generates a crisp 4 ms damped mechanical relay pulse
 * in memory once and plays it only when the user opts into tactile sound in Settings.
 */
object TactileAudio {

    private const val SAMPLE_RATE = 22050
    private const val SAMPLES = 88 // ~4 ms

    @Volatile private var pcm: ShortArray? = null
    @Volatile private var lastPlayedMs = 0L

    private fun buildClickPcm(): ShortArray {
        pcm?.let { return it }
        val buf = ShortArray(SAMPLES)
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

        val am = context.getSystemService(AudioManager::class.java)
        if (am != null && am.ringerMode == AudioManager.RINGER_MODE_SILENT) return

        try {
            val data = buildClickPcm()
            val track = AudioTrack.Builder()
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
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            track.setNotificationMarkerPosition(data.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) {
                    try { t.release() } catch (_: Throwable) {}
                }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        } catch (_: Throwable) {
            // Best-effort under Robolectric/restricted audio contexts.
        }
    }
}
