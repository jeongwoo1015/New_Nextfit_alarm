package com.example.respeclive.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Supabase Auth 이메일 로그인. 로그인 화면에서 받은 access token·refresh token을 기기에 저장하고,
 * access token이 만료 1분 전이면 refresh token으로 새로 받음. refresh token도 거절되면 로그아웃 상태가 되어 로그인 화면으로 돌아감.
 */
object ServerAuth {
    private const val TAG = "RespecApi"
    private const val PREFS = "auth_session"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES = "expires_at_ms"
    private const val KEY_EMAIL = "email"

    private val JSON = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(ApiConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ApiConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val mutex = Mutex()

    private var prefs: SharedPreferences? = null
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var expiresAtMs = 0L

    /** 로그인돼 있는지 (Compose 상태: 로그아웃·세션 만료되면 화면이 로그인으로 바뀜) */
    var loggedIn by mutableStateOf(false)
        private set

    /** 로그인한 이메일 (설정 화면 표시용) */
    var email by mutableStateOf<String?>(null)
        private set

    /** 마지막 로그인 실패 이유 (화면 표시용) */
    @Volatile
    var lastError: String? = null
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        accessToken = p.getString(KEY_ACCESS, null)
        refreshToken = p.getString(KEY_REFRESH, null)
        expiresAtMs = p.getLong(KEY_EXPIRES, 0L)
        email = p.getString(KEY_EMAIL, null)
        loggedIn = refreshToken != null
    }

    val configured: Boolean
        get() = ApiConfig.SUPABASE_URL.isNotBlank() && ApiConfig.SUPABASE_ANON_KEY.isNotBlank()

    /** 서버 요청에 붙일 access token. 만료됐으면 refresh token으로 새로 받음. 로그인돼 있지 않거나 갱신에 실패하면 null */
    suspend fun bearer(): String? = mutex.withLock {
        val cached = accessToken
        if (cached != null && System.currentTimeMillis() < expiresAtMs - 60_000L) return cached
        refresh()
    }

    /** 서버가 401을 주면 다음 요청에서 refresh token으로 다시 받음 */
    fun invalidate() {
        accessToken = null
        expiresAtMs = 0L
    }

    /** 이메일 로그인. 성공하면 null, 실패하면 화면에 보일 문구 */
    suspend fun signIn(email: String, password: String): String? = mutex.withLock {
        authRequest(
            "/auth/v1/token?grant_type=password",
            JSONObject().put("email", email).put("password", password)
        ) { code, json ->
            when {
                code == 400 && json.errorCode() == "email_not_confirmed" -> "이메일 인증을 아직 마치지 않았어요. 받은 메일의 링크를 눌러 주세요"
                code == 400 || code == 401 -> "이메일 또는 비밀번호가 맞지 않아요"
                code == 429 -> "시도가 너무 많아요. 잠시 뒤 다시 해 주세요"
                else -> "로그인하지 못했어요 (HTTP $code)"
            }
        }
    }

    /**
     * 회원가입. 성공해서 바로 로그인되면 null.
     * Supabase에서 이메일 인증을 켜 두었으면 세션 없이 끝나므로 [SignUpResult.NeedsConfirm]
     */
    suspend fun signUp(email: String, password: String): SignUpResult = mutex.withLock {
        var needsConfirm = false
        val error = authRequest(
            "/auth/v1/signup",
            JSONObject().put("email", email).put("password", password),
            onNoSession = { needsConfirm = true }
        ) { code, json ->
            val err = json.errorCode()
            when {
                err == "user_already_exists" || err == "email_exists" -> "이미 가입한 이메일이에요. 로그인해 주세요"
                err == "weak_password" -> "비밀번호가 너무 쉬워요. 더 길고 복잡하게 만들어 주세요"
                err == "email_address_invalid" || err == "validation_failed" -> "이메일 주소를 확인해 주세요"
                err == "signup_disabled" -> "지금은 회원가입을 받지 않아요"
                code == 429 -> "시도가 너무 많아요. 잠시 뒤 다시 해 주세요"
                else -> "가입하지 못했어요 (HTTP $code)"
            }
        }
        when {
            error != null -> SignUpResult.Failed(error)
            needsConfirm -> SignUpResult.NeedsConfirm
            else -> SignUpResult.Done
        }
    }

