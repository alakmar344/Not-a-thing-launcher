package com.nothinglauncher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Zero-asset procedural ambient soundscape generator (`BROWN`, `RAIN`, `40HZ`) for deep work.
 * Streams softly into [AudioTrack] only when explicitly toggled on, stopping cleanly on pause/off.
 */
object FocusSoundEngine {

    enum class Profile(val labelRes: Int) {
        OFF(R.string.nt_sound_off),
        BROWN(R.string.nt_sound_brown),
        RAIN(R.string.nt_sound_rain),
        GAMMA_40HZ(R.string.nt_sound_40hz)
    }

    private const val SAMPLE_RATE = 22050
    private val listeners = CopyOnWriteArraySet<(Profile) -> Unit>()

    @Volatile
    var current: Profile = Profile.OFF
        private set

    @Volatile
    private var playing = false

    @Volatile
    private var liveLevel = 0f

    private var worker: Thread? = null

    fun isPlaying(): Boolean = current != Profile.OFF && playing

    fun level01(): Float = if (current == Profile.OFF) 0f else liveLevel

    fun addListener(listener: (Profile) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Profile) -> Unit) {
        listeners -= listener
    }

    fun cycle(): Profile {
        val next = when (current) {
            Profile.OFF -> Profile.BROWN
            Profile.BROWN -> Profile.RAIN
            Profile.RAIN -> Profile.GAMMA_40HZ
            Profile.GAMMA_40HZ -> Profile.OFF
        }
        setProfile(next)
        return next
    }

    @Synchronized
    fun setProfile(profile: Profile) {
        if (profile == current) return
        current = profile
        if (profile == Profile.OFF) {
            stopInternal()
        } else {
            startInternal()
        }
        listeners.forEach { it(current) }
    }

    @Synchronized
    fun stop() {
        if (current == Profile.OFF && !playing) return
        current = Profile.OFF
        stopInternal()
        listeners.forEach { it(current) }
    }

    private fun startInternal() {
        if (playing && worker?.isAlive == true) return
        playing = true
        val t = Thread({ streamLoop() }, "nothing-focus-sound").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }
        worker = t
        t.start()
    }

    private fun stopInternal() {
        playing = false
        liveLevel = 0f
        worker = null
    }

    private fun streamLoop() {
        val minBuf = try {
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (_: Throwable) {
            -1
        }
        if (minBuf <= 0) {
            // Headless / JVM environment fallback
            while (playing && current != Profile.OFF) {
                liveLevel = 0.45f
                try { Thread.sleep(120L) } catch (_: InterruptedException) { break }
            }
            liveLevel = 0f
            return
        }

        val track = try {
            val frames = 1024
            val bytes = maxOf(minBuf, frames * 4)
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
                bytes,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } catch (_: Throwable) {
            liveLevel = 0f
            return
        }

        try {
            track.play()
        } catch (_: Throwable) {
            try { track.release() } catch (_: Throwable) {}
            liveLevel = 0f
            return
        }

        val state = SynthState()
        val buf = ShortArray(1024 * 2)
        var envelope = 0f
        while (playing && current != Profile.OFF) {
            val p = current
            var peak = 0f
            for (i in 0 until 1024) {
                envelope = (envelope + 0.002f).coerceAtMost(1f)
                val (l, r) = synthesizeSample(p, state)
                val sl = (l * envelope).coerceIn(-0.95f, 0.95f)
                val sr = (r * envelope).coerceIn(-0.95f, 0.95f)
                buf[i * 2] = (sl * 32767f).toInt().toShort()
                buf[i * 2 + 1] = (sr * 32767f).toInt().toShort()
                val m = maxOf(abs(sl), abs(sr))
                if (m > peak) peak = m
            }
            liveLevel = (peak * 2.2f).coerceIn(0.15f, 1f)
            try {
                track.write(buf, 0, buf.size)
            } catch (_: Throwable) {
                break
            }
        }

        liveLevel = 0f
        try { track.pause() } catch (_: Throwable) {}
        try { track.flush() } catch (_: Throwable) {}
        try { track.release() } catch (_: Throwable) {}
    }

    internal class SynthState {
        var rng = 0x5EEDC0DE
        var brownL = 0f
        var brownR = 0f
        var dropEnvL = 0f
        var dropEnvR = 0f
        var phaseL = 0.0
        var phaseR = 0.0

        fun nextSigned(): Float {
            rng = rng * 1664525 + 1013904223
            return ((rng shr 16) and 0xFFFF) / 32768f - 1f
        }
    }

    internal fun synthesizeSample(profile: Profile, state: SynthState): Pair<Float, Float> {
        val n1 = state.nextSigned()
        val n2 = state.nextSigned()
        return when (profile) {
            Profile.OFF -> 0f to 0f
            Profile.BROWN -> {
                state.brownL = (state.brownL + 0.025f * n1) / 1.025f
                state.brownR = (state.brownR + 0.025f * n2) / 1.025f
                (state.brownL * 0.48f) to (state.brownR * 0.48f)
            }
            Profile.RAIN -> {
                state.brownL = (state.brownL + 0.045f * n1) / 1.045f
                state.brownR = (state.brownR + 0.045f * n2) / 1.045f
                if (abs(n1) > 0.992f) state.dropEnvL = 0.28f else state.dropEnvL *= 0.985f
                if (abs(n2) > 0.992f) state.dropEnvR = 0.28f else state.dropEnvR *= 0.985f
                val shimmerL = n1 * 0.055f + n1 * state.dropEnvL
                val shimmerR = n2 * 0.055f + n2 * state.dropEnvR
                (state.brownL * 0.34f + shimmerL) to (state.brownR * 0.34f + shimmerR)
            }
            Profile.GAMMA_40HZ -> {
                state.brownL = (state.brownL + 0.02f * n1) / 1.02f
                state.brownR = (state.brownR + 0.02f * n2) / 1.02f
                state.phaseL = (state.phaseL + 2.0 * PI * 180.0 / SAMPLE_RATE) % (2.0 * PI)
                state.phaseR = (state.phaseR + 2.0 * PI * 220.0 / SAMPLE_RATE) % (2.0 * PI)
                val carrierL = sin(state.phaseL).toFloat() * 0.14f
                val carrierR = sin(state.phaseR).toFloat() * 0.14f
                (carrierL + state.brownL * 0.22f) to (carrierR + state.brownR * 0.22f)
            }
        }
    }
}
