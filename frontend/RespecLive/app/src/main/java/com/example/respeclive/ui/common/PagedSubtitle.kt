package com.example.respeclive.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.delay

private const val PAGE_MAX_CHARS = 30      // 한 페이지 최대 글자 수 (박스에 약 3줄)
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

/** 크기가 고정된 자막 박스 (아침/밤 공용). 예외적으로 글이 넘치면 박스 안에서 스크롤 */
@Composable
fun SubtitleBox(text: String) {
    val shape = RoundedCornerShape(24.dp)
    val scrollState = rememberScrollState()

    LaunchedEffect(text) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(RespecColors.Lime.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text("AI", color = RespecColors.Lime, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(scrollState),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(text, color = Color.White, fontSize = 17.sp, lineHeight = 26.sp)
        }
    }
}