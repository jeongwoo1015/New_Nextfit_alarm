package com.example.respeclive.ui.nexty

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

// 넥스티 둘레의 장식. 위치·크기는 그리는 영역(정사각형)에 대한 비율이라 화면 크기와 상관없이 같은 자리에 놓임.
// 넥스티를 영역의 180/210 크기로 가운데에 그렸을 때(수신 화면의 원) 넥스티와 겹치지 않게 맞춤

/** 장식이 천천히 반짝이도록 0~1을 오가는 값 */
@Composable
private fun rememberTwinkle(): Float {
    val wave by rememberInfiniteTransition(label = "decor").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "twinkle"
    )
    return wave
}

/**
 * 밤 장식: 별 4개와 Zz (별은 번갈아 반짝이고 Z는 살짝 떠오름).
 * inRing이면 가운데 원과 바깥 고리 사이(밤 대화 화면), 아니면 원 안 넥스티 둘레(굿나잇콜 수신 화면)
 */
@Composable
fun NightDecor(color: Color, modifier: Modifier = Modifier, inRing: Boolean = false) {
    val wave = rememberTwinkle()
    Canvas(modifier) {
        if (inRing) {
            star(0.19f, 0.27f, 0.04f, wave, color)
            star(0.14f, 0.62f, 0.022f, 1f - wave, color)
            star(0.86f, 0.66f, 0.028f, wave, color)
            star(0.42f, 0.11f, 0.018f, 1f - wave, color)
            letterZ(0.745f, 0.235f, 0.016f, wave, color)
            letterZ(0.775f, 0.17f, 0.024f, wave, color)
        } else {
            star(0.22f, 0.30f, 0.035f, wave, color)
            star(0.17f, 0.58f, 0.02f, 1f - wave, color)
            star(0.83f, 0.60f, 0.025f, wave, color)
            star(0.38f, 0.13f, 0.016f, 1f - wave, color)
            letterZ(0.70f, 0.27f, 0.016f, wave, color)
            letterZ(0.765f, 0.19f, 0.022f, wave, color)
        }
    }
}

/** 아침 장식(모닝콜 수신 화면의 원 안): 반짝이 4개와, 손 흔드는 넥스티의 오른손 옆 움직임 선 2줄 */
@Composable
fun MorningDecor(color: Color, modifier: Modifier = Modifier) {
    val wave = rememberTwinkle()
    Canvas(modifier) {
        star(0.21f, 0.30f, 0.04f, wave, color)
        star(0.17f, 0.60f, 0.02f, 1f - wave, color)
        star(0.36f, 0.12f, 0.018f, 1f - wave, color)
        star(0.84f, 0.64f, 0.026f, wave, color)
        // 흔드는 손(영역의 약 x 0.76, y 0.35) 오른쪽의 짧은 호 두 개
        val hand = Offset(size.width * 0.76f, size.height * 0.35f)
        listOf(0.085f to 1f, 0.12f to 0.6f).forEach { (r, alpha) ->
            val radius = size.width * r
            drawArc(
                color = color.copy(alpha = alpha * (0.6f + 0.4f * wave)),
                startAngle = -40f,
                sweepAngle = 80f,
                useCenter = false,
                topLeft = Offset(hand.x - radius, hand.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(size.width * 0.012f, cap = StrokeCap.Round)
            )
        }
    }
}

/** 끝이 네 개인 별 (twinkle 0~1에 따라 크기·진하기가 조금 바뀜) */
private fun DrawScope.star(x: Float, y: Float, radius: Float, twinkle: Float, color: Color) {
    val c = Offset(size.width * x, size.height * y)
    val r = size.width * radius * (0.8f + 0.2f * twinkle)
    val k = r * 0.22f
    val path = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
        quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
        quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
        quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
        close()
    }
    drawPath(path, color.copy(alpha = 0.55f + 0.45f * twinkle))
}

/** 선으로 그린 Z. wave에 따라 살짝 떠오름 */
private fun DrawScope.letterZ(x: Float, y: Float, half: Float, wave: Float, color: Color) {
    val c = Offset(size.width * x, size.height * (y - 0.012f * wave))
    val h = size.width * half
    val path = Path().apply {
        moveTo(c.x - h, c.y - h)
        lineTo(c.x + h, c.y - h)
        lineTo(c.x - h, c.y + h)
        lineTo(c.x + h, c.y + h)
    }
    drawPath(path, color, style = Stroke(h * 0.42f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
