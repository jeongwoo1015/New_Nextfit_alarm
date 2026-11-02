package com.example.respeclive.data

import com.example.respeclive.BuildConfig

object ApiConfig {
    /**
     * 백엔드(FastAPI) 주소. local.properties 의 API_BASE_URL, 없으면 기본값.
     * 에뮬레이터: http://10.0.2.2:8000 / 실기기(USB): adb reverse tcp:8000 tcp:8000 후 http://127.0.0.1:8000
     */
    val BASE_URL: String = BuildConfig.API_BASE_URL.ifBlank { "http://127.0.0.1:8000" }

    /** 로그인(Supabase Auth). 백엔드가 Authorization: Bearer <access_token> 을 요구함 */
    val SUPABASE_URL: String = BuildConfig.SUPABASE_URL
    val SUPABASE_ANON_KEY: String = BuildConfig.SUPABASE_ANON_KEY

    /** 프로토타입용 테스트 계정 (로그인 화면이 생기기 전까지 사용). 온보딩을 마친 계정이어야 함 */
    val DEV_EMAIL: String = BuildConfig.SUPABASE_DEV_EMAIL
    val DEV_PASSWORD: String = BuildConfig.SUPABASE_DEV_PASSWORD

    /** 일반(DB) 요청 */
    const val TIMEOUT_MS = 10_000L

    /** Live 토큰 발급: 서버가 지시문을 조립하고 Gemini에 토큰을 요청 (코칭은 콘텐츠 구성 AI 호출이 더해질 수 있음) */
    const val TOKEN_TIMEOUT_MS = 40_000L
}