    /**
     * 회원 탈퇴 전 본인 확인: 로그인한 이메일과 입력한 비밀번호로 로그인해 봄 (받은 세션은 저장하지 않음).
     * 맞으면 null, 틀리거나 확인하지 못하면 화면에 보일 문구
     */
    suspend fun verifyPassword(password: String): String? = withContext(Dispatchers.IO) {
        val mail = email ?: return@withContext "로그인한 이메일을 알 수 없어요. 다시 로그인한 뒤 해 주세요"
        if (!configured) return@withContext "로그인 서버 설정이 없어요"
        try {
            val request = Request.Builder()
                .url(ApiConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/token?grant_type=password")
                .header("apikey", ApiConfig.SUPABASE_ANON_KEY)
                .post(JSONObject().put("email", mail).put("password", password).toString().toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> null
                    resp.code == 400 || resp.code == 401 -> "비밀번호가 맞지 않아요"
                    resp.code == 429 -> "시도가 너무 많아요. 잠시 뒤 다시 해 주세요"
                    else -> "비밀번호를 확인하지 못했어요 (HTTP ${resp.code})"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "비밀번호 확인 실패: ${e.message}")
            "로그인 서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요"
        }
    }

    /** 로그아웃: 서버 세션을 끊고(실패해도 무시) 기기에 저장한 토큰을 지움 */
    suspend fun signOut() {
        val token = mutex.withLock { accessToken }
        if (token != null && configured) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(ApiConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/logout")
                        .header("apikey", ApiConfig.SUPABASE_ANON_KEY)
                        .header("Authorization", "Bearer $token")
                        .post("{}".toRequestBody(JSON))
                        .build()
                    http.newCall(request).execute().close()
                }
            }
        }
        mutex.withLock { clear() }
    }

    private suspend fun refresh(): String? {
        val rt = refreshToken ?: run {
            lastError = "로그인이 필요해요"
            return null
        }
        var expired = false
        val error = authRequest(
            "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", rt)
        ) { code, _ ->
            // 400/401: refresh token이 만료·취소됨 → 다시 로그인해야 함. 그 밖(서버 오류)은 다음에 다시 시도
            if (code == 400 || code == 401) expired = true
            if (expired) "로그인이 만료됐어요. 다시 로그인해 주세요" else "로그인을 이어 가지 못했어요 (HTTP $code)"
        }
        if (expired) clear()
        return if (error == null) accessToken else null
    }

    /**
     * Supabase Auth 요청 하나. 응답에 세션이 있으면 저장하고 null, 실패하면 문구(lastError에도 저장).
     * 성공했지만 세션이 없으면(이메일 인증 대기) onNoSession을 부르고 null
     */
    private suspend fun authRequest(
        path: String,
        body: JSONObject,
        onNoSession: () -> Unit = {},
        failMessage: (code: Int, json: JSONObject) -> String
    ): String? = withContext(Dispatchers.IO) {
        if (!configured) {
            Log.w(TAG, "local.properties 에 SUPABASE_URL / SUPABASE_ANON_KEY 를 넣어 주세요")
            return@withContext "로그인 서버 설정이 없어요 (local.properties의 SUPABASE_URL / SUPABASE_ANON_KEY)".also { lastError = it }
        }
        try {
            val request = Request.Builder()
                .url(ApiConfig.SUPABASE_URL.trimEnd('/') + path)
                .header("apikey", ApiConfig.SUPABASE_ANON_KEY)
                .post(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Auth $path 실패 HTTP ${resp.code} $text")
                    return@withContext failMessage(resp.code, json).also { lastError = it }
                }
                val token = json.optString("access_token").takeIf { it.isNotBlank() }
                if (token == null) {
                    onNoSession()
                    return@withContext null
                }
                save(json)
                lastError = null
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auth $path 실패: ${e.message}")
            "로그인 서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요".also { lastError = it }
        }
    }

    private fun save(session: JSONObject) {
        accessToken = session.optString("access_token")
        refreshToken = session.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken
        expiresAtMs = System.currentTimeMillis() + session.optLong("expires_in", 3600L) * 1000L
        val mail = session.optJSONObject("user")?.optString("email")?.takeIf { it.isNotBlank() } ?: email
        prefs?.edit()
            ?.putString(KEY_ACCESS, accessToken)
            ?.putString(KEY_REFRESH, refreshToken)
            ?.putLong(KEY_EXPIRES, expiresAtMs)
            ?.putString(KEY_EMAIL, mail)
            ?.apply()
        postState(loggedIn = true, email = mail)
    }

    private fun clear() {
        accessToken = null
        refreshToken = null
        expiresAtMs = 0L
        prefs?.edit()?.clear()?.apply()
        postState(loggedIn = false, email = null)
    }

    /** Compose 상태는 메인 스레드에서 바꿈 */
    private fun postState(loggedIn: Boolean, email: String?) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            this.loggedIn = loggedIn
            this.email = email
        }
    }

    private fun JSONObject.errorCode(): String? =
        (optString("error_code").takeIf { it.isNotBlank() } ?: optString("error").takeIf { it.isNotBlank() })

    sealed interface SignUpResult {
        data object Done : SignUpResult
        data object NeedsConfirm : SignUpResult
        data class Failed(val message: String) : SignUpResult
    }
}
