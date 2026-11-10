package com.example.respeclive.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.delay

/**
 * 수면 음악 반복 재생 (SleepMusicService가 씀). 서버 음악(GET /music/sleep의 서명 URL)을 먼저 쓰고,
 * 없으면 앱 내 res/raw/sleep_music.* 파일로 대체.
 * 볼륨 = 상황별 기본 볼륨(setBaseVolume) × 페이드 배율. 곡을 바꿀 때는 새 곡을 0에서 올리고 이전 곡을 내려 이어 줌.
 */
class SleepMusicPlayer(private val context: Context) {
    private val resId = context.resources.getIdentifier("sleep_music", "raw", context.packageName)
    private var player: MediaPlayer? = null
    private var base = 0f          // 상황별 기본 볼륨
    private var fade = 1f          // 페이드 배율 (0~1)
    private var paused = false

    /** 지금 곡의 서버 music_id (앱 내 파일이면 null) */
    var musicId: String? = null
        private set

    val hasFallback: Boolean get() = resId != 0

    val isPlaying: Boolean
        get() = try { player?.isPlaying == true } catch (e: IllegalStateException) { false }

    /** 새로 틀기. url이 있으면 서버 음악(비동기 준비), 없으면 앱 내 파일. 재생할 음악이 없으면 false */
    fun play(url: String?, id: String?, baseVolume: Float): Boolean {
        release()
        base = baseVolume
        fade = 1f
        paused = false
        val mp = (if (!url.isNullOrBlank()) createUrl(url) else null) ?: createFallback() ?: return false
        player = mp
        musicId = if (url.isNullOrBlank()) null else id
        return true
    }

    /**
     * 다른 곡으로 서서히 바꿈: 새 곡을 준비해 0에서 올리고, 이전 곡은 내린 뒤 해제.
     * 새 곡을 준비하지 못하면 이전 곡을 그대로 둠 (false)
     */
    suspend fun crossfadeTo(url: String, id: String, ms: Long): Boolean {
        val old = player ?: return play(url, id, base)
        val next = createUrl(url, startVolume = 0f) ?: return false
        // 준비(prepareAsync)를 기다림. 오래 걸리면 포기하고 이전 곡 유지
        var waited = 0L
        while (!next.preparedFlag && !next.failedFlag && waited < PREPARE_TIMEOUT_MS) {
            delay(100)
            waited += 100
        }
        if (!next.preparedFlag) {
            next.mp.release()
            return false
        }
        val steps = 20
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            volume(next.mp, current() * t)
            volume(old, current() * (1 - t))
            delay(ms / steps)
        }
        old.release()
        player = next.mp
        musicId = id
        if (paused) pause()
        return true
    }

    /** 상황별 기본 볼륨 바꾸기 (대화 중 작게, 끝나면 보통) */
    fun setBaseVolume(v: Float) {
        base = v
        player?.let { volume(it, current()) }
    }

    fun pause() {
        paused = true
        try {
            player?.takeIf { it.isPlaying }?.pause()
        } catch (_: IllegalStateException) {
        }
    }

    fun resume() {
        paused = false
        try {
            player?.takeIf { !it.isPlaying && prepared.contains(it) }?.start()
        } catch (_: IllegalStateException) {
        }
    }

    /** 볼륨을 ms 동안 서서히 0까지 낮춘 뒤 멈추고 해제 (잠든 사람이 깨지 않도록 뚝 끊지 않음) */
    suspend fun fadeOutAndRelease(ms: Long) {
        val steps = 20
        for (i in steps - 1 downTo 0) {
            fade = i / steps.toFloat()
            player?.let { volume(it, current()) }
            delay(ms / steps)
        }
        release()
    }

    fun release() {
        player?.let {
            prepared.remove(it)
            it.release()
        }
        player = null
        musicId = null
    }

    // ---------------- 내부 ----------------

    private fun current() = base * fade

    // 준비가 끝난 플레이어 (준비 전에 start하면 오류)
    private val prepared = mutableSetOf<MediaPlayer>()

    private class Pending(val mp: MediaPlayer) {
        @Volatile var preparedFlag = false
        @Volatile var failedFlag = false
    }

    private fun createUrl(url: String): MediaPlayer? = createUrl(url, startVolume = null)?.mp

    /** 서버 음악 준비 시작. startVolume이 null이면 준비되는 대로 지금 볼륨으로 바로 틂(새로 틀기) */
    private fun createUrl(url: String, startVolume: Float?): Pending? {
        val mp = MediaPlayer()
        val pending = Pending(mp)
        return try {
            mp.setAudioAttributes(MUSIC_ATTRIBUTES)
            mp.setDataSource(url)
            mp.isLooping = true
            mp.setOnPreparedListener {
                prepared += it
                pending.preparedFlag = true
                volume(it, startVolume ?: current())
                if (!paused) it.start()
            }
            mp.setOnErrorListener { p, _, _ ->
                pending.failedFlag = true
                prepared.remove(p)
                if (player === p) {
                    p.release()
                    player = null
                    musicId = null
                }
                true
            }
            mp.prepareAsync()
            pending
        } catch (e: Exception) {
            mp.release()
            null
        }
    }

    private fun createFallback(): MediaPlayer? {
        if (resId == 0) return null
        return MediaPlayer.create(context, resId)?.apply {
            isLooping = true
            prepared += this
            volume(this, current())
            if (!paused) start()
        }
    }

    private fun volume(mp: MediaPlayer, v: Float) {
        try {
            mp.setVolume(v, v)
        } catch (_: IllegalStateException) {
        }
    }

    companion object {
        private const val PREPARE_TIMEOUT_MS = 15_000L
        private val MUSIC_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
