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

/** 테마 하나의 색. 넥스티의 몸·햇살·수면모자 색에서 뽑음 */
data class RespecPalette(
    val background: Color,
    val card: Color,
    val cardBorder: Color,
    val text: Color,
    val textSub: Color,
    val accent: Color,        // 주요 강조의 채움색 (버튼, 스위치, 게이지)
    val onAccent: Color,      // accent 위의 글자·아이콘
    val accentText: Color,    // 배경 위에 쓰는 강조 글자·아이콘 (accent보다 또렷함)
    val purple: Color,
    val purpleDark: Color,
    val banner: Color,
    val onBanner: Color,
    val mint: Color,
    val mintDark: Color,
    val disabled: Color,
    val danger: Color
)

/** 아침의 넥스티: 크림 배경, 몸 색 오렌지, 달 모자 남색 */
val LightPalette = RespecPalette(
    background = Color(0xFFFFF9EF),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFF1E3CF),
    text = Color(0xFF3A2E2A),
    textSub = Color(0xFF8A7464),
    accent = Color(0xFFF5A95B),
    onAccent = Color(0xFF3A2E2A),
    accentText = Color(0xFFA9591A),
    purple = Color(0xFF4A5AA8),
    purpleDark = Color(0xFFE4E7F7),
    banner = Color(0xFF1F2547),
    onBanner = Color(0xFFF6EFE0),
    mint = Color(0xFF2E8F6C),
    mintDark = Color(0xFFDDF3E8),
    disabled = Color(0xFFF3E7D6),
    danger = Color(0xFFD9483B)
)

/** 밤의 넥스티: 남색 배경, 햇살 노랑 강조, 달빛 라벤더 */
val DarkPalette = RespecPalette(
    background = Color(0xFF141A33),
    card = Color(0xFF1F2547),
    cardBorder = Color(0xFF323A68),
    text = Color(0xFFF6F3EA),
    textSub = Color(0xFFA3A9C9),
    accent = Color(0xFFFFD45C),
    onAccent = Color(0xFF1F2547),
    accentText = Color(0xFFFFD45C),
    purple = Color(0xFFB9C2F2),
    purpleDark = Color(0xFF333C73),
    banner = Color(0xFF2A3160),
    onBanner = Color(0xFFF6EFE0),
    mint = Color(0xFF7FE0B8),
    mintDark = Color(0xFF1E4A45),
    disabled = Color(0xFF2A3160),
    danger = Color(0xFFFF7B6B)
)

/** 화면이 쓰는 색. 값은 지금 테마(AppTheme)를 따르고, 테마가 바뀌면 화면이 바로 다시 그려짐 */
object RespecColors {
    var palette by mutableStateOf(DarkPalette)

    /** 테마와 상관없는 햇살 노랑 (굿나잇콜 손잡이 등 남색 위) */
    val Sun = Color(0xFFFFD45C)

    val Background get() = palette.background
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
