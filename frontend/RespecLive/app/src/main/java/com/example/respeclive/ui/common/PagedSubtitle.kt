package com.example.respeclive.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.respeclive.live.LiveClient
import com.example.respeclive.live.LiveConfig
import com.example.respeclive.ui.theme.DarkPalette
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.delay
import kotlin.math.ceil

private val SUBTITLE_HEIGHT = 168.dp     // 자막 박스 높이 (세로가 짧은 폰에서는 rememberScreenScale만큼 줄어듦)
private const val PAGE_MAX_CHARS = 64     // 한 페이지 최대 글자 수 (박스 5줄, 한 줄 약 15자). 문장 단위로 담음
private const val CHAR_TYPE_MS = 60L       // 글자가 나오는 속도
private const val HOLD_PER_CHAR_MS = 90L   // 페이지를 유지하는 시간. 음성보다 빠르면 키우고, 느리면 줄이기

/**
 * 자막 한 줄을 박스(페이지)로 나눔. 받아쓰기의 수 읽기("오 초")는 숫자 표기("5초")로 바꿔 보여 줌 (SubtitleNumbers)
 * - 한 문장은 한 박스에 다 보이게: 문장을 이어 담다가 다음 문장이 들어가지 않으면 그 문장은 통째로 다음 박스로.
 * - 한 문장이 박스 하나를 넘으면 splitLongSentence로 어색하지 않은 곳(쉼표, "~고/~서/~면" 같은 연결 어미)에서 비슷한 길이로 나눔.
 */
fun splitSubtitlePages(text: String, maxChars: Int = PAGE_MAX_CHARS): List<String> {
    val clean = SubtitleNumbers.apply(text.trim().replace(Regex("\\s+"), " "))
    if (clean.isEmpty()) return listOf("")

    val pages = mutableListOf<String>()
    var cur = ""
    for (s in Regex("(?<=[.!?…])\\s+").split(clean)) {
        if (s.length > maxChars) {
            if (cur.isNotEmpty()) pages += cur
            val parts = splitLongSentence(s, maxChars)
            pages += parts.dropLast(1)
            cur = parts.last()      // 긴 문장의 끝 조각 뒤에는 다음 문장이 통째로 들어갈 때만 이어 담음
            continue
        }
        when {
            cur.isEmpty() -> cur = s
            cur.length + 1 + s.length <= maxChars -> cur += " $s"
            else -> {
                pages += cur
                cur = s
            }
        }
    }
    if (cur.isNotEmpty()) pages += cur
    return pages
}

/** 끊어도 자연스러운 연결 어미 (이 어미로 끝나는 어절 뒤에서 나누기를 좋아함) */
private val SOFT_ENDINGS = listOf(
    "는데", "은데", "지만", "니까", "으면", "려고", "도록", "거나", "든지", "면서", "아서", "어서", "해서",
    "고", "며", "면", "서", "데", "게"
)

/**
 * 박스 하나를 넘는 한 문장을 필요한 만큼(최소 개수)의 조각으로 나눔. 조각 길이는 비슷하게 맞추고,
 * 그중에서도 쉼표 뒤 > 연결 어미 뒤 > 그냥 띄어쓰기 순으로 끊기 좋은 곳을 고름. 띄어쓰기가 없는 긴 덩어리만 글자 수로 자름.
 */
internal fun splitLongSentence(sentence: String, maxChars: Int): List<String> {
    val words = sentence.split(" ").flatMap { w -> w.chunked(maxChars) }   // 박스보다 긴 어절은 어쩔 수 없이 자름
    val parts = mutableListOf<String>()
    var start = 0
    while (start < words.size) {
        val rest = words.subList(start, words.size)
        val restLen = rest.joinToString(" ").length
        if (restLen <= maxChars) {
            parts += rest.joinToString(" ")
            break
        }
        val piecesLeft = (restLen + maxChars - 1) / maxChars
        val target = restLen.toFloat() / piecesLeft
        // 이번 조각을 words[start..end]로 할 때의 점수. 남은 글이 남은 조각 수 안에 들어가는 끊는 곳만 후보
        var bestEnd = start
        var bestScore = Float.NEGATIVE_INFINITY
        var len = -1
        for (end in start until words.size - 1) {
            len += 1 + words[end].length
            if (len > maxChars) break
            val remain = restLen - len - 1
            val fits = remain <= maxChars * (piecesLeft - 1)
            val w = words[end].trimEnd('"', '”', '\'', ')')
            val bonus = when {
                w.endsWith(",") -> 10f
                SOFT_ENDINGS.any { w.endsWith(it) } -> 5f
                else -> 0f
            }
            val score = bonus - kotlin.math.abs(len - target) - (if (fits) 0f else 100f)
            if (score > bestScore) {
                bestScore = score
                bestEnd = end
            }
        }
        parts += words.subList(start, bestEnd + 1).joinToString(" ")
        start = bestEnd + 1
    }
    return parts
}

suspend fun typeSubtitlePages(
    text: String,
    onPage: (String) -> Unit,
    isSkipped: () -> Boolean
) {
    val pages = splitSubtitlePages(text)
    for ((idx, page) in pages.withIndex()) {
        for (i in 1..page.length) {
            if (isSkipped()) {
                onPage(pages.last())
                return
            }
            onPage(page.substring(0, i))
            delay(CHAR_TYPE_MS)
        }
        if (idx < pages.lastIndex) {
            var hold = page.length * HOLD_PER_CHAR_MS
            while (hold > 0) {
                if (isSkipped()) {
                    onPage(pages.last())
                    return
                }
                delay(50)
                hold -= 50
            }
        }
    }
}

/**
 * 사용자 말(받아쓰기)이 바로 AI 말로 덮이지 않도록 붙잡아 둠.
 * 마지막 사용자 줄이 바뀐 뒤 보여 주는 시간(LiveConfig.userHoldMs, 말 길이에 맞춰 1.5~3초)이 지나기 전이면 그 줄을, 아니면 null.
 * 그동안 AI 음성도 같은 시간만큼 미룸 (LiveClient)
 */
@Composable
fun rememberHeldUserLine(lines: List<LiveClient.Line>): LiveClient.Line? {
    val lastUser = lines.lastOrNull { it.role == LiveClient.Role.User }
    var holdUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lastUser?.atMs, lastUser?.text) {
        if (lastUser == null) return@LaunchedEffect
        val hold = LiveConfig.userHoldMs(lastUser.text)
        holdUntil = System.currentTimeMillis() + hold
        now = System.currentTimeMillis()
        delay(hold)
        now = System.currentTimeMillis()
    }
    val afterUser = lines.lastOrNull()?.role == LiveClient.Role.Assistant && lines.getOrNull(lines.lastIndex - 1) === lastUser
    return lastUser?.takeIf { afterUser && now < holdUntil }
}

/** 지금 보여 줄 자막 한 박스. key가 바뀌면 새 박스로 넘어가는 효과 */
class SpokenPage(val key: Any, val text: String)

/**
 * AI 받아쓰기 한 줄을 박스(페이지)로 나눠, 지금 말하고 있는 박스를 통째로 보여 줌 (fraction: 이번 응답 중 재생된 비율).
 * 글자는 말보다 먼저 완전하게 떠 있고, 말이 다음 박스 내용에 이르면 넘어감. 한 번 넘어간 박스로는 돌아가지 않음.
 * lineKey: 받아쓰기 줄마다 다른 값 (줄이 바뀌면 첫 박스부터)
 */
@Composable
fun rememberSpokenPage(lineKey: Any, text: String, fraction: Float): SpokenPage {
    val pages = splitSubtitlePages(text)
    val shown = remember(lineKey) { intArrayOf(0) }
    val target = if (fraction >= 1f) pages.lastIndex else {
        val pos = ceil(pages.sumOf { it.length + 1 } * fraction).toInt()     // 숫자 표기로 바뀐 글자 기준
        var end = 0
        pages.indexOfFirst { p -> end += p.length + 1; pos < end }.takeIf { it >= 0 } ?: pages.lastIndex
    }
    shown[0] = maxOf(shown[0], target).coerceAtMost(pages.lastIndex)
    return SpokenPage(lineKey to shown[0], pages[shown[0]])
}

/** 자막 박스의 기본 높이 (세로가 짧은 폰에서는 rememberScreenScale만큼 줄어듦) */
@Composable
fun rememberSubtitleHeight(): Dp = SUBTITLE_HEIGHT * rememberScreenScale()

private val BUBBLE_TAIL_HEIGHT = 12.dp
private val BUBBLE_TAIL_WIDTH = 24.dp
private val BOX_CORNER = 24.dp
/**
 * 사용자 말 칸: AI 말풍선처럼 테두리 선으로 모양을 잡고, 테마의 포인트 색(아침 오렌지 / 밤 노랑)으로 구분.
 * 선만 포인트 색이고 안쪽은 같은 색을 아주 옅게 깔아서 진해 보이지 않게. 글자는 테마 글자색 그대로.
 * (고정 노랑은 아침 테마의 크림·오렌지와 따로 놀아서 테마 색을 따름)
 */
private val USER_BUBBLE_LINE get() = RespecColors.Lime
private val USER_BUBBLE_FILL get() = RespecColors.Lime.copy(alpha = if (RespecColors.palette === DarkPalette) 0.10f else 0.12f)
/** 자막 글자의 좌우 최소 여백 (가운데 정렬이라 가장 긴 줄의 양옆 여백) */
private val SUBTITLE_SIDE_PADDING = 26.dp

/** 위쪽 가운데에 꼬리가 달린 말풍선 모양 (자막 박스 위에 있는 넥스티를 가리킴) */
private object TopTailBubbleShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tailH = with(density) { BUBBLE_TAIL_HEIGHT.toPx() }
        val tailW = with(density) { BUBBLE_TAIL_WIDTH.toPx() }
        val r = with(density) { BOX_CORNER.toPx() }
        val body = Path().apply {
            addRoundRect(RoundRect(0f, tailH, size.width, size.height, CornerRadius(r)))
        }
        val cx = size.width / 2
        val tail = Path().apply {
            moveTo(cx - tailW / 2, tailH + 1f)
            lineTo(cx, 0f)
            lineTo(cx + tailW / 2, tailH + 1f)
            close()
        }
        return Outline.Generic(Path().apply { op(body, tail, PathOperation.Union) })
    }
}

/**
 * 사용자 말 박스: 말풍선 몸통과 같은 자리·크기의 둥근 박스 (꼬리만 없음). 글자 위치가 AI 말풍선과 똑같도록.
 * Outline.Rounded로 돌려주면 border가 모양이 아닌 칸 전체 크기로 그려져(위 테두리는 잘리고 아래만 밝은 선이 보임) 경로(Generic)로 돌려줌
 */
private object UserBoxShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tailH = with(density) { BUBBLE_TAIL_HEIGHT.toPx() }
        val r = with(density) { BOX_CORNER.toPx() }
        return Outline.Generic(Path().apply { addRoundRect(RoundRect(0f, tailH, size.width, size.height, CornerRadius(r))) })
    }
}

