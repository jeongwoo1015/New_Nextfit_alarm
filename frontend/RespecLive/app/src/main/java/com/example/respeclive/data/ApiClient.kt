package com.example.respeclive.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 서버 응답. 실패 응답 형식은 {"error": {"code", "message"}} */
class ApiResponse(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299

    fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()

    fun array(): JSONArray? = runCatching { JSONArray(body) }.getOrNull()

    val errorCode: String? get() = json()?.optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }

    val errorMessage: String? get() = json()?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
}

object ApiClient {
    private const val TAG = "RespecApi"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 마지막 실패 이유 (화면 표시용): 로그인 실패, 서버 연결 실패, 서버 오류 메시지 */
    @Volatile
    var lastError: String? = null
        private set

    /** 마지막 실패의 서버 오류 코드 (예: VALIDATION_FAILED). 네트워크·로그인 실패면 null */
    @Volatile
    var lastErrorCode: String? = null
        private set

    /** 화면이 닫혀도 취소되지 않는 전송용 스코프 (통화 종료 기록, 원문·목소리 업로드) */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        .connectTimeout(ApiConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ApiConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun get(path: String, timeoutMs: Long = ApiConfig.TIMEOUT_MS): ApiResponse? =
        send("GET", path, null, timeoutMs)

    suspend fun post(path: String, body: JSONObject = JSONObject(), timeoutMs: Long = ApiConfig.TIMEOUT_MS): ApiResponse? =
        send("POST", path, body.toString().toRequestBody(JSON), timeoutMs)

    suspend fun patch(path: String, body: JSONObject, timeoutMs: Long = ApiConfig.TIMEOUT_MS): ApiResponse? =
        send("PATCH", path, body.toString().toRequestBody(JSON), timeoutMs)

    suspend fun put(path: String, body: JSONObject, timeoutMs: Long = ApiConfig.TIMEOUT_MS): ApiResponse? =
        send("PUT", path, body.toString().toRequestBody(JSON), timeoutMs)

    suspend fun delete(path: String, timeoutMs: Long = ApiConfig.TIMEOUT_MS): ApiResponse? =
        send("DELETE", path, null, timeoutMs)

    suspend fun putBytes(path: String, bytes: ByteArray, contentType: String, timeoutMs: Long = ApiConfig.TIMEOUT_MS): ApiResponse? =
        send("PUT", path, bytes.toRequestBody(contentType.toMediaType()), timeoutMs)

    /** 네트워크 오류나 로그인 실패면 null. 401이면 한 번 다시 로그인해서 재시도 */
    private suspend fun send(method: String, path: String, body: RequestBody?, timeoutMs: Long): ApiResponse? {
        val first = sendOnce(method, path, body, timeoutMs) ?: return null
        if (first.code != 401) return first
        ServerAuth.invalidate()
        return sendOnce(method, path, body, timeoutMs)
    }

    private suspend fun sendOnce(method: String, path: String, body: RequestBody?, timeoutMs: Long): ApiResponse? {
        val token = ServerAuth.bearer() ?: run {
            lastErrorCode = null
            lastError = ServerAuth.lastError ?: "로그인하지 못했어요"
            return null
        }
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(ApiConfig.BASE_URL.trimEnd('/') + path)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer $token")
                    .method(method, body)
                    .build()
                val client = http.newBuilder().readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) Log.d(TAG, "$method $path -> ${resp.code}")
                    else Log.w(TAG, "$method $path -> HTTP ${resp.code} $text")
                    ApiResponse(resp.code, text).also { r ->
                        lastErrorCode = if (r.ok) null else r.errorCode
                        lastError = if (r.ok) null else "서버 오류 (HTTP ${r.code}): ${r.errorMessage ?: r.errorCode ?: "알 수 없는 오류"}"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$method $path 실패: ${e.message}")
                lastErrorCode = null
                lastError = "서버(${ApiConfig.BASE_URL})에 연결하지 못했어요. 서버 실행과 adb reverse를 확인해 주세요"
                null
            }
        }
    }
}
