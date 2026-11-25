package com.example.respeclive.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** 디자인 기준 화면 세로 길이 (dp) */
private const val BASE_HEIGHT_DP = 800f

/**
 * 세로가 기준(800dp)보다 짧은 폰에서 고정 크기를 줄이는 배율 (0.8~1). 기준 이상인 폰에서는 1(원래 크기).
 * 스크롤이 없는 통화 화면처럼 한 화면에 다 들어가야 하는 곳의 높이·버튼 크기에 곱해서 씀
 */
@Composable
fun rememberScreenScale(): Float =
    (LocalConfiguration.current.screenHeightDp / BASE_HEIGHT_DP).coerceIn(0.8f, 1f)
