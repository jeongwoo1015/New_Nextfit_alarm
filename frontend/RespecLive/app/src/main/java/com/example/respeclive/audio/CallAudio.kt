package com.example.respeclive.audio

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioManager

/**
 * 통화 화면의 소리를 통화 볼륨 하나로 맞춤.
 * 통화 화면에 있는 동안 통화 모드(에코 제거가 확실히 동작) + 스피커 출력을 유지하고,
 * AI 음성·안내 음성(TTS)·녹음 듣기·어젯밤 한 마디를 모두 VOICE 속성으로 재생함.
 * 화면과 LiveClient가 각각 acquire/release 하므로 Live 연결이 끊겨도 통화 모드가 풀리지 않음.
 * 수면 음악·알람음은 여기에 포함하지 않음 (미디어·알람 볼륨).
 */
object CallAudio {
    /** 통화 볼륨은 최저가 1단계라 그보다 작게 들리도록 앱에서 줄이는 비율 (밤·아침, 모든 음성에 같은 값) */
    const val VOICE_GAIN = 0.1f

    val VOICE_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var holders = 0
    private var prevMode = AudioManager.MODE_NORMAL
    private var speakerOn = true

    @Synchronized
    fun acquire(context: Context) {
        val am = am(context)
        if (holders++ == 0) {
            prevMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        setSpeakerphone(am, speakerOn)
    }

    @Synchronized
    fun release(context: Context) {
        if (holders == 0) return
        if (--holders == 0) {
            val am = am(context)
            setSpeakerphone(am, false)
            am.mode = prevMode
        }
    }

    @Synchronized
    fun setSpeaker(context: Context, on: Boolean) {
        speakerOn = on
        if (holders > 0) setSpeakerphone(am(context), on)
    }

    /** 화면에 있는 동안 폰의 볼륨 버튼이 조절할 볼륨 (통화 중: 통화 볼륨, 수면 음악만 남으면: 미디어 볼륨) */
    fun volumeKeys(context: Context, stream: Int) {
        context.findActivity()?.volumeControlStream = stream
    }

    private fun am(context: Context) = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Suppress("DEPRECATION")
    private fun setSpeakerphone(am: AudioManager, on: Boolean) {
        am.isSpeakerphoneOn = on
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
