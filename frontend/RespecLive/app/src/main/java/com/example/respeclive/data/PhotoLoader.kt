package com.example.respeclive.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 일기 사진(서버가 준 서명된 임시 URL)을 내려받아 작게 줄여 보여 줌. 메모리에만 잠깐 보관.
 * 서명 URL은 인증 없이 받을 수 있어 앱 로그인 토큰을 붙이지 않음.
 */
object PhotoLoader {
    private const val TAG = "PhotoLoader"
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // 키: URL + 크기. 약 24MB까지 보관
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** maxPx: 긴 변을 이 정도로 줄여서 디코딩 (달력 칸은 작게, 상세는 크게). 실패하면 null */
    suspend fun load(url: String, maxPx: Int): Bitmap? {
        val key = "$maxPx|$url"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    r.body?.bytes()
                } ?: return@withContext null
                decode(bytes, maxPx)?.also { cache.put(key, it) }
            } catch (e: Exception) {
                Log.w(TAG, "사진을 받지 못했어요: ${e.message}")
                null
            }
        }
    }

    private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
