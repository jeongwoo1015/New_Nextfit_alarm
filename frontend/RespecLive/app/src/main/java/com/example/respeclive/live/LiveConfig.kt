package com.example.respeclive.live

object LiveConfig {
    /** 입력: 16-bit PCM, 16kHz, 모노 / 출력: 24kHz */
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000

    /** 마이크 전송 단위 (권장 20~40ms) */
    const val CHUNK_MS = 40

    /** setupComplete를 기다리는 최대 시간 */
    const val SETUP_TIMEOUT_MS = 10_000L

    /** 사용자가 말을 마친 뒤 이 시간 동안은 AI 음성을 재생하지 않음 (화면에 사용자 말을 보여 주는 시간과 같음) */
    const val USER_GAP_MS = 2_000L

    /** 연결이 끊겼을 때 재연결 시도 횟수 (매번 서버에서 새 토큰을 받음) */
    const val RECONNECT_MAX = 3
}
