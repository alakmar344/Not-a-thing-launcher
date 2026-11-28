package com.nothinglauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Local Voice-to-Dot Memo recorder & player stored inside `filesDir/voice_memos/`.
 * Strictly opt-in (`RECORD_AUDIO` requested only when the user taps Record), and cleanly releases
 * recorder/player resources on pause/stop.
 */
object VoiceMemoRepository {

    data class Memo(val file: File, val name: String, val createdAtMs: Long, val label: String)

    private val lock = Any()
    private val memoFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d · HH:mm", Locale.US)

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var recordingFile: File? = null
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var playingPath: String? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    val isRecording: Boolean get() = recorder != null
    val activePlayingPath: String? get() = playingPath

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyListeners() { listeners.forEach { it() } }

    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun memoDir(context: Context): File = File(context.filesDir, "voice_memos").apply { mkdirs() }

    fun listMemos(context: Context): List<Memo> {
        val files = memoDir(context).listFiles { f -> f.isFile && f.name.endsWith(".m4a") } ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val fmt = memoFormatter.withLocale(Locale.getDefault())
        return files.sortedByDescending { it.lastModified() }.map { f ->
            val ts = f.lastModified()
            val lbl = fmt.format(Instant.ofEpochMilli(ts).atZone(zone)).uppercase(Locale.getDefault())
            Memo(f, f.nameWithoutExtension, ts, lbl)
        }
    }

    fun startRecording(context: Context): Boolean {
        if (!hasRecordPermission(context)) return false
        synchronized(lock) {
            stopPlaybackLocked()
            stopRecordingLocked()
            val file = File(memoDir(context), "MEMO_${System.currentTimeMillis()}.m4a")
            var r: MediaRecorder? = null
            val started = try {
                @Suppress("DEPRECATION")
                r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(64_000)
                r.setAudioSamplingRate(22_050)
                r.setOutputFile(file.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                recordingFile = file
                true
            } catch (_: Throwable) {
                try { r?.release() } catch (_: Throwable) {}
                try { file.delete() } catch (_: Throwable) {}
                recorder = null
                recordingFile = null
                false
            }
            if (started) notifyListeners()
            return started
        }
    }

    fun stopRecording(): File? {
        val out = synchronized(lock) { stopRecordingLocked() }
        notifyListeners()
        return out
    }

    private fun stopRecordingLocked(): File? {
        val r = recorder ?: return null
        val file = recordingFile
        recorder = null
        recordingFile = null
        try {
            r.stop()
        } catch (_: Throwable) {
            try { file?.delete() } catch (_: Throwable) {}
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
        return file?.takeIf { it.exists() && it.length() > 0 }
    }

    /** Normalized microphone level 0..1 while recording. */
    fun amplitude01(): Float {
        val r = recorder ?: return 0f
        return try {
            (r.maxAmplitude / 16000f).coerceIn(0.08f, 1f)
        } catch (_: Throwable) {
            0.2f
        }
    }

    fun togglePlayback(memo: Memo): Boolean {
        val playing = synchronized(lock) {
            if (playingPath == memo.file.absolutePath) {
                stopPlaybackLocked()
                false
            } else {
                stopRecordingLocked()
                stopPlaybackLocked()
                var p: MediaPlayer? = null
                try {
                    p = MediaPlayer()
                    p.setDataSource(memo.file.absolutePath)
                    p.setOnCompletionListener { stopPlayback() }
                    p.prepare()
                    p.start()
                    player = p
                    playingPath = memo.file.absolutePath
                    true
                } catch (_: Throwable) {
                    try { p?.release() } catch (_: Throwable) {}
                    stopPlaybackLocked()
                    false
                }
            }
        }
        notifyListeners()
        return playing
    }

    fun deleteMemo(memo: Memo) {
        synchronized(lock) {
            if (playingPath == memo.file.absolutePath) stopPlaybackLocked()
            val name = memo.file.name
            if (memo.file.parentFile?.name == "voice_memos" &&
                name.endsWith(".m4a") &&
                !name.contains("..")
            ) {
                try { memo.file.delete() } catch (_: Throwable) {}
            }
        }
        notifyListeners()
    }

    fun stopPlayback() {
        val changed = synchronized(lock) { stopPlaybackLocked() }
        if (changed) notifyListeners()
    }

    private fun stopPlaybackLocked(): Boolean {
        val p = player ?: run {
            playingPath = null
            return false
        }
        player = null
        playingPath = null
        try { p.stop() } catch (_: Throwable) {}
        try { p.release() } catch (_: Throwable) {}
        return true
    }

    fun releaseAll() {
        synchronized(lock) {
            stopRecordingLocked()
            stopPlaybackLocked()
        }
        notifyListeners()
    }
}
