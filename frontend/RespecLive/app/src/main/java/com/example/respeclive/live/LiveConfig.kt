package com.example.respeclive.live

object LiveConfig {
    /** 입력: 16-bit PCM, 16kHz, 모노 / 출력: 24kHz */
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000

    /** 마이크 전송 단위 (권장 20~40ms) */
    const val CHUNK_MS = 40

    /** setupComplete를 기다리는 최대 시간 */
    const val SETUP_TIMEOUT_MS = 10_000L

    /** 사용자가 말을 마친 뒤 AI 음성을 재생하지 않는 최소 시간 (화면에 사용자 말을 보여 주는 시간과 같음) */
    const val USER_GAP_MS = 1_500L
    /** 길게 말해도 이 시간 넘게 AI 대답을 미루지 않음 */
    const val USER_GAP_MAX_MS = 3_000L
    /** 사용자 자막을 치는 속도 (이미 한 말이라 AI 자막보다 빠르게) */
    const val USER_CHAR_TYPE_MS = 25L
    /** 사용자 말 박스 한 페이지 최대 글자 수 (PagedSubtitle PAGE_MAX_CHARS와 같음) */
    private const val USER_PAGE_CHARS = 64
    /** 다 쳐진 뒤 읽을 시간 */
    private const val USER_READ_MS = 1_000L

    /**
     * 사용자 말을 보여 주고 AI 음성을 미루는 시간: 마지막 박스가 다 쳐질 때까지 + 읽을 시간 (1.5~3초).
     * 예전에는 길이와 상관없이 1.5초라 길게 말하면 자막이 다 뜨기 전에 AI 말로 넘어갔음 (11/26 실사용 피드백)
     */
    fun userHoldMs(text: String): Long {
        val lastPage = minOf(text.trim().length, USER_PAGE_CHARS)    // 박스는 문장 단위로 나뉘어 넉넉하게 한 박스 길이까지
        return (lastPage * USER_CHAR_TYPE_MS + USER_READ_MS).coerceIn(USER_GAP_MS, USER_GAP_MAX_MS)
    }

    /** 연결이 끊겼을 때 재연결 시도 횟수 (매번 서버에서 새 토큰을 받음) */
    const val RECONNECT_MAX = 3
}
