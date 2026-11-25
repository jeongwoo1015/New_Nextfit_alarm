package com.example.respeclive.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/** 테마 하나의 색. 넥스티의 몸·햇살·수면모자 색에서 뽑음 (화면 수정 명세 3-1. 색) */
data class RespecPalette(
    val background: Color,          // 기본 화면 배경 (홈, 리포트, 일기, 내 정보, 설정, 온보딩)
    val morningBackground: Color,   // 아침 화면 배경 (모닝콜 수신·통화·콘텐츠)
    val card: Color,
    val cardBorder: Color,
    val text: Color,
    val textSub: Color,
    val accent: Color,        // 포인트 색: 선택, 진행, 토글 켜짐
    val onAccent: Color,      // accent 위의 글자·아이콘
    val accentText: Color,    // 강조 글자: 라벨, 숫자 강조 (배경 위에서 또렷함)
    val purple: Color,
    val purpleDark: Color,    // 보조 포인트: 요일 칩, 알약 배경, 동그란 버튼 배경
    val banner: Color,
    val onBanner: Color,
    val button: Color,        // 주요 버튼 (다음으로, 저장하기, 다음 문제 등)
    val onButton: Color,
    val mint: Color,
    val mintDark: Color,
    val disabled: Color,
    val danger: Color         // 통화 거절·종료, 오류
)

/** 아침의 넥스티: 밝은 크림 배경, 오렌지 포인트, 진한 갈색 글자·버튼 */
val LightPalette = RespecPalette(
    background = Color(0xFFFFFBF5),
    morningBackground = Color(0xFFFFF4E4),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFF0E2CF),
    text = Color(0xFF2B2522),
    textSub = Color(0xFF6B5E55),
    accent = Color(0xFFF2A65A),
    onAccent = Color(0xFF2B2522),
    accentText = Color(0xFF9A4F12),
    purple = Color(0xFF4A5AA8),
    purpleDark = Color(0xFFFFE3C2),
    banner = Color(0xFF1F2547),
    onBanner = Color(0xFFF6EFE0),
    button = Color(0xFF2B2522),
    onButton = Color(0xFFFFFFFF),
    mint = Color(0xFF2E8F6C),
    mintDark = Color(0xFFDDF3E8),
    disabled = Color(0xFFF3E7D6),
    danger = Color(0xFFE46B5C)
)

/** 밤의 넥스티: 남색 배경·카드, 노랑 포인트, 흰 글자 */
val DarkPalette = RespecPalette(
    background = Color(0xFF1F2547),
    morningBackground = Color(0xFF1F2547),
    card = Color(0xFF2A3160),
    cardBorder = Color(0xFF3A4378),
    text = Color(0xFFFFFFFF),
    textSub = Color(0xFFC9D0FF),
    accent = Color(0xFFFFE08A),
    onAccent = Color(0xFF1F2547),
    accentText = Color(0xFFFFE08A),
    purple = Color(0xFFB9C2F2),
    purpleDark = Color(0xFF3A4378),
    banner = Color(0xFF3A4378),
    onBanner = Color(0xFFF6EFE0),
    button = Color(0xFFFFE08A),
    onButton = Color(0xFF1F2547),
    mint = Color(0xFF7FE0B8),
    mintDark = Color(0xFF1E4A45),
    disabled = Color(0xFF2A3160),
    danger = Color(0xFFE46B5C)
)

/** 화면이 쓰는 색. 값은 지금 테마(AppTheme)를 따르고, 테마가 바뀌면 화면이 바로 다시 그려짐 */
object RespecColors {
    var palette by mutableStateOf(DarkPalette)

    /** 테마와 상관없는 햇살 노랑 (굿나잇콜 손잡이 등 남색 위) */
    val Sun = Color(0xFFFFD45C)

    val Background get() = palette.background
    val MorningBackground get() = palette.morningBackground
    val Button get() = palette.button
    val OnButton get() = palette.onButton
    val Card get() = palette.card
    val CardBorder get() = palette.cardBorder
    val Text get() = palette.text
    val TextSub get() = palette.textSub
    val Lime get() = palette.accent
    val OnAccent get() = palette.onAccent
    val AccentText get() = palette.accentText
    val Purple get() = palette.purple
    val PurpleDark get() = palette.purpleDark
    val Banner get() = palette.banner
    val OnBanner get() = palette.onBanner
    val Mint get() = palette.mint
    val MintDark get() = palette.mintDark
    val Disabled get() = palette.disabled
    val Danger get() = palette.danger
}
