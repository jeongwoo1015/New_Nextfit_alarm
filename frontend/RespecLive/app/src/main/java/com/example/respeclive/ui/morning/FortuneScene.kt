package com.example.respeclive.ui.morning

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.nexty.NextyCharacter
import com.example.respeclive.ui.nexty.NextyMotion
import com.example.respeclive.ui.theme.RespecColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * 오늘의 포춘 한 장.
 * text: AI가 읽는 문장 (못 받았으면 null), style: COOKIE 포춘쿠키 / HOROSCOPE 오늘의 운세,
 * basedOn: 카드 아래 재료 표시 (예: 어젯밤 한마디 · "…"). 없으면 표시 안 함
 */
data class FortuneShow(val text: String?, val style: String?, val basedOn: String?) {
    val isHoroscope get() = style == "HOROSCOPE"
}

/** 포춘쿠키 그림 색 (일러스트라 테마와 상관없이 고정) */
private val COOKIE = Color(0xFFF7C873)
private val COOKIE_SHADE = Color(0xFFE9A955)
private val COOKIE_LINE = Color(0xFF3A2A22)
private val BLUSH = Color(0xFFF4A09A)
private val PEACH = Color(0xFFF4A65A)
private val SPARKLE = Color(0xFFFFCB45)
private val SPARKLE_PINK = Color(0xFFF7B3A6)
private val CARD = Color(0xFFFFE6A3)
private val CARD_TEXT = Color(0xFF2B2118)
private val CARD_SUB = Color(0xFF7A6650)

/**
 * 아침 대화의 포춘 단계 화면. 닫힌 쿠키 + 넥스티 말풍선 → (AI가 문장을 읽기 시작하거나 탭하면) 열린 쿠키 + 포춘 카드.
 * aiLine: 넥스티가 지금 하는 말 (닫혀 있을 때 말풍선), onOpen: 쿠키를 탭함
 */
