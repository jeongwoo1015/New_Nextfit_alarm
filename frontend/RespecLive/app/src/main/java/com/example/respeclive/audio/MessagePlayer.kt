package com.example.respeclive.audio

import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 음성 메시지 재생용. 서버(GET /night/records)가 내려준 서명 URL 또는 기기 파일을 재생.
 * @param baseUrl 서버가 "/static/..." 같은 상대 경로를 내려줄 때 앞에 붙일 서버 주소
 *                (예: "http://10.0.2.2:8000" - 에뮬레이터, 실기기는 PC의 LAN IP)
 */
class MessagePlayer(private val baseUrl: String = "") {
    private var player: MediaPlayer? = null

    /** 로컬 파일 재생(녹음 직후 미리듣기 등). 준비에 성공하면 true */
    fun load(file: File, onComplete: () -> Unit): Boolean =
        prepare(onComplete) { it.setDataSource(file.absolutePath) }

    /** 서버 URL 재생용. 네트워크 접속이 있으므로 IO 스레드에서 호출 */
    fun loadUrl(url: String, onComplete: () -> Unit): Boolean =
        prepare(onComplete) { it.setDataSource(resolve(url)) }

    /** 코루틴에서 바로 쓰는 버전 (내부에서 IO 스레드로 전환) */
    suspend fun loadUrlAsync(url: String, onComplete: () -> Unit): Boolean =
        withContext(Dispatchers.IO) { loadUrl(url, onComplete) }

    private fun resolve(url: String): String {
        val u = url.trim().trim('"') // 응답이 JSON 문자열 그대로 넘어온 경우 대비
        return if (u.startsWith("http://", true) || u.startsWith("https://", true) || baseUrl.isBlank()) {
            u
        } else {
            baseUrl.trimEnd('/') + "/" + u.trimStart('/')
        }
    }

    private fun prepare(onComplete: () -> Unit, setSource: (MediaPlayer) -> Unit): Boolean {
        release()
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(CallAudio.VOICE_ATTRIBUTES)     // 통화 화면에서 재생하므로 통화 볼륨
            setSource(mp)
            mp.setOnCompletionListener { onComplete() }
            mp.prepare()
            mp.setVolume(CallAudio.VOICE_GAIN, CallAudio.VOICE_GAIN)
            player = mp
            true
        } catch (e: Exception) {
            mp.release()
            false
        }
    }

    fun play() {
        player?.start()
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
    }

    fun restart() {
        player?.seekTo(0)
        player?.start()
    }

    val durationMs: Int
        get() = try { player?.duration ?: 0 } catch (e: IllegalStateException) { 0 }

    val positionMs: Int
        get() = try { player?.currentPosition ?: 0 } catch (e: IllegalStateException) { 0 }

    fun release() {
        player?.release()
        player = null
    }
}