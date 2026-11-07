package com.example.respeclive.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.example.respeclive.ui.nexty.NextyCharacter
import com.example.respeclive.ui.nexty.NextyFace
import com.example.respeclive.ui.nexty.NextyMotion
import com.example.respeclive.ui.theme.RespecColors
import kotlin.math.cos
import kotlin.math.sin

// 낮: 크림빛 하늘, 해, 흰 구름, 따뜻한 언덕
private val DAY_SKY_TOP = Color(0xFFFFE9CC)
private val DAY_SKY_BOTTOM = Color(0xFFFFF1DE)
private val DAY_SUN = Color(0xFFFFD45C)
private val DAY_CLOUD = Color(0xFFFFFFFF)
private val DAY_HILL_BACK = Color(0xFFF6D9AE)

// 밤: 남색 하늘, 초승달, 별, 어두운 언덕
private val NIGHT_SKY_TOP = Color(0xFF0E1330)
private val NIGHT_SKY_BOTTOM = Color(0xFF232B5C)
private val NIGHT_MOON = Color(0xFFFFE6A0)
private val NIGHT_STAR = Color(0xFFFFF4D2)
private val NIGHT_CLOUD = Color(0xFF3A4378)
private val NIGHT_HILL_BACK = Color(0xFF2A3264)

/** 별 자리 (가로·세로 비율, 크기 dp, 반짝임 위상 0/1) */
private val STARS = listOf(
    Triple(0.32f, 0.14f, 3.5f), Triple(0.46f, 0.30f, 2f), Triple(0.58f, 0.10f, 2.5f),
    Triple(0.72f, 0.24f, 4f), Triple(0.88f, 0.12f, 2.5f), Triple(0.93f, 0.42f, 2f),
    Triple(0.06f, 0.52f, 2f), Triple(0.27f, 0.44f, 2.5f), Triple(0.80f, 0.50f, 2f)
)

/**
 * 홈 위쪽의 넥스티 장면. 화면 양옆 끝까지 채우고, 앞 언덕은 화면 바탕색이라 아래 목록과 경계 없이 이어짐.
 * 낮이면 해·구름과 손 흔드는 넥스티, 밤이면 달·별과 졸린 수면모자 넥스티.
 */
@Composable
fun HomeScene(night: Boolean, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "scene")
    // 구름이 좌우로 천천히 오감
    val drift by infinite.animateFloat(
        0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "drift"
    )
    // 해 빛무리·별이 숨 쉬듯 반짝임
    val glow by infinite.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow"
    )
    val ground = RespecColors.Background

    Box(modifier.fillMaxWidth().height(220.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            if (night) drawNightSky(drift, glow) else drawDaySky(drift, glow)
            drawHills(if (night) NIGHT_HILL_BACK else DAY_HILL_BACK, ground)
            if (!night) drawDayGreens(glow)
        }
        // 넥스티 캔버스는 발 아래 여백이 있어(높이의 약 15%) 발이 앞 언덕 꼭대기에 닿도록 아래 여백을 줌
        NextyCharacter(
            motion = if (night) NextyMotion.IDLE else NextyMotion.WAVE,
            face = if (night) NextyFace.SLEEPY else NextyMotion.WAVE.face,
            night = night,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .height(172.dp)
        )
    }
}

private fun DrawScope.drawDaySky(drift: Float, glow: Float) {
    drawRect(Brush.verticalGradient(listOf(DAY_SKY_TOP, DAY_SKY_BOTTOM)))

    // 왼쪽 위의 해: 빛무리 + 짧은 햇살
    val sun = Offset(size.width * 0.14f, size.height * 0.27f)
    val r = 22.dp.toPx()
    drawCircle(DAY_SUN.copy(alpha = 0.18f + 0.12f * glow), r * (1.7f + 0.15f * glow), sun)
    repeat(8) { i ->
        val a = Math.toRadians(i * 45.0 + 22.5)
        val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
        drawLine(DAY_SUN, sun + dir * (r * 1.3f), sun + dir * (r * 1.65f), 3.dp.toPx(), StrokeCap.Round)
    }
    drawCircle(DAY_SUN, r, sun)

    val move = (drift - 0.5f) * 24.dp.toPx()
    drawCloud(Offset(size.width * 0.70f + move, size.height * 0.20f), 1f, DAY_CLOUD)
    drawCloud(Offset(size.width * 0.36f - move * 0.7f, size.height * 0.40f), 0.7f, DAY_CLOUD.copy(alpha = 0.85f))
    drawCloud(Offset(size.width * 0.92f - move * 0.5f, size.height * 0.48f), 0.6f, DAY_CLOUD.copy(alpha = 0.8f))
}