@Composable
fun FortuneScene(
    fortune: FortuneShow?,
    opened: Boolean,
    aiLine: String,
    speaking: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = if (fortune?.isHoroscope == true) "오늘의 운세" else "오늘의 포춘"
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // 넥스티 말풍선 (쿠키를 열기 전). 열린 뒤에도 자리는 남겨 쿠키가 위아래로 움직이지 않게 함
        Box(Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            if (!opened) NextyBubble(aiLine, speaking)
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val size = minOf(maxWidth, maxHeight, 300.dp).coerceAtLeast(0.dp)
            FortuneCookie(
                opened = opened,
                modifier = Modifier
                    .size(size)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !opened,
                        onClick = onOpen
                    )
            )
        }

        AnimatedContent(
            targetState = opened,
            transitionSpec = { (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 }) togetherWith fadeOut(tween(150)) },
            label = "fortune"
        ) { isOpen ->
            if (isOpen) {
                FortuneCard(title, fortune)
            } else {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FitText(
                        when {
                            fortune == null -> "넥스티가 포춘쿠키를 굽고 있어요"
                            fortune.isHoroscope -> "오늘의 운세가 도착했어요"
                            else -> "오늘의 포춘쿠키가 도착했어요"
                        },
                        color = RespecColors.Text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    FitText(
                        if (fortune == null) "잠시만 기다려 주세요" else "넥스티가 열어 줄 거예요 · 쿠키를 톡 눌러도 열려요",
                        color = RespecColors.TextSub,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun NextyBubble(text: String, speaking: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        NextyCharacter(NextyMotion.IDLE, Modifier.size(52.dp), speaking = speaking)
        Spacer(Modifier.width(8.dp))
        val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
        Text(
            text,
            color = RespecColors.Text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = 3,
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .background(RespecColors.Card)
                .border(1.dp, RespecColors.CardBorder, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun FortuneCard(title: String, fortune: FortuneShow?) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(CARD)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 30.dp, height = 24.dp)) { drawMiniCookie() }
            Spacer(Modifier.width(8.dp))
            Text(title, color = CARD_TEXT, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            fortune?.text ?: "오늘은 포춘을 준비하지 못했어요. 그래도 좋은 하루가 될 거예요!",
            color = CARD_TEXT,
            fontSize = 17.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold
        )
        fortune?.basedOn?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = CARD_SUB, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** 표정이 있는 포춘쿠키. 열리면 쪽지가 솟아오르고 반짝임이 생김. 닫혀 있을 때는 살랑살랑 흔들리고 옆에 탭 표시 */
@Composable
private fun FortuneCookie(opened: Boolean, modifier: Modifier = Modifier) {
    val open by animateFloatAsState(if (opened) 1f else 0f, tween(700, easing = FastOutSlowInEasing), label = "open")
    val infinite = rememberInfiniteTransition(label = "cookie")
    val wiggle by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wiggle"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "pulse"
    )

    Canvas(modifier) {
        val s = size.minDimension
        translate((size.width - s) / 2, (size.height - s) / 2) {
            // 뒤쪽 은은한 원
            drawCircle(PEACH.copy(alpha = 0.08f), s * 0.48f, Offset(s * 0.5f, s * 0.52f))
            drawCircle(PEACH.copy(alpha = 0.12f), s * 0.38f, Offset(s * 0.5f, s * 0.52f))
            // 그림자
            drawOval(
                COOKIE_LINE.copy(alpha = 0.12f),
                topLeft = Offset(s * 0.2f, s * 0.82f),
                size = Size(s * 0.6f, s * 0.07f)
            )

            val pop = 1f + 0.06f * sin(PI.toFloat() * open)
            val tilt = wiggle * 4f * (1f - open)
            scale(pop, Offset(s * 0.5f, s * 0.6f)) {
                rotate(tilt, Offset(s * 0.5f, s * 0.75f)) {
                    if (open > 0f) drawSlip(s, open)      // 쿠키 뒤에서 솟아오르는 쪽지
                    drawCookieBody(s)
                    drawCookieFace(s, open)
                }
            }

            if (open > 0f) {
                drawSparkle(Offset(s * 0.13f, s * 0.32f), s * 0.05f * open, SPARKLE)
                drawSparkle(Offset(s * 0.87f, s * 0.24f), s * 0.035f * open, SPARKLE_PINK)
                drawSparkle(Offset(s * 0.9f, s * 0.6f), s * 0.03f * open, SPARKLE)
            } else {
                // 탭 표시: 점과 퍼지는 고리
                val c = Offset(s * 0.93f, s * 0.64f)
                drawCircle(PEACH.copy(alpha = 0.5f * (1f - pulse)), s * (0.04f + 0.04f * pulse), c, style = Stroke(s * 0.008f))
                drawCircle(Color.White, s * 0.045f, c)
                drawCircle(PEACH, s * 0.045f, c, style = Stroke(s * 0.008f))
                drawCircle(PEACH, s * 0.022f, c)
            }
        }
    }
}

/** 쿠키 몸통 윤곽: 위는 둥근 돔, 아래는 가운데가 ∧ 모양으로 접힌 두 날개 */
private fun cookiePath(s: Float) = Path().apply {
    moveTo(s * 0.12f, s * 0.72f)
    cubicTo(s * 0.08f, s * 0.38f, s * 0.28f, s * 0.22f, s * 0.5f, s * 0.22f)
    cubicTo(s * 0.72f, s * 0.22f, s * 0.92f, s * 0.38f, s * 0.88f, s * 0.72f)
    quadraticTo(s * 0.80f, s * 0.80f, s * 0.70f, s * 0.75f)
    lineTo(s * 0.5f, s * 0.57f)
    lineTo(s * 0.30f, s * 0.75f)
    quadraticTo(s * 0.20f, s * 0.80f, s * 0.12f, s * 0.72f)
    close()
}

private fun DrawScope.drawCookieBody(s: Float) {
    val body = cookiePath(s)
    drawPath(body, COOKIE)
    clipPath(body) {
        // 아래 날개 쪽 진한 색
        drawOval(COOKIE_SHADE, Offset(s * 0.10f, s * 0.62f), Size(s * 0.34f, s * 0.24f))
        drawOval(COOKIE_SHADE, Offset(s * 0.56f, s * 0.62f), Size(s * 0.34f, s * 0.24f))
        // 왼쪽 위 하이라이트
        drawArc(
            Color.White.copy(alpha = 0.55f), 200f, 50f, false,
            Offset(s * 0.2f, s * 0.29f), Size(s * 0.6f, s * 0.6f),
            style = Stroke(s * 0.03f, cap = StrokeCap.Round)
        )
    }
    // 가운데 위 접힌 선
    val seam = Path().apply {
        moveTo(s * 0.5f, s * 0.225f)
        quadraticTo(s * 0.47f, s * 0.29f, s * 0.49f, s * 0.36f)
    }
    drawPath(seam, COOKIE_SHADE, style = Stroke(s * 0.012f, cap = StrokeCap.Round))
    drawPath(body, COOKIE_LINE, style = Stroke(s * 0.016f, join = StrokeJoin.Round))
}

/** 닫혀 있으면 동그란 눈과 작은 미소, 열리면 웃는 눈(^ ^)과 벌린 입 */
private fun DrawScope.drawCookieFace(s: Float, open: Float) {
    val eyeY = s * 0.45f
    val stroke = Stroke(s * 0.012f, cap = StrokeCap.Round)
    listOf(0.415f, 0.585f).forEach { x ->
        if (open < 0.5f) {
            drawCircle(COOKIE_LINE, s * 0.022f, Offset(s * x, eyeY))
            drawCircle(Color.White, s * 0.007f, Offset(s * x + s * 0.007f, eyeY - s * 0.008f))
        } else {
            drawArc(COOKIE_LINE, 200f, 140f, false, Offset(s * (x - 0.03f), eyeY - s * 0.012f), Size(s * 0.06f, s * 0.05f), style = stroke)
        }
        val blushX = if (x < 0.5f) 0.32f else 0.61f     // 눈 바깥 아래 볼
        drawOval(BLUSH.copy(alpha = 0.85f), Offset(s * blushX, s * 0.5f), Size(s * 0.07f, s * 0.04f))
    }
    if (open < 0.5f) {
        drawArc(COOKIE_LINE, 20f, 140f, false, Offset(s * 0.47f, s * 0.47f), Size(s * 0.06f, s * 0.04f), style = stroke)
    } else {
        val mouth = Path().apply {
            moveTo(s * 0.465f, s * 0.49f)
            lineTo(s * 0.535f, s * 0.49f)
            quadraticTo(s * 0.535f, s * 0.545f, s * 0.5f, s * 0.545f)
            quadraticTo(s * 0.465f, s * 0.545f, s * 0.465f, s * 0.49f)
            close()
        }
        drawPath(mouth, COOKIE_LINE)
        drawOval(BLUSH, Offset(s * 0.483f, s * 0.522f), Size(s * 0.034f, s * 0.02f))
    }
}

/** 쿠키 위로 솟아오르는 쪽지 (줄과 반짝임) */
private fun DrawScope.drawSlip(s: Float, open: Float) {
    val w = s * 0.12f
    val h = s * 0.3f
    val left = s * 0.5f
    val top = s * 0.02f + s * 0.16f * (1f - open)
    rotate(16f, Offset(left + w / 2, top + h)) {
        drawRoundRect(Color.White, Offset(left, top), Size(w, h), CornerRadius(s * 0.012f))
        drawRoundRect(COOKIE_LINE, Offset(left, top), Size(w, h), CornerRadius(s * 0.012f), style = Stroke(s * 0.01f))
        drawSparkle(Offset(left + w * 0.5f, top + h * 0.17f), w * 0.26f, SPARKLE)
        listOf(0.36f, 0.48f, 0.6f).forEach { y ->
            drawLine(COOKIE_LINE.copy(alpha = 0.45f), Offset(left + w * 0.22f, top + h * y), Offset(left + w * 0.78f, top + h * y), s * 0.008f, StrokeCap.Round)
        }
    }
}

/** 카드 제목 옆 작은 쿠키 아이콘 */
private fun DrawScope.drawMiniCookie() {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.04f, h * 0.9f)
        cubicTo(w * 0.0f, h * 0.2f, w * 0.3f, h * 0.05f, w * 0.5f, h * 0.05f)
        cubicTo(w * 0.7f, h * 0.05f, w * 1.0f, h * 0.2f, w * 0.96f, h * 0.9f)
        lineTo(w * 0.5f, h * 0.45f)
        close()
    }
    drawPath(path, COOKIE)
    drawPath(path, COOKIE_LINE, style = Stroke(w * 0.06f, join = StrokeJoin.Round))
}

/** 끝이 네 개인 반짝임 */
private fun DrawScope.drawSparkle(c: Offset, r: Float, color: Color) {
    if (r <= 0f) return
    val k = r * 0.22f
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
