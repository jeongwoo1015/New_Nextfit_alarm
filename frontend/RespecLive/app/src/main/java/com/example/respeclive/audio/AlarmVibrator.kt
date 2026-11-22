package com.example.respeclive.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.respeclive.data.WakeProfile

/**
 * 알람 진동: 볼륨 단계(1~6)에 맞춰 세기를 올리고, 사용자 유형(WakeProfile)에 따라 시작 세기를 낮추거나 높임.
 * 알람 용도로 울려서 무음 모드에서도 진동함. 세기 조절이 안 되는 폰은 진동 길이·쉬는 간격으로만 차이가 남.
 * 설정에서 진동을 끄면 아무것도 하지 않음
 */
class AlarmVibrator(context: Context) {
    private val app = context.applicationContext
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private var profile = WakeProfile.NORMAL
    private var current = -1
    private var running = false

    /** 기상 알람: level은 지금 볼륨 단계 */
    fun start(level: Int, profile: WakeProfile) {
        this.profile = profile
        running = true
        current = -1
        setLevel(level)
    }

    /** 재기상 알람: 이미 한 번 못 일어난 상황이라 유형과 상관없이 가장 세게 */
    fun startStrong() {
        profile = WakeProfile.NORMAL
        running = true
        current = -1
        play(MAX_STEP)
    }

    /** 볼륨 단계가 오를 때 진동도 함께 세짐 */
    fun setLevel(level: Int) {
        if (!running) return
        play(step(level, profile))
    }

    private fun play(step: Int) {
        val v = vibrator ?: return
        if (step == current || !isEnabled(app) || !v.hasVibrator()) return
        current = step
        val (timings, amplitudes) = pattern(step)
        v.cancel()
        if (Build.VERSION.SDK_INT >= 26) {
            val effect = VibrationEffect.createWaveform(timings, amplitudes, 0)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, ALARM_AUDIO)
            }
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(timings, 0, ALARM_AUDIO)
        }
    }

    fun stop() {
        running = false
        current = -1
        vibrator?.cancel()
    }

    companion object {
        const val MAX_STEP = 6
        private const val PREFS = "alarm_vibration"
        private const val KEY_ENABLED = "enabled"

        private val ALARM_AUDIO by lazy {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        }

        /** 진동 세기 단계 = 볼륨 단계 + 유형 보정 (잘 깨는 사용자 -1, 잘 못 깨는 사용자 +2), 1~6 */
        fun step(level: Int, profile: WakeProfile): Int {
            val offset = when (profile) {
                WakeProfile.EASY -> -1
                WakeProfile.NORMAL -> 0
                WakeProfile.HARD -> 2
            }
            return (level + offset).coerceIn(1, MAX_STEP)
        }

        /**
         * 단계별 반복 무늬 (쉼, 진동, 쉼, 진동 … ms / 세기 0~255). 단계가 오를수록 길고 세게, 쉬는 간격은 짧게.
         * 1~2: 짧고 약하게 툭툭, 3: 두 번씩 톡톡, 4~6: 길게 쭉
         */
        fun pattern(step: Int): Pair<LongArray, IntArray> = when (step.coerceIn(1, MAX_STEP)) {
            1 -> longArrayOf(0, 120, 1880) to intArrayOf(0, 70, 0)
            2 -> longArrayOf(0, 180, 1320) to intArrayOf(0, 110, 0)
            3 -> longArrayOf(0, 220, 180, 220, 1080) to intArrayOf(0, 150, 0, 150, 0)
            4 -> longArrayOf(0, 450, 650) to intArrayOf(0, 190, 0)
            5 -> longArrayOf(0, 650, 400) to intArrayOf(0, 230, 0)
            else -> longArrayOf(0, 850, 250) to intArrayOf(0, 255, 0)
        }
    }
}
