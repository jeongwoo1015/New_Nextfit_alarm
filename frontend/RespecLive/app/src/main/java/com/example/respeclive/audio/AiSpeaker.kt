package com.example.respeclive.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * AI 음성(TTS). 서버 응답 텍스트를 그대로 받아서 재생.
 * 서버 텍스트가 비어 있으면 아무것도 읽지 않고 바로 끝난 것으로 처리.
 * enabled=false이거나 TTS를 쓸 수 없으면 글자 수에 맞는 시간만큼 기다렸다가 끝난 것으로 처리.
 */
class AiSpeaker(
    context: Context,
    private val locale: Locale = Locale.KOREAN,
    private val speechRate: Float = 0.9f,
) {
    private enum class State { INITIALIZING, READY, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var state = State.INITIALIZING
    private var queued: (() -> Unit)? = null
    private var fallback: Runnable? = null
    private var currentId: String? = null
    private var currentDone: (() -> Unit)? = null

    @Volatile
    var enabled = true

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = finish(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finish(utteranceId)
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                val engine = tts
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    val r = engine.setLanguage(locale)
                    state = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        State.FAILED
                    } else {
                        engine.setSpeechRate(speechRate)
                        engine.setOnUtteranceProgressListener(listener)
                        State.READY
                    }
                } else {
                    state = State.FAILED
                }
                queued?.let { queued = null; it() }
            }
        }
    }

    /** serverText: 서버가 내려준 AI 응답 텍스트 */
    fun speak(serverText: String?, onDone: () -> Unit) {
        stop()
        val text = serverText?.trim().orEmpty()
        if (text.isEmpty()) {
            onDone()
            return
        }
        begin(text, onDone)
    }

    private fun begin(text: String, onDone: () -> Unit) {
        currentDone = onDone
        when {
            !enabled || state == State.FAILED -> {
                currentId = "fallback"
                val r = Runnable { finishCurrent() }
                fallback = r
                main.postDelayed(r, (text.length * FALLBACK_MS_PER_CHAR).coerceAtLeast(FALLBACK_MIN_MS))
            }
            state == State.INITIALIZING -> {
                currentId = "queued"
                queued = { begin(text, onDone) }
            }
            else -> {
                val id = UUID.randomUUID().toString()
                currentId = id
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            }
        }
    }

    suspend fun speakAndWait(serverText: String?) = suspendCancellableCoroutine<Unit> { cont ->
        speak(serverText) { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { stop() }
    }

    fun stop() {
        queued = null
        tts?.stop()
        finishCurrent()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun finish(id: String?) {
        main.post { if (id != null && id == currentId) finishCurrent() }
    }

    private fun finishCurrent() {
        val done = currentDone
        currentId = null
        currentDone = null
        fallback?.let { main.removeCallbacks(it) }
        fallback = null
        done?.invoke()
    }

    private companion object {
        const val FALLBACK_MS_PER_CHAR = 90L
        const val FALLBACK_MIN_MS = 1500L
    }
}