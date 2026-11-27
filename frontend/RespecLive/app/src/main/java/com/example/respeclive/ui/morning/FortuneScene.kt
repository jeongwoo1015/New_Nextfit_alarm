package com.example.respeclive.ui.morning

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
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

/** 쿠키 열기 전체 길이: 떨림·금 → 반으로 갈라짐 → 쪽지가 솟아오름 */
private const val COOKIE_OPEN_MS = 1300
/** 카드를 띄우는 시점 (쪽지가 거의 다 드러난 뒤) */
private const val COOKIE_REVEAL_MS = 1000L

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
 * 아침 대화의 포춘 단계 화면. 닫힌 쿠키 → (AI가 문장을 읽기 시작하거나 탭하면) 열린 쿠키 + 포춘 카드.
 * onOpen: 쿠키를 탭함
 */
@Composable
fun FortuneScene(
    fortune: FortuneShow?,
    opened: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = "오늘의 포춘"     // 운세 스타일도 "오늘의 포춘"으로 (11/18)
    // 쿠키가 갈라지고 쪽지가 드러난 뒤에 카드를 보여 줌
    val revealed by produceState(false, opened) {
        if (opened) {
            delay(COOKIE_REVEAL_MS)
            value = true
        } else value = false
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
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
            targetState = revealed,
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
                            fortune.isHoroscope -> "오늘의 포춘이 도착했어요"
                            else -> "오늘의 포춘쿠키가 도착했어요"
                        },
                        color = RespecColors.Text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    FitText(
                        if (fortune == null) "잠시만 기다려 주세요." else "넥스티가 열어 줄 거예요. 쿠키를 톡 눌러도 열려요.",
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

/**
 * 표정이 있는 포춘쿠키. 닫혀 있을 때는 살랑살랑 흔들림.
 * 열기: 부르르 떨며 가운데에 금이 감 → 금을 따라 두 쪽으로 벌어짐 → 그 틈에서 쪽지가 솟아오르며 드러나고 반짝임이 생김
 */
@Composable
private fun FortuneCookie(opened: Boolean, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(if (opened) 1f else 0f, tween(COOKIE_OPEN_MS, easing = LinearEasing), label = "open")
    val infinite = rememberInfiniteTransition(label = "cookie")
    val wiggle by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wiggle"
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

            // 진행도 p(0~1)를 단계별로 나눔
            val crack = ((p - 0.06f) / 0.24f).coerceIn(0f, 1f)                 // 금이 위에서 아래로 번짐
            val split = easeOut(((p - 0.30f) / 0.35f).coerceIn(0f, 1f))       // 두 쪽으로 벌어짐
            val rise = backOut(((p - 0.38f) / 0.45f).coerceIn(0f, 1f))        // 쪽지가 틈에서 솟아오름
            val shine = ((p - 0.65f) / 0.35f).coerceIn(0f, 1f)                // 반짝임
            // 갈라지기 전까지 점점 세게 부르르
            val amp = if (p > 0f && split == 0f) s * 0.012f * (p / 0.3f).coerceIn(0f, 1f) else 0f
            val jx = sin(p * 160f) * amp
            val tilt = if (p == 0f) wiggle * 4f else 0f
            // 얼굴은 닫혀 있을 때만. 열기 시작하면 금이 가기 전에 바로 사라짐
            val faceAlpha = 1f - (p / 0.1f).coerceIn(0f, 1f)

            translate(jx, 0f) {
                rotate(tilt, Offset(s * 0.5f, s * 0.75f)) {
                    if (p > 0.3f) drawSlip(s, rise)      // 쿠키 속에 숨어 있다가 벌어진 틈으로 드러남
                    // 갈라지기 전에는 통째로 그림: 반쪽 두 개를 맞대어 그리면 경계(금 모양)에 가는 틈이 비쳐 열기 전부터 금이 간 것처럼 보임
                    if (split == 0f) {
                        drawCookieBody(s)
                        if (faceAlpha > 0f) drawCookieFace(s, faceAlpha)
                    }
                    // 갈라지면: 금을 경계로 왼쪽·오른쪽 반쪽을 따로 그려, 각자 아래 날개를 축으로 바깥으로 젖혀짐
                    if (split > 0f) listOf(-1f, 1f).forEach { side ->
                        translate(side * s * 0.06f * split, s * 0.015f * split) {
                            rotate(side * 18f * split, Offset(s * (0.5f + side * 0.2f), s * 0.78f)) {
                                clipPath(halfRegion(s, side)) {
                                    drawCookieBody(s)
                                    if (faceAlpha > 0f) drawCookieFace(s, faceAlpha)
                                    // 갈라진 단면 테두리
                                    if (split > 0f) clipPath(cookiePath(s)) {
                                        drawPath(crackPath(s, 1f), COOKIE_LINE, style = Stroke(s * 0.032f, join = StrokeJoin.Round))
                                    }
                                }
                            }
                        }
                    }
                    // 갈라지기 전: 위에서부터 번지는 금
                    if (split == 0f && crack > 0f) clipPath(cookiePath(s)) {
                        drawPath(crackPath(s, crack), COOKIE_LINE, style = Stroke(s * 0.012f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }

            if (p > 0f) {
                drawSparkle(Offset(s * 0.13f, s * 0.32f), s * 0.05f * shine, SPARKLE)
                drawSparkle(Offset(s * 0.87f, s * 0.24f), s * 0.035f * shine, SPARKLE_PINK)
                drawSparkle(Offset(s * 0.9f, s * 0.6f), s * 0.03f * shine, SPARKLE)
                drawSparkle(Offset(s * 0.36f, s * 0.08f), s * 0.03f * shine, SPARKLE)
            }
        }
    }
}

private fun easeOut(t: Float): Float {
    val u = 1f - t
    return 1f - u * u * u
}

/** 0→1로 가며 살짝 넘쳤다가 돌아오는 곡선 (쪽지가 쏙 튀어나왔다 자리 잡음) */
private fun backOut(t: Float): Float {
    val c = 1.7f
    val u = t - 1f
    return 1f + (c + 1f) * u * u * u + c * u * u
}

/** 쿠키 가운데를 위에서 아래로 가르는 지그재그 금 (쿠키 크기 기준 x, y) */
private val CRACK = listOf(
    0.50f to 0.18f, 0.48f to 0.27f, 0.52f to 0.35f,
    0.485f to 0.43f, 0.515f to 0.51f, 0.50f to 0.60f
)

/** 금을 위에서부터 t(0~1)만큼만 그린 선 */
private fun crackPath(s: Float, t: Float) = Path().apply {
    val n = CRACK.size - 1
    val f = t.coerceIn(0f, 1f) * n
    val k = f.toInt()
    moveTo(s * CRACK[0].first, s * CRACK[0].second)
    for (i in 1..minOf(k, n)) lineTo(s * CRACK[i].first, s * CRACK[i].second)
    if (k < n) {
        val (x0, y0) = CRACK[k]
        val (x1, y1) = CRACK[k + 1]
        val r = f - k
        lineTo(s * (x0 + (x1 - x0) * r), s * (y0 + (y1 - y0) * r))
    }
}

/** 금을 경계로 한 왼쪽(side = -1) / 오른쪽(side = 1) 영역 */
private fun halfRegion(s: Float, side: Float) = Path().apply {
    val far = if (side < 0f) -s else s * 2f
    moveTo(far, -s)
    lineTo(s * 0.5f, -s)
    CRACK.forEach { (x, y) -> lineTo(s * x, s * y) }
    lineTo(s * 0.5f, s * 2f)
    lineTo(far, s * 2f)
    close()
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

/** 닫힌 쿠키의 얼굴: 동그란 눈, 볼터치, 작은 미소. alpha: 열기 시작할 때 사라지는 정도 */
private fun DrawScope.drawCookieFace(s: Float, alpha: Float) {
    val eyeY = s * 0.45f
    val stroke = Stroke(s * 0.012f, cap = StrokeCap.Round)
    listOf(0.415f, 0.585f).forEach { x ->
        drawCircle(COOKIE_LINE, s * 0.022f, Offset(s * x, eyeY), alpha = alpha)
        drawCircle(Color.White, s * 0.007f, Offset(s * x + s * 0.007f, eyeY - s * 0.008f), alpha = alpha)
        val blushX = if (x < 0.5f) 0.32f else 0.61f     // 눈 바깥 아래 볼
        drawOval(BLUSH, Offset(s * blushX, s * 0.5f), Size(s * 0.07f, s * 0.04f), alpha = 0.85f * alpha)
    }
    drawArc(COOKIE_LINE, 20f, 140f, false, Offset(s * 0.47f, s * 0.47f), Size(s * 0.06f, s * 0.04f), alpha = alpha, style = stroke)
}

/**
 * 쿠키 속에서 솟아오르는 쪽지 (줄과 반짝임).
 * rise 0: 작게 접혀 쿠키 몸통 뒤에 완전히 가려짐 → 1: 원래 크기로 쿠키 위에 비스듬히 섬
 */
private fun DrawScope.drawSlip(s: Float, rise: Float) {
    val k = 0.5f + 0.5f * rise
    val w = s * 0.12f * k
    val h = s * 0.3f * k
    val cx = s * 0.5f
    val cy = s * (0.42f - 0.15f * rise)
    val left = cx - w / 2
    val top = cy - h / 2
    rotate(10f * rise, Offset(cx, cy)) {
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
