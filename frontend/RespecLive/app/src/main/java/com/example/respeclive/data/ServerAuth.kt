package com.example.respeclive.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Supabase Auth 이메일 로그인으로 access token을 받아 보관. 만료 1분 전부터는 다시 로그인.
 * 로그인 화면이 생기면 이 객체만 바꾸면 됨.
 */
object ServerAuth {
    private const val TAG = "RespecApi"

    private val http = OkHttpClient.Builder()
        .connectTimeout(ApiConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ApiConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val mutex = Mutex()

    private var accessToken: String? = null

    /** 마지막 로그인 실패 이유 (화면 표시용) */
    @Volatile
    var lastError: String? = null
        private set
    private var expiresAtMs = 0L

    suspend fun bearer(): String? = mutex.withLock {
        val cached = accessToken
        if (cached != null && System.currentTimeMillis() < expiresAtMs - 60_000L) return cached
        login()
    }

    /** 서버가 401을 주면 다음 요청에서 다시 로그인 */
    fun invalidate() {
        accessToken = null
        expiresAtMs = 0L
    }

    private suspend fun login(): String? = withContext(Dispatchers.IO) {
        if (ApiConfig.SUPABASE_URL.isBlank() || ApiConfig.SUPABASE_ANON_KEY.isBlank() ||
            ApiConfig.DEV_EMAIL.isBlank() || ApiConfig.DEV_PASSWORD.isBlank()
        ) {
            Log.w(TAG, "local.properties 에 SUPABASE_URL / SUPABASE_ANON_KEY / SUPABASE_DEV_EMAIL / SUPABASE_DEV_PASSWORD 를 넣어 주세요")
            lastError = "로그인 정보가 없어요 (local.properties의 SUPABASE_DEV_EMAIL / SUPABASE_DEV_PASSWORD)"
            return@withContext null
        }
        try {
            val body = JSONObject()
                .put("email", ApiConfig.DEV_EMAIL)
                .put("password", ApiConfig.DEV_PASSWORD)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(ApiConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/token?grant_type=password")
                .header("apikey", ApiConfig.SUPABASE_ANON_KEY)
                .post(body)
                .build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "로그인 실패 HTTP ${resp.code} $text")
                    lastError = "로그인하지 못했어요 (HTTP ${resp.code}). 테스트 계정의 이메일·비밀번호를 확인해 주세요"
                    return@withContext null
                }
                val json = JSONObject(text)
                val token = json.optString("access_token").takeIf { it.isNotBlank() } ?: return@withContext null
                accessToken = token
                lastError = null
                expiresAtMs = System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L
                token
            }
        } catch (e: Exception) {
            Log.w(TAG, "로그인 실패: ${e.message}")
            lastError = "로그인 서버(Supabase)에 연결하지 못했어요"
            null
        }
    }
}
