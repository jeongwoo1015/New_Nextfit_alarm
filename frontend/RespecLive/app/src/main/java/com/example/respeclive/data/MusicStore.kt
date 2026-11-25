package com.example.respeclive.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 재기상 음악 보관. 서버 URL은 12시간 유효하므로 밤 대화 뒤 미리 받아 두면
 * 아침 네트워크 상태와 상관없이 울릴 수 있다 (GET /music/rewake).
 */
object MusicStore {
    private const val TAG = "RespecApi"
    private const val MAX_AGE_MS = 24 * 60 * 60_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun rewakePath(context: Context) = File(context.filesDir, "music/rewake.mp3")

    /** 하루 안에 받아 둔 재기상 음악 파일 */
    fun rewakeFile(context: Context): File? =
        rewakePath(context).takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < MAX_AGE_MS }

    /** 서버에서 재기상 음악을 받아 파일로 저장. 음악이 없으면(404) false */
    suspend fun prefetchRewake(context: Context): Boolean {
        val url = RespecApi.musicUrl("rewake") ?: return false
        val target = rewakePath(context.applicationContext)
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val body = resp.body
                    if (!resp.isSuccessful || body == null) return@withContext false
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, "rewake.tmp")
                    tmp.outputStream().use { body.byteStream().copyTo(it) }
                    tmp.renameTo(target) || run { tmp.copyTo(target, overwrite = true); tmp.delete() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "재기상 음악 받기 실패: ${e.message}")
                false
            }
        }
    }
}
