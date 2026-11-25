package com.example.respeclive.live

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

private const val TAG = "Live"

class LiveAudioIn(context: Context) {
    private val app = context.applicationContext

    @Volatile var muted = false

    @Volatile var level = 0f
        private set

    private var record: AudioRecord? = null
    private var job: Job? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, onChunk: (ByteArray) -> Unit): Boolean {
        if (job != null) return true
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "마이크 권한 없음")
            return false
        }

        val rate = LiveConfig.INPUT_SAMPLE_RATE
        val chunkBytes = rate * 2 * LiveConfig.CHUNK_MS / 1000        // 16kHz x 2바이트 x 40ms = 1280
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, chunkBytes * 4)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        val sid = rec.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sid)?.also { it.setEnabled(true) }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sid)?.also { it.setEnabled(true) }
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(sid)?.also { it.setEnabled(true) }
        }
        Log.d(TAG, "마이크 시작 AEC=${aec != null} NS=${ns != null} AGC=${agc != null}")

        rec.startRecording()
        record = rec

        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(chunkBytes)
            while (isActive) {
                val n = try {
                    rec.read(buf, 0, buf.size)          // 블로킹 읽기
                } catch (e: IllegalStateException) {
                    -1
                }
                if (n < 0) break
                if (n == 0) continue
                level = rms(buf, n)
                if (!muted) onChunk(buf.copyOf(n))
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        val rec = record
        record = null
        try {
            rec?.stop()
        } catch (_: IllegalStateException) {
        }
        rec?.release()
        aec?.release(); aec = null
        ns?.release(); ns = null
        agc?.release(); agc = null
        level = 0f
    }

    /** 16-bit little-endian PCM의 RMS를 0..1로 환산 (말소리가 잘 보이도록 증폭) */
    private fun rms(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < len) {
            val s = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)
            sum += s.toDouble() * s
            count++
            i += 2
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) / 32768.0 * 6.0).toFloat().coerceIn(0f, 1f)
    }
}
