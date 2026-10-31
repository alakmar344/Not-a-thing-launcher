package com.nothinglauncher

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real single-slot voice memo recorder used by the Voice Memo element.
 * Files are stored in the app's external files directory, so they survive updates.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedAtElapsed = 0L

    val isRecording: Boolean get() = recorder != null

    /** ElapsedRealtime timestamp for the active recording, or 0 when idle. */
    val recordingStartedAt: Long get() = if (isRecording) startedAtElapsed else 0L

    fun recordingsDir(): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun start(): Boolean {
        if (isRecording) return false
        if (!LauncherSystem.hasRecordAudio(context)) return false
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(recordingsDir(), "memo_$stamp.m4a")
        val rec = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
        } catch (e: Exception) {
            return false
        }
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            currentFile = file
            startedAtElapsed = android.os.SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            try {
                rec.release()
            } catch (_: Exception) {
            }
            file.delete()
            false
        }
    }

    /** Stops the recorder and returns the saved file, or null if it was too short/failed. */
    fun stop(): File? {
        val rec = recorder ?: return null
        val file = currentFile
        recorder = null
        currentFile = null
        // Monotonic clock: a wall-clock change mid-recording must not misjudge the length.
        val tooShort = android.os.SystemClock.elapsedRealtime() - startedAtElapsed < 600
        startedAtElapsed = 0L
        return try {
            // stop() throws a RuntimeException when no valid audio was captured yet.
            rec.stop()
            rec.release()
            if (tooShort || file == null || !file.exists() || file.length() < 512L) {
                file?.delete()
                null
            } else {
                val prefs = LauncherPrefs(context)
                // Single slot: the previous memo is replaced, so do not leave it behind.
                prefs.lastRecordingPath?.let { old ->
                    val previous = File(old)
                    if (previous != file && previous.parentFile == file.parentFile) previous.delete()
                }
                prefs.lastRecordingPath = file.absolutePath
                file
            }
        } catch (e: Exception) {
            try {
                rec.release()
            } catch (_: Exception) {
            }
            file?.delete()
            null
        }
    }

    fun cancel() {
        val rec = recorder ?: return
        recorder = null
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        try {
            rec.release()
        } catch (_: Exception) {
        }
        currentFile?.delete()
        currentFile = null
        startedAtElapsed = 0L
    }
}
