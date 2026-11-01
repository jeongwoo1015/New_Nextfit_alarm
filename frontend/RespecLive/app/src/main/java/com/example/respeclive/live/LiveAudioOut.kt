package com.example.respeclive.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "Live"

/**
 * 서버가 보낸 24kHz / 16-bit / 모노 PCM 조각을 받는 대로 재생.
 * 사용자가 끼어들면(Interrupted) clear()로 남은 소리를 즉시 버림.
 */
class LiveAudioOut {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var track: AudioTrack? = null
    private var queue = Channel<ByteArray>(Channel.UNLIMITED)
    private var job: Job? = null

    private val queuedBytes = AtomicLong(0)
    @Volatile private var playUntilMs = 0L     // 지금까지 보낸 소리가 끝나는 예상 시각
    @Volatile private var epoch = 0            // clear() 할 때마다 증가 (남은 조각 재생 중단용)
    private val sliceBytes = LiveConfig.OUTPUT_SAMPLE_RATE * 2 * 20 / 1000
    private val bytesPerMs = LiveConfig.OUTPUT_SAMPLE_RATE * 2 / 1000L       // 48

    fun start() {
        if (job != null) return
        val rate = LiveConfig.OUTPUT_SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, rate))      // 약 0.5초
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t

        val q = Channel<ByteArray>(Channel.UNLIMITED)
        queue = q
        job = scope.launch {
            for (chunk in q) {
                queuedBytes.addAndGet(-chunk.size.toLong())
                val myEpoch = epoch
                var off = 0
                while (off < chunk.size && myEpoch == epoch) {
                    val n = minOf(sliceBytes, chunk.size - off)
                    val w = try {
                        t.write(chunk, off, n)              // 블로킹 쓰기: 버퍼가 비면 진행
                    } catch (e: IllegalStateException) {
                        -1
                    }
                    if (w < 0) {
                        Log.w(TAG, "AudioTrack 쓰기 실패 $w")
                        return@launch
                    }
                    if (w == 0) {
                        delay(5)
                        continue
                    }
                    off += w
                    playUntilMs = maxOf(SystemClock.elapsedRealtime(), playUntilMs) + w / bytesPerMs
                }
            }
        }
    }

    /** 서버에서 받은 PCM 조각을 재생 대기열에 넣음 (바로 반환) */
    fun write(pcm: ByteArray) {
        queuedBytes.addAndGet(pcm.size.toLong())
        queue.trySend(pcm)
    }

    /** 끼어들기: 대기 중인 소리와 재생 버퍼를 모두 버림 */
    fun clear() {
        epoch++
        while (true) {
            val c = queue.tryReceive().getOrNull() ?: break
            queuedBytes.addAndGet(-c.size.toLong())
        }
        val t = track ?: return
        try {
            t.pause()
            t.flush()
            t.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack clear 실패")
        }
        playUntilMs = 0L
    }

    /** AI가 지금 말하고 있는지 (대기열이 있거나 재생이 끝나지 않음) */
    fun isSpeaking(): Boolean =
        queuedBytes.get() > 0 || SystemClock.elapsedRealtime() < playUntilMs

    fun stop() {
        epoch++
        queue.close()
        job?.cancel()
        job = null
        val t = track
        track = null
        try {
            t?.pause()
            t?.flush()
        } catch (_: IllegalStateException) {
        }
        t?.release()
        queuedBytes.set(0)
        playUntilMs = 0L
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
