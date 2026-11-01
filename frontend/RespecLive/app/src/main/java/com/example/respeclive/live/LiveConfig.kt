package com.example.respeclive.live

object LiveConfig {
    const val MODEL = "gemini-3.8-live"
    const val VOICE = "Kore"

    /** 입력: 16-bit PCM, 16kHz, 모노 / 출력: 24kHz */
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000

    /** 마이크 전송 단위 (권장 20~40ms) */
    const val CHUNK_MS = 40

    private const val BASE =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService"

    /** 개발용: API 키 직접 사용 */
    const val URL_API_KEY = "${BASE}.BidiGenerateContent?key="

    /** 배포용: 서버가 발급한 임시 토큰 사용 */
    const val URL_EPHEMERAL = "${BASE}.BidiGenerateContentConstrained?access_token="

    /** setupComplete를 기다리는 최대 시간 */
    const val SETUP_TIMEOUT_MS = 10_000L

    /** 연결이 끊겼을 때 재연결 시도 횟수 */
    const val RECONNECT_MAX = 3

    /**
     * 임시 토큰 발급 서버 (개발용 API 키를 쓰면 사용하지 않음). 경로와 응답 형식은 서버 팀과 확정 필요 - 현재는 가정값.
     * USB 실기기: adb reverse tcp:8000 tcp:8000 후 127.0.0.1 / 에뮬레이터: http://10.0.2.2:8000
     */
    const val TOKEN_BASE_URL = "http://127.0.0.1:8000"
    const val TOKEN_PATH = "/live/token"
    const val USER_ID = "00000000-0000-0000-0000-000000000001"
}
