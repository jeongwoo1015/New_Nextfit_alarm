package com.example.respeclive.ui.start

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.ui.nexty.NextyCharacter
import com.example.respeclive.ui.nexty.NextyMotion

// 화면 수정 명세 01: 시작 화면은 앱 테마와 상관없이 아침 크림색
private val BACKGROUND = Color(0xFFFFF4E4)
private val TITLE = Color(0xFF2B2522)
private val SUBTITLE = Color(0xFF6B5E55)
private val SUN = Color(0xFFFFD79A)          // 넥스티 뒤 햇살
private val DOT = Color(0xFFF2A65A)          // 불러오는 중 점 (아침 테마 포인트 색)

// 넥스티 그림판은 260×240 비율이고 서 있는 발끝이 아래에서 약 30 위라, 그 빈자리만큼 글자를 끌어올림
private val NEXTY_WIDTH = 240.dp
private val NEXTY_HEIGHT = NEXTY_WIDTH * (240f / 260f)
private val NEXTY_FOOT_GAP = NEXTY_HEIGHT * (28f / 240f)

/** 앱을 켜면 서버 확인이 끝날 때까지 보이는 화면: 은은한 햇살 앞에서 반기는 넥스티, 앱 이름, 불러오는 중 표시 */
@Composable
fun StartScreen() {
    val infinite = rememberInfiniteTransition(label = "start")
    val glow by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(BACKGROUND)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                // 넥스티 뒤에서 천천히 숨 쉬듯 커졌다 작아지는 햇살
                // 넥스티 자리 크기에 맞추고(줄인 높이를 그대로 따름) 원은 자리 밖으로 넘쳐 그려도 됨
                Canvas(Modifier.matchParentSize()) {
                    val r = size.width / 2f * (0.85f + 0.05f * glow)
                    val c = Offset(size.width / 2f, size.width * (240f / 260f) * 0.5f)
                    drawCircle(
                        Brush.radialGradient(
                            0f to SUN.copy(alpha = 0.55f),
                            0.55f to SUN.copy(alpha = 0.25f),
                            1f to SUN.copy(alpha = 0f),
                            center = c,
                            radius = r
                        ),
                        radius = r,
                        center = c
                    )
                }
                NextyCharacter(
                    NextyMotion.WELCOME,
                    Modifier
                        .size(NEXTY_WIDTH, NEXTY_HEIGHT)
                        .trimBottom(NEXTY_FOOT_GAP)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("넥스티", color = TITLE, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text("더 나은 나를 위한 아침 습관", color = SUBTITLE, fontSize = 14.sp)
            Spacer(Modifier.height(28.dp))
            LoadingDots()
        }
    }
}

/** 차례로 떠오르는 점 세 개 (서버 확인 중) */
@Composable
private fun LoadingDots() {
    val infinite = rememberInfiniteTransition(label = "dots")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200)),
        label = "phase"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            // 지금 차례인 점이 가장 진하고 살짝 위로
            val d = ((phase - i + 3f) % 3f)
            val t = if (d < 1f) 1f - kotlin.math.abs(d - 0.5f) * 2f else 0f
            Box(
                Modifier
                    .offset(y = (-4).dp * t)
                    .size(8.dp)
                    .alpha(0.35f + 0.65f * t)
                    .clip(CircleShape)
                    .background(DOT)
            )
        }
    }
}

/** 그림은 그대로 그리고, 아래쪽 빈자리만큼 차지하는 높이를 줄여 다음 요소를 끌어올림 */
private fun Modifier.trimBottom(gap: Dp) = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val cut = gap.roundToPx().coerceIn(0, p.height)
    layout(p.width, p.height - cut) { p.place(0, 0) }
}