/**
 * 크기가 고정된 자막 박스 (아침/밤 공용). AI와 사용자의 말을 같은 자리에 보여 줌.
 * AI 말은 위의 넥스티를 가리키는 말풍선(11/20 미팅), 사용자 말은 꼬리 없이 테마 포인트 색 테두리 + 아주 옅은 같은 색 안쪽으로 색만 다르게 구분.
 * 두 박스는 몸통 자리·여백이 같아서 말한 사람이 바뀌어도 글자 위치가 움직이지 않음. 글자는 가운데 정렬, 좌우 최소 여백은 SUBTITLE_SIDE_PADDING.
 * 글이 박스를 넘으면 스크롤하지 않고 다음 페이지(새 박스)로 넘어감: 늘어나는 글은 마지막 페이지만 보여 줌.
 * pageKey: rememberSpokenPage로 고른 박스면 그 key (없으면 페이지 수로 넘어감을 판단)
 * height: 자리가 모자랄 때 줄인 높이 (없으면 기본 높이). 말풍선은 꼬리까지 이 높이 안에 그림
 */
@Composable
fun SubtitleBox(text: String, fromUser: Boolean = false, pageKey: Any? = null, modifier: Modifier = Modifier, height: Dp? = null) {
    val shape = if (fromUser) UserBoxShape else TopTailBubbleShape
    val height = height ?: rememberSubtitleHeight()
    val pages = splitSubtitlePages(text)
    val page = pages.last()

    Box(
        modifier
            .height(height)
            .fillMaxWidth()
            .clip(shape)
            .background(if (fromUser) USER_BUBBLE_FILL else RespecColors.Card)
            .border(if (fromUser) 1.5.dp else 1.dp, if (fromUser) USER_BUBBLE_LINE else RespecColors.CardBorder, shape)
            .padding(start = SUBTITLE_SIDE_PADDING, end = SUBTITLE_SIDE_PADDING, bottom = 18.dp, top = 18.dp + BUBBLE_TAIL_HEIGHT)
    ) {
        // 말한 사람이 바뀌거나 페이지가 넘어가면 새 박스처럼 위로 밀려 올라옴
        AnimatedContent(
            targetState = Triple(fromUser, pageKey ?: pages.size, page),
            contentKey = { (user, key, _) -> user to key },   // 같은 페이지 안에서 글자가 늘 때는 움직이지 않음
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically { -it / 2 } + fadeOut(tween(160)))
            },
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
            label = "subtitlePage"
        ) { (user, _, shown) ->
            // 받아쓰기는 몇 글자씩 덩어리로 들어오므로, 박스마다 첫 글자부터 하나씩 타자 치듯 보여 줌 (글이 늘면 이어서 침).
            // 사용자 말은 이미 한 말이라 빠르게 침 (길게 말하면 다 뜨기 전에 AI 말로 넘어가던 문제, 11/26)
            val typeMs = if (user) LiveConfig.USER_CHAR_TYPE_MS else CHAR_TYPE_MS
            // 앞부분이 바뀌면(예: "오 초" → "5초") 같은 데까지만 남기고 거기서부터 다시 침.
            // 아직 안 나온 글자는 투명하게 자리를 잡아 두어 줄바꿈이 흔들리지 않음
            val full by rememberUpdatedState(shown)
            var typed by remember { mutableIntStateOf(0) }
            var typedText by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                while (true) {
                    val f = full
                    val keep = minOf(typed, f.commonPrefixWith(typedText).length)
                    typed = if (keep < f.length) keep + 1 else keep
                    typedText = f.take(typed)
                    delay(typeMs)
                }
            }
            val n = minOf(typed, shown.commonPrefixWith(typedText).length)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // 글 상자를 박스 폭에 꽉 채우고 가운데 정렬: 줄마다 좌우 여백이 같고, 가장 긴 줄도 양옆 SUBTITLE_SIDE_PADDING 안쪽
                // 폭이 좁거나 글꼴이 커서 한 페이지가 박스를 넘으면 글자를 줄여 박스 안에 맞춤
                BasicText(
                    buildAnnotatedString {
                        append(shown.take(n))
                        withStyle(SpanStyle(color = Color.Transparent)) { append(shown.drop(n)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = TextStyle(color = RespecColors.Text, fontSize = 17.sp, lineHeight = 1.53.em, textAlign = TextAlign.Center),
                    autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 17.sp, stepSize = 0.5.sp)
                )
            }
        }
    }
}