private fun DrawScope.drawNightSky(drift: Float, glow: Float) {
    drawRect(Brush.verticalGradient(listOf(NIGHT_SKY_TOP, NIGHT_SKY_BOTTOM)))

    STARS.forEachIndexed { i, (x, y, d) ->
        val t = if (i % 2 == 0) glow else 1f - glow
        drawStar(Offset(size.width * x, size.height * y), d.dp.toPx() * (0.75f + 0.5f * t), NIGHT_STAR.copy(alpha = 0.5f + 0.5f * t))
    }

    // 왼쪽 위의 초승달: 큰 원에서 오른쪽 위로 비킨 원을 빼서 만듦 (하늘 그러데이션 위에서도 깔끔하게)
    val c = Offset(size.width * 0.15f, size.height * 0.27f)
    val r = 22.dp.toPx()
    drawCircle(NIGHT_MOON.copy(alpha = 0.10f + 0.08f * glow), r * 1.9f, c)
    val moon = Path().apply {
        op(
            Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) },
            Path().apply { addOval(androidx.compose.ui.geometry.Rect(c + Offset(r * 0.55f, -r * 0.35f), r * 0.9f)) },
            PathOperation.Difference
        )
    }
    drawPath(moon, NIGHT_MOON)

    val move = (drift - 0.5f) * 20.dp.toPx()
    drawCloud(Offset(size.width * 0.74f + move, size.height * 0.36f), 0.8f, NIGHT_CLOUD.copy(alpha = 0.7f))
    drawCloud(Offset(size.width * 0.40f - move * 0.6f, size.height * 0.52f), 0.55f, NIGHT_CLOUD.copy(alpha = 0.5f))
}

