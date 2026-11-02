package com.example.respeclive.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class VoiceRecorder(
    private val context: Context,
    private val bitRate: Int = 64_000,
    private val sampleRate: Int = 44_100,
) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    fun start(target: File): Boolean {
        cancel()
        target.parentFile?.mkdirs()
        return try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(bitRate)
            r.setAudioSamplingRate(sampleRate)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            currentFile = target
            true
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            false
        }
    }

    /** 정상 종료되면 파일 반환, 너무 짧거나 실패하면 null */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val file = currentFile
        currentFile = null
        return try {
            r.stop()
            file
        } catch (e: RuntimeException) {
            file?.delete()
            null
        } finally {
            r.release()
        }
    }

    fun cancel() {
        stop()?.delete()
    }

    /** 0 ~ 32767 */
    fun amplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (e: Exception) {
        0
    }
}
