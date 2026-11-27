package com.example.respeclive.audio

import android.content.Context
import android.os.Bundle
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
                        engine.setAudioAttributes(CallAudio.VOICE_ATTRIBUTES)   // AI 음성과 같은 통화 볼륨
                        // 네트워크 목소리는 연결 상태에 따라 바뀌므로 기기 안의 한국어 목소리 하나로 고정
                        runCatching {
                            engine.voices
                                ?.filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
                                ?.minByOrNull { it.name }
                                ?.let { engine.voice = it }
                        }
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
                val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, CallAudio.VOICE_GAIN) }
                tts?.speak(ttsText(text), TextToSpeech.QUEUE_FLUSH, params, id)
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

/** 소리로 읽으면 이름("물결표", "하트", "샵")이 나오는 기호 → 뺌. 물결표, 장식 기호·이모지(\p{So}), 자음·모음만 쓴 말, ^^ 등 */
private val TTS_DROP = Regex("[~～〜#&*@+=<>\\p{So}\\uFE0F\\u200D]+|[ㄱ-ㅎㅏ-ㅣ]{2,}|\\^\\^+")
/** 쉬는 느낌은 남길 기호 → 쉼표: 가운뎃점, 말줄임표, 슬래시, 하이픈·줄표 */
private val TTS_PAUSE = Regex("\\s*(?:[·…/—–-]|\\.{2,})\\s*")

/**
 * 기기 TTS에 넘길 글: 화면 글은 그대로 두고 소리로만 읽지 않을 기호를 빼거나 쉼표로 바꿈.
 * "좋은 아침이에요~"를 "물결표"까지 읽던 문제 (11/26 실사용 피드백). 마침표·쉼표·느낌표·물음표·따옴표·괄호·%는 그대로
 */
internal fun ttsText(text: String): String = text
    .replace(TTS_DROP, " ")
    .replace(TTS_PAUSE, ", ")
    .replace(Regex(" {2,}"), " ")
    .replace(Regex("\\s+([,.!?])"), "$1")              // 기호를 뺀 자리에 남은 빈칸
    .replace(Regex(",(?:\\s*,)+"), ",")                   // 쉼표가 겹치면 하나로
    .replace(Regex(",+(?=[.!?])"), "")                    // 다른 문장 부호 바로 앞의 쉼표
    .trim()
    .trim(',')
    .trim()
