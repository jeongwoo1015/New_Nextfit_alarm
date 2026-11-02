package com.example.respeclive.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 짧은 예/아니오 대답을 기기 음성 인식으로 들음 (AI에게 보내지 않음).
 * Live 마이크를 닫아 둔 뒤에 써야 함 (마이크가 겹치지 않게).
 */
object VoiceAnswer {
    private val NO = listOf("아니", "괜찮", "됐어", "됐습니다", "싫어", "안 들", "안들", "그만", "나중", "다음에")
    private val YES = listOf("그래", "응", "네", "예", "좋아", "좋습니다", "들을", "들어", "들려", "틀어", "재생", "어 ", "웅", "콜", "오케이")

    /** true=예, false=아니오, null=못 알아들음(말이 없거나 인식 실패) */
    suspend fun yesNo(context: Context): Boolean? {
        val app = context.applicationContext
        if (!SpeechRecognizer.isRecognitionAvailable(app)) return null
        val text = withContext(Dispatchers.Main) { listenOnce(app) } ?: return null
        return classify(text)
    }

    fun classify(text: String): Boolean? {
        val t = " ${text.trim()} "
        return when {
            NO.any { it in t } -> false      // "안 들을래"처럼 '들'이 들어간 거절을 먼저 거름
            YES.any { it in t } -> true
            else -> null
        }
    }

    private suspend fun listenOnce(app: Context): String? = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(app)
        val main = Handler(Looper.getMainLooper())
        var partial: String? = null
        var destroyed = false
        fun destroy() = main.post { if (!destroyed) { destroyed = true; recognizer.destroy() } }   // 인식기는 메인 스레드에서만
        fun finish(text: String?) {
            if (cont.isActive) cont.resume(text)
            destroy()
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) =
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: partial)

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { partial = it }
            }

            override fun onError(error: Int) = finish(partial)
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        cont.invokeOnCancellation { destroy() }
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }
}