/** 뒤 언덕(오른쪽이 높음)과 가운데가 볼록한 앞 언덕. 앞 언덕 꼭대기는 아래에서 약 38dp */
private fun DrawScope.drawHills(back: Color, front: Color) {
    val w = size.width
    val h = size.height
    val backHill = Path().apply {
        moveTo(0f, h - 52.dp.toPx())
        cubicTo(w * 0.25f, h - 86.dp.toPx(), w * 0.5f, h - 50.dp.toPx(), w * 0.72f, h - 70.dp.toPx())
        quadraticTo(w * 0.88f, h - 86.dp.toPx(), w, h - 80.dp.toPx())
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(backHill, back)
    val frontHill = Path().apply {
        moveTo(0f, h - 18.dp.toPx())
        quadraticTo(w / 2f, h - 58.dp.toPx(), w, h - 18.dp.toPx())
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(frontHill, front)
}

/** 앞 언덕(drawHills의 곡선) 위 x 비율 t 자리의 높이 */
private fun DrawScope.frontHillY(t: Float) = size.height - 18.dp.toPx() - 80.dp.toPx() * t * (1f - t)

/**
 * 낮의 풀숲과 풀잎. 넥스티(가운데 약 ±70dp)를 피해 양쪽에만 둠.
 * 뒤 언덕 위 작은 덤불 → 앞 언덕 양 끝의 큰 덤불 → 앞 언덕의 풀잎 순으로 그림. sway(0~1)에 따라 풀잎이 살랑임
 */
private fun DrawScope.drawDayGreens(sway: Float) {
    val h = size.height
    // 뒤 언덕 (왼쪽 낮은 쪽, 오른쪽 높은 쪽)
    drawBush(Offset(size.width * 0.20f, h - 66.dp.toPx()), 0.55f, BUSH_FAR, BUSH_FAR_DARK)
    drawBush(Offset(size.width * 0.86f, h - 78.dp.toPx()), 0.6f, BUSH_FAR, BUSH_FAR_DARK)
    // 앞 언덕 양 끝
    listOf(0.06f to 1f, 0.17f to 0.65f, 0.95f to 0.95f, 0.83f to 0.6f).forEach { (t, s) ->
        drawBush(Offset(size.width * t, frontHillY(t) + 6.dp.toPx()), s, BUSH, BUSH_DARK)
    }
    // 풀잎
    listOf(0.27f, 0.34f, 0.66f, 0.73f, 0.11f, 0.89f).forEachIndexed { i, t ->
        val lean = (if (i % 2 == 0) sway else 1f - sway) - 0.5f
        drawGrass(Offset(size.width * t, frontHillY(t) + 2.dp.toPx()), lean)
    }
}

private val BUSH = Color(0xFFA6D17F)
private val BUSH_DARK = Color(0xFF86BA62)
private val BUSH_FAR = Color(0xFFC4DE9C)
private val BUSH_FAR_DARK = Color(0xFFADD083)
private val GRASS = Color(0xFF8CC269)

/** 동그라미를 겹친 덤불. base는 바닥 가운데이고, 바닥 아래로 삐져나온 부분은 잘라 언덕에 묻힌 듯 보이게 함 */
private fun DrawScope.drawBush(base: Offset, scale: Float, color: Color, shade: Color) {
    val u = 16.dp.toPx() * scale
    clipRect(top = 0f, bottom = base.y) {
        drawCircle(shade, u * 1.0f, base + Offset(-u * 1.0f, -u * 0.35f))
        drawCircle(shade, u * 0.95f, base + Offset(u * 1.05f, -u * 0.3f))
        drawCircle(color, u * 1.25f, base + Offset(0f, -u * 0.7f))
        drawCircle(color, u * 0.85f, base + Offset(-u * 0.9f, -u * 0.55f))
        drawCircle(color, u * 0.8f, base + Offset(u * 0.95f, -u * 0.5f))
        // 위쪽 밝은 점
        drawCircle(Color.White.copy(alpha = 0.25f), u * 0.28f, base + Offset(-u * 0.35f, -u * 1.35f))
    }
}

/** 세 가닥 풀잎. lean(-0.5~0.5)만큼 끝이 옆으로 기욺 */
private fun DrawScope.drawGrass(base: Offset, lean: Float) {
    val len = 11.dp.toPx()
    val sway = lean * 4.dp.toPx()
    val w = 2.2.dp.toPx()
    listOf(-4f to 0.8f, 0f to 1f, 4f to 0.75f).forEach { (dx, k) ->
        val start = base + Offset(dx.dp.toPx() * 0.6f, 0f)
        val tip = start + Offset(dx.dp.toPx() * 0.5f + sway, -len * k)
        val blade = Path().apply {
            moveTo(start.x, start.y)
            quadraticTo(start.x, (start.y + tip.y) / 2f, tip.x, tip.y)
        }
        drawPath(blade, GRASS, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** 동그라미 셋을 겹친 뭉게구름. center는 구름 바닥 가운데 */
private fun DrawScope.drawCloud(center: Offset, scale: Float, color: Color) {
    val u = 14.dp.toPx() * scale
    drawCircle(color, u, center + Offset(-u * 1.1f, -u * 0.8f))
    drawCircle(color, u * 1.4f, center + Offset(0f, -u * 1.2f))
    drawCircle(color, u * 1.05f, center + Offset(u * 1.2f, -u * 0.8f))
    drawRoundRect(
        color,
        topLeft = center + Offset(-u * 2.1f, -u * 0.9f),
        size = androidx.compose.ui.geometry.Size(u * 4.3f, u * 0.9f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.45f)
    )
}

/** 끝이 네 개인 작은 별 */
private fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val k = r * 0.25f
    val star = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
        quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
        quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
        quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
        close()
    }
    drawPath(star, color)
}
