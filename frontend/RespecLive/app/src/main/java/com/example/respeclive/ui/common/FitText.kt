package com.example.respeclive.ui.common

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 항상 한 줄. 자리가 모자라면(좁은 화면, 글꼴을 키운 폰) 줄바꿈 대신 글자를 minSize까지 줄임.
 * 버튼 이름·상태 문구처럼 두 줄이 되면 배치가 깨지는 곳에 씀
 */
@Composable
fun FitText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.Unspecified,
    minSize: TextUnit = 10.sp
) {
    BasicText(
        text,
        modifier,
        style = LocalTextStyle.current.merge(color = color, fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = fontSize, stepSize = 0.5.sp)
    )
}
