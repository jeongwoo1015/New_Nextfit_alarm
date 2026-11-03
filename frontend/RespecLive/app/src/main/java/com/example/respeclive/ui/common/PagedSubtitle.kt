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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.respeclive.live.LiveClient
import com.example.respeclive.live.LiveConfig
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.delay
import kotlin.math.ceil

private val SUBTITLE_HEIGHT = 168.dp     // 자막 박스 높이 (세로가 짧은 폰에서는 rememberScreenScale만큼 줄어듦)
private const val PAGE_MAX_CHARS = 64     // 한 페이지 최대 글자 수 (박스 5줄, 한 줄 약 15자). 문장 단위로 담음
private const val CHAR_TYPE_MS = 60L       // 글자가 나오는 속도
private const val HOLD_PER_CHAR_MS = 90L   // 페이지를 유지하는 시간. 음성보다 빠르면 키우고, 느리면 줄이기

fun splitSubtitlePages(text: String, maxChars: Int = PAGE_MAX_CHARS): List<String> {
    val clean = text.trim().replace("\n", " ")
    if (clean.isEmpty()) return listOf("")

    val sentences = Regex("(?<=[.!?…])\\s+").split(clean)
    val chunks = mutableListOf<String>()
    for (s in sentences) {
        if (s.length <= maxChars) {
            chunks += s
            continue
        }
        var cur = ""
        for (w in s.split(" ")) {
            var word = w
            while (word.length > maxChars) {
                if (cur.isNotEmpty()) {
                    chunks += cur
                    cur = ""
                }
                chunks += word.take(maxChars)
                word = word.drop(maxChars)
            }
            when {
                cur.isEmpty() -> cur = word
                cur.length + 1 + word.length <= maxChars -> cur += " $word"
                else -> {
                    chunks += cur
                    cur = word
                }
            }
        }
        if (cur.isNotEmpty()) chunks += cur
    }

    val pages = mutableListOf<String>()
    var cur = ""
    for (c in chunks) {
        when {
            cur.isEmpty() -> cur = c
            cur.length + 1 + c.length <= maxChars -> cur += " $c"
            else -> {
                pages += cur
                cur = c
            }
        }
    }
    if (cur.isNotEmpty()) pages += cur
    return pages
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

/** 사용자가 말을 마친 뒤 사용자 말을 보여 주는 시간. 그동안 AI 음성도 미룸 (LiveClient) */
private const val USER_HOLD_MS = LiveConfig.USER_GAP_MS

/**
 * 사용자 말(받아쓰기)이 바로 AI 말로 덮이지 않도록 붙잡아 둠.
 * 마지막 사용자 줄이 바뀐 뒤 USER_HOLD_MS가 지나기 전이면 그 줄을, 아니면 null.
 */
@Composable
fun rememberHeldUserLine(lines: List<LiveClient.Line>): LiveClient.Line? {
    val lastUser = lines.lastOrNull { it.role == LiveClient.Role.User }
    var holdUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lastUser?.atMs, lastUser?.text) {
        if (lastUser == null) return@LaunchedEffect
        holdUntil = System.currentTimeMillis() + USER_HOLD_MS
        now = System.currentTimeMillis()
        delay(USER_HOLD_MS)
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
        val pos = ceil(text.length * fraction).toInt()
        var end = 0
        pages.indexOfFirst { p -> end += p.length + 1; pos < end }.takeIf { it >= 0 } ?: pages.lastIndex
    }
    shown[0] = maxOf(shown[0], target).coerceAtMost(pages.lastIndex)
    return SpokenPage(lineKey to shown[0], pages[shown[0]])
}

/**
 * 크기가 고정된 자막 박스 (아침/밤 공용). AI와 사용자의 말을 같은 자리에 보여 주고, 왼쪽 프로필과 박스 색으로 구분.
 * 글이 박스를 넘으면 스크롤하지 않고 다음 페이지(새 박스)로 넘어감: 늘어나는 글은 마지막 페이지만 보여 줌.
 * pageKey: rememberSpokenPage로 고른 박스면 그 key (없으면 페이지 수로 넘어감을 판단)
 */
@Composable
fun SubtitleBox(text: String, fromUser: Boolean = false, pageKey: Any? = null, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    val height = SUBTITLE_HEIGHT * rememberScreenScale()
    val pages = splitSubtitlePages(text)
    val page = pages.last()
    val accent = if (fromUser) RespecColors.Purple else RespecColors.Lime

    Row(
        modifier
            .height(height)
            .fillMaxWidth()
            .clip(shape)
            .background(if (fromUser) RespecColors.PurpleDark.copy(alpha = 0.55f) else RespecColors.Card)
            .border(1.dp, if (fromUser) RespecColors.Purple.copy(alpha = 0.6f) else RespecColors.CardBorder, shape)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (fromUser) "나" else "AI", color = accent, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(14.dp))
        // 말한 사람이 바뀌거나 페이지가 넘어가면 새 박스처럼 위로 밀려 올라옴
        AnimatedContent(
            targetState = Triple(fromUser, pageKey ?: pages.size, page),
            contentKey = { (user, key, _) -> user to key },   // 같은 페이지 안에서 글자가 늘 때는 움직이지 않음
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically { -it / 2 } + fadeOut(tween(160)))
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
            label = "subtitlePage"
        ) { (_, _, shown) ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                // 폭이 좁거나 글꼴이 커서 한 페이지가 박스를 넘으면 글자를 줄여 박스 안에 맞춤
                BasicText(
                    shown,
                    style = TextStyle(color = Color.White, fontSize = 17.sp, lineHeight = 1.53.em),
                    autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 17.sp, stepSize = 0.5.sp)
                )
            }
        }
    }
}
