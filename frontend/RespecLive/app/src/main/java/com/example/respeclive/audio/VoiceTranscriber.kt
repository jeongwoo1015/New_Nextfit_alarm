package com.example.respeclive.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TAG = "Transcriber"
private const val TIMEOUT_MS = 20_000L
private const val RATE = 16_000          // 인식기가 받는 샘플레이트 (44.1kHz는 거절)

/**
 * 녹음 파일(m4a)을 글자로 바꿈. 파일을 PCM으로 풀어 기기 음성 인식기에 넣음 (Android 13+, EXTRA_AUDIO_SOURCE).
 * 마이크를 다시 열지 않으므로 녹음과 겹치지 않음. 안 되면 null.
 */
object VoiceTranscriber {

    suspend fun transcribe(context: Context, file: File): String? {
        if (Build.VERSION.SDK_INT < 33 || !file.exists()) return null
        val app = context.applicationContext
        if (!SpeechRecognizer.isRecognitionAvailable(app)) return null

        val pcm = withContext(Dispatchers.IO) { runCatching { decode(file) }.getOrNull() } ?: return null
        val pipe = ParcelFileDescriptor.createPipe()
        val result = CompletableDeferred<String?>()
        var partial: String? = null      // 이 인식기는 최종 결과를 비워 보내기도 해서 마지막 부분 결과를 대신 씀
        fun Bundle?.firstText() =
            this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        val recognizer = withContext(Dispatchers.Main) {
            SpeechRecognizer.createSpeechRecognizer(app).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        result.complete(results.firstText() ?: partial)
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "인식 실패 $error")
                        result.complete(partial)
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults.firstText()?.let { partial = it }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                })
            }
        }
        // 인식기가 읽는 동안 파이프에 씀. 다 쓰고 닫으면 인식기가 소리 끝으로 봄
        withContext(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pcm) }
            } catch (e: Exception) {
                Log.w(TAG, "소리 전달 실패: ${e.message}")
            }
        }
        val text = withTimeoutOrNull(TIMEOUT_MS) { result.await() } ?: partial
        withContext(Dispatchers.Main) { recognizer.destroy() }
        runCatching { pipe[0].close() }
        return text
    }

    /** m4a → 16kHz 16-bit 모노 PCM (여러 채널이면 첫 채널만) */
    private fun decode(file: File): ByteArray? {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null.also { extractor.release() }
        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = java.io.ByteArrayOutputStream()
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    val chunk = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(chunk)
                    if (channels <= 1) {
                        out.write(chunk)
                    } else {
                        val frame = 2 * channels
                        for (p in 0 until chunk.size - frame + 1 step frame) out.write(chunk, p, 2)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return resample(out.toByteArray(), sampleRate)
    }

    /** 선형 보간으로 RATE에 맞춤 (말소리 인식에는 충분) */
    private fun resample(pcm: ByteArray, from: Int): ByteArray {
        if (from == RATE) return pcm
        val src = ShortArray(pcm.size / 2) { ((pcm[2 * it + 1].toInt() shl 8) or (pcm[2 * it].toInt() and 0xff)).toShort() }
        val n = (src.size.toLong() * RATE / from).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val pos = i.toDouble() * from / RATE
            val j = pos.toInt().coerceAtMost(src.size - 1)
            val k = (j + 1).coerceAtMost(src.size - 1)
            val v = (src[j] + (src[k] - src[j]) * (pos - j)).toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }
}
