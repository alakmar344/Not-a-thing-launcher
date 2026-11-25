package com.nothinglauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Local Voice-to-Dot Memo recorder & player stored inside `filesDir/voice_memos/`.
 * Strictly opt-in (`RECORD_AUDIO` requested only when the user taps Record), and cleanly releases
 * recorder/player resources on pause/stop.
 */
object VoiceMemoRepository {

    data class Memo(val file: File, val name: String, val createdAtMs: Long, val label: String)

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var player: MediaPlayer? = null
    private var playingPath: String? = null
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
        val fmt = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())
        return files.sortedByDescending { it.lastModified() }.map { f ->
            Memo(f, f.nameWithoutExtension, f.lastModified(), fmt.format(Date(f.lastModified())).uppercase(Locale.getDefault()))
        }
    }

    fun startRecording(context: Context): Boolean {
        if (!hasRecordPermission(context)) return false
        stopPlayback()
        stopRecording()
        val file = File(memoDir(context), "MEMO_${System.currentTimeMillis()}.m4a")
        return try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
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
            notifyListeners()
            true
        } catch (_: Throwable) {
            try { file.delete() } catch (_: Throwable) {}
            recorder = null
            recordingFile = null
            false
        }
    }

    fun stopRecording(): File? {
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
        notifyListeners()
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
        if (playingPath == memo.file.absolutePath) {
            stopPlayback()
            return false
        }
        stopRecording()
        stopPlayback()
        return try {
            val p = MediaPlayer()
            p.setDataSource(memo.file.absolutePath)
            p.setOnCompletionListener { stopPlayback() }
            p.prepare()
            p.start()
            player = p
            playingPath = memo.file.absolutePath
            notifyListeners()
            true
        } catch (_: Throwable) {
            stopPlayback()
            false
        }
    }

    fun deleteMemo(memo: Memo) {
        if (playingPath == memo.file.absolutePath) stopPlayback()
        try { memo.file.delete() } catch (_: Throwable) {}
        notifyListeners()
    }

    fun stopPlayback() {
        val p = player ?: run {
            playingPath = null
            return
        }
        player = null
        playingPath = null
        try { p.stop() } catch (_: Throwable) {}
        try { p.release() } catch (_: Throwable) {}
        notifyListeners()
    }

    fun releaseAll() {
        stopRecording()
        stopPlayback()
    }
}
