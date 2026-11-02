package com.example.respeclive.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * 수면 음악 반복 재생. 서버 음악(GET /music/sleep의 서명 URL)을 먼저 쓰고,
 * 없으면 앱 내 res/raw/sleep_music.* 파일로 대체.
 */
class SleepMusicPlayer(
    private val context: Context,
    private val normalVolume: Float = 0.35f,
    private val duckedVolume: Float = 0.08f,
) {
    private val resId = context.resources.getIdentifier("sleep_music", "raw", context.packageName)
    private var player: MediaPlayer? = null
    private var ducked = false

    val isPlaying: Boolean
        get() = try { player?.isPlaying == true } catch (e: IllegalStateException) { false }

    /** url이 있으면 서버 음악(비동기 준비), 없으면 앱 내 파일. 재생할 음악이 없으면 false */
    fun start(url: String? = null): Boolean {
        player?.let {
            it.start()
            return true
        }
        if (!url.isNullOrBlank() && startUrl(url)) return true
        if (resId == 0) return false
        player = MediaPlayer.create(context, resId)?.apply {
            isLooping = true
            applyVolume(this)
            start()
        }
        return player != null
    }

    private fun startUrl(url: String): Boolean {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(url)
            mp.isLooping = true
            mp.setOnPreparedListener {
                if (player === it) {
                    applyVolume(it)
                    it.start()
                }
            }
            mp.setOnErrorListener { p, _, _ ->
                if (player === p) {
                    p.release()
                    player = null
                }
                true
            }
            player = mp
            mp.prepareAsync()
            true
        } catch (e: Exception) {
            mp.release()
            false
        }
    }

    fun pause() {
        try {
            player?.takeIf { it.isPlaying }?.pause()
        } catch (_: IllegalStateException) {
        }
    }

    /** 대화·녹음 중에는 음악 소리가 마이크에 섞이지 않도록 볼륨을 낮춤 */
    fun setDucked(ducked: Boolean) {
        this.ducked = ducked
        player?.let { applyVolume(it) }
    }

    private fun applyVolume(mp: MediaPlayer) {
        val v = if (ducked) duckedVolume else normalVolume
        try {
            mp.setVolume(v, v)
        } catch (_: IllegalStateException) {
        }
    }

    fun release() {
        player?.release()
        player = null
    }
}
