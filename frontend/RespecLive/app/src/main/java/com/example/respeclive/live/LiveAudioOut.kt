package com.example.respeclive.live

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.example.respeclive.audio.CallAudio
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
    @Volatile private var queue = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var job: Job? = null

    private val queuedBytes = AtomicLong(0)
    @Volatile private var playUntilMs = 0L     // 지금까지 보낸 소리가 끝나는 예상 시각
    @Volatile private var epoch = 0            // clear() 할 때마다 증가 (남은 조각 재생 중단용)
    @Volatile private var holdUntilMs = 0L     // 이 시각(elapsedRealtime)까지는 받은 소리를 재생하지 않고 모아 둠
    private val sliceBytes = LiveConfig.OUTPUT_SAMPLE_RATE * 2 * 20 / 1000
    private val bytesPerMs = LiveConfig.OUTPUT_SAMPLE_RATE * 2 / 1000L       // 48

    fun start() {
        if (job != null) return
        val rate = LiveConfig.OUTPUT_SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(CallAudio.VOICE_ATTRIBUTES)
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
        t.setVolume(CallAudio.VOICE_GAIN)
        t.play()
        track = t

        val q = Channel<ByteArray>(Channel.UNLIMITED)
        queue = q
        job = scope.launch {
            for (chunk in q) {
                val myEpoch = epoch
                while (myEpoch == epoch && SystemClock.elapsedRealtime() < holdUntilMs) delay(20)
                queuedBytes.addAndGet(-chunk.size.toLong())
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
        if (job == null) return     // 정지 후 늦게 도착한 조각은 버림 (isSpeaking이 계속 true로 남지 않도록)
        queuedBytes.addAndGet(pcm.size.toLong())
        if (queue.trySend(pcm).isFailure) queuedBytes.addAndGet(-pcm.size.toLong())
    }

    /** until(elapsedRealtime)까지 재생을 미룸. 그동안 온 소리는 버리지 않고 모아 두었다가 이어서 재생 */
    fun holdUntil(until: Long) {
        holdUntilMs = until
    }

    /** 끼어들기: 대기 중인 소리와 재생 버퍼를 모두 버림 */
    fun clear() {
        epoch++
        holdUntilMs = 0L
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

    /** 아직 재생하지 않은 소리 길이 (대기열 + 재생 버퍼) */
    fun pendingMs(): Long =
        queuedBytes.get() / bytesPerMs + maxOf(0L, playUntilMs - SystemClock.elapsedRealtime())

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
