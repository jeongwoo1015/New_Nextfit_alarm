package com.example.respeclive.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import kotlin.math.roundToInt

/**
 * 알람음을 반복 재생하고, 알람 볼륨을 1~maxLevel 단계로 조절. stop() 시 원래 볼륨 복원.
 * soundUrl(서버가 내려준 알람/메시지 음원)이 있으면 그것을 재생하고,
 * 없거나 재생에 실패하면 기기 기본 알람음으로 대체.
 */
class AlarmSoundPlayer(
    private val context: Context,
    private val maxLevel: Int = MAX_LEVEL,
) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var originalVolume = -1

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** level: 1 ~ maxLevel, soundUrl: 서버 음원 주소(절대 URL). null이면 기기 기본음 */
    fun start(level: Int = 1, soundUrl: String? = null) {
        stop()
        originalVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        setLevel(level)

        if (!soundUrl.isNullOrBlank() && playUrl(soundUrl)) return
        playDefault()
    }

    /** 네트워크 음원은 비동기로 준비하므로 메인 스레드에서 호출해도 된다 */
    private fun playUrl(url: String): Boolean {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(url)
            mp.isLooping = true
            mp.setOnPreparedListener { if (player === it) it.start() }
            mp.setOnErrorListener { p, _, _ ->
                if (player === p) {
                    p.release()
                    player = null
                    playDefault() // 서버 음원 실패 시 기본 알람음으로
                }
                true
            }
            player = mp
            mp.prepareAsync()
            true
        } catch (e: Exception) {
            mp.release()
            player = null
            false
        }
    }

    private fun playDefault() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            mp.release()
        }
    }

    /** level: 1 ~ maxLevel */
    fun setLevel(level: Int) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val v = (max * level / maxLevel.toFloat()).roundToInt().coerceIn(1, max)
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, v, 0)
        } catch (e: SecurityException) {
            // 방해 금지 모드 등으로 볼륨을 바꿀 수 없는 경우 무시
        }
    }

    fun stop() {
        player?.release()
        player = null
        if (originalVolume >= 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
            } catch (e: SecurityException) {
            }
            originalVolume = -1
        }
    }

    companion object {
        const val MAX_LEVEL = 5
    }
}