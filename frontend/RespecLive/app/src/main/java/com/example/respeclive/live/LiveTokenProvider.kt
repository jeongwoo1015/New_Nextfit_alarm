package com.example.respeclive.live

import android.util.Log
import com.example.respeclive.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface LiveAuth {
    /** 개발용. 키가 앱에 들어가므로 배포 금지 */
    class ApiKey(val key: String) : LiveAuth

    /** 서버가 발급한 임시 토큰 */
    class Ephemeral(val token: String) : LiveAuth
}

object LiveTokenProvider {
    private const val TAG = "Live"

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * 1) local.properties 의 GEMINI_DEV_KEY 가 있으면 개발용으로 그 키를 사용.
     * 2) 없으면 서버(TOKEN_BASE_URL + TOKEN_PATH)에 POST 해서 임시 토큰을 받음.
     *    응답 JSON의 token / access_token / name 중 하나를 사용
     *
     * 재연결할 때마다 새로 호출함. (임시 토큰의 신규 세션 허용 시간이 기본 60초로 짧기 때문)
     */
    suspend fun fetch(): LiveAuth? {
        val devKey = BuildConfig.GEMINI_DEV_KEY
        if (devKey.isNotBlank()) {
            Log.w(TAG, "개발용 API 키로 연결합니다 (배포 금지)")
            return LiveAuth.ApiKey(devKey)
        }
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(LiveConfig.TOKEN_BASE_URL.trimEnd('/') + LiveConfig.TOKEN_PATH)
                    .post(FormBody.Builder().add("user_id", LiveConfig.USER_ID).build())
                    .header("Accept", "application/json")
                    .build()
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "임시 토큰 요청 실패 HTTP ${resp.code}")
                        return@withContext null
                    }
                    val json = JSONObject(body)
                    val token = listOf("token", "access_token", "name")
                        .firstNotNullOfOrNull { k -> json.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                    if (token == null) Log.w(TAG, "토큰 응답에서 token/access_token/name 필드를 찾지 못했어요")
                    token?.let { LiveAuth.Ephemeral(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "임시 토큰 요청 실패: ${e.message}")
                null
            }
        }
    }
}
