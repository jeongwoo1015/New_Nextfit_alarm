package com.example.respeclive.ui.common

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 하단 탭이 없는 화면(알람 설정, 설정, 일기 날짜 상세)의 뒤로 가기 헤더 위 여백.
 * 상태 표시줄 여백은 Scaffold가 따로 주고, 그 아래에 모든 화면이 같은 간격을 둠
 */
val SubScreenHeaderTop = 36.dp

/**
 * 아침·밤 통화 화면의 아래쪽 배치 (두 화면의 자막 박스 위치를 같게 맞춤).
 * 자막 박스 → GAP → 대답 버튼 한 줄(ANSWER_HEIGHT × 화면 비율) → GAP → 음소거·통화 종료 버튼.
 * 자리가 모자라면 원을 먼저 ORB_MIN까지 줄이고, 그래도 모자랄 때만 자막 박스를 SUBTITLE_MIN까지 줄임
 */
object CallLayout {
    val ANSWER_HEIGHT = 56.dp
    val GAP = 12.dp
    val ORB_MIN = 150.dp
    val SUBTITLE_MIN = 96.dp

    /** 가운데 원 영역의 기본 크기 (아침 코칭의 스트레칭 시범이 잘 보이는 크기. 자리가 모자라면 ORB_MIN까지 줄어듦) */
    val ORB_SIZE = 240.dp
    /** 넥스티가 든 안쪽 원 / 바깥 테두리 / 옅은 원의 비율 (원 영역 크기 기준) */
    const val ORB_INNER = 0.65f
    const val ORB_RING = 216f / 230f
    const val ORB_GLOW = 184f / 230f
    /** 원과 아래 상태 문구('듣고 있어요' 등) 사이 간격, 상태 문구 글자 크기와 자리 높이 */
    val ORB_STATUS_GAP = 12.dp
    val ORB_STATUS_TEXT = 14.sp
    val ORB_STATUS_HEIGHT = ORB_STATUS_GAP + 22.dp
    /** 통화 화면 헤더(이름·통화 시간, 밤은 수면 음악 스위치) 높이: 원의 위치가 두 화면에서 같도록 고정 */
    val HEADER_HEIGHT = 56.dp
    /** 밤 대화 녹음 확인 단계의 버튼 줄 높이 (버튼 52dp가 화면 비율로 줄어든 자리보다 크면 이 단계에서만 자막 박스를 그만큼 줄임) */
    val REVIEW_ANSWER_HEIGHT = 56.dp
    /** 녹음 확인 단계에서 줄인 자막 박스의 최소 높이 */
    val REVIEW_SUBTITLE_MIN = 64.dp
}
