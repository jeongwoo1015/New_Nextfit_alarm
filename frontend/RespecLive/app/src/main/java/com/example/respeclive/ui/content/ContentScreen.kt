package com.example.respeclive.ui.content

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.RespecApi
import com.example.respeclive.data.WordChainStore
import com.example.respeclive.data.WordChainWinner
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 콘텐츠 탭의 책갈피 3개. 게임 안에서 끝말잇기·초성 게임·퀴즈는 카드 제목으로 구분 */
private enum class ContentTab(val label: String, val icon: ImageVector, val contentType: String, val emptyText: String) {
    STRETCH("스트레칭", Icons.Filled.SelfImprovement, "EXERCISE", "오늘은 스트레칭 모닝콜이 없었어요"),
    ENGLISH("영어", Icons.Filled.Translate, "LANGUAGE", "오늘은 영어 모닝콜이 없었어요"),
    GAME("게임", Icons.Filled.SportsEsports, "QUIZ", "오늘은 게임 모닝콜이 없었어요"),
}

private fun tabOf(s: ContentSession): ContentTab? = ContentTab.entries.firstOrNull { it.contentType == s.contentType }

/**
 * 콘텐츠 탭 (예전 루틴 탭 자리): 오늘 모닝콜에서 한 콘텐츠를 스트레칭 / 영어 / 게임 책갈피로 나눠 보여 줌.
 * 스트레칭은 한 동작, 영어는 따라 읽은 문장, 게임은 알람 이름·승패와 이어 하기(또는 주고받은 내용 보기).
 * 단계 이름·결과는 일기 API(GET /diary/{date})에서, 문장 같은 내용은 콘텐츠 API(서버 준비 전)에서 받음
 */
@Composable
fun ContentScreen(onResumeGame: (sessionId: String) -> Unit) {
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val todayLabel = remember {
        val c = Calendar.getInstance()
        "${c.get(Calendar.MONTH) + 1}월 ${c.get(Calendar.DAY_OF_MONTH)}일"
    }
    var sessions by remember { mutableStateOf<List<ContentSession>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    // 이어 하기를 마치고 돌아오거나 앱을 다시 열면 새로 불러옴
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) reload++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(reload) {
        val (day, extra) = coroutineScope {
            val d = async { RespecApi.diaryDay(today) }
            val c = async { RespecApi.contentSteps(today) }
            d.await() to c.await()
        }
        failed = day == null && sessions == null
        if (day != null) sessions = parseSessions(day, extra).filter { it.callType == "CONTENT" && tabOf(it) != null }
    }

    // 처음에는 가장 최근 모닝콜의 책갈피 (한 번 고르면 그대로)
    var picked by rememberSaveable { mutableStateOf<ContentTab?>(null) }
    val list = sessions
    val tab = picked ?: list?.lastOrNull()?.let { tabOf(it) } ?: ContentTab.STRETCH
    val counts = ContentTab.entries.associateWith { t -> list?.count { tabOf(it) == t } ?: 0 }

    Column(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text("$todayLabel 모닝콜에서 한 콘텐츠", color = RespecColors.TextSub, fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        TabBar(selected = tab, counts = counts, onSelect = { picked = it })
        Spacer(Modifier.height(18.dp))

        when {
            list == null && failed -> Empty(tab.icon, "서버에 연결하지 못했어요", "잠시 뒤 다시 열어 주세요.")
            list == null -> Empty(tab.icon, "불러오는 중…", null)
            else -> {
                val items = list.filter { tabOf(it) == tab }.reversed()     // 최근 모닝콜이 위로
                if (items.isEmpty()) {
                    Empty(tab.icon, tab.emptyText, "${tab.label} 알람을 받으면 여기에 기록이 남아요.")
                } else {
                    // 게임: 최고 기록·이긴 횟수는 카드마다가 아니라 위에 한 번만
                    if (tab == ContentTab.GAME) {
                        GameStats()
                        Spacer(Modifier.height(14.dp))
                    }
                    items.forEachIndexed { i, s ->
                        if (i > 0) Spacer(Modifier.height(14.dp))
                        when (tab) {
                            ContentTab.STRETCH -> StretchCard(s)
                            ContentTab.ENGLISH -> EnglishCard(s)
                            ContentTab.GAME -> GameCard(s, onResumeGame)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

// ---------------- 책갈피 ----------------

/** 알약 모양 세그먼트: 고른 칸은 강조색으로 채우고, 오늘 기록이 있는 칸은 글자를 조금 더 진하게 */
@Composable
private fun TabBar(selected: ContentTab, counts: Map<ContentTab, Int>, onSelect: (ContentTab) -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ContentTab.entries.forEach { t ->
            val on = t == selected
            val bg by animateColorAsState(if (on) RespecColors.Button else Color.Transparent, label = "tabBg")
            val fg = when {
                on -> RespecColors.OnButton
                (counts[t] ?: 0) > 0 -> RespecColors.Text
                else -> RespecColors.TextSub
            }
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(bg)
                    .clickable { onSelect(t) }
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(t.icon, null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t.label, color = fg, fontSize = 15.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold)
            }
        }
    }
}

// ---------------- 카드 ----------------

/** 카드 머리: 아이콘 · 제목 · 알람 이름과 시각 · 오른쪽 표시. 그 아래 내용 */
@Composable
private fun ContentCard(
    icon: ImageVector,
    title: String,
    session: ContentSession,
    trailing: (@Composable () -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(RespecColors.PurpleDark),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = RespecColors.AccentText, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = RespecColors.Text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(2.dp))
                Text(alarmCaption(session), color = RespecColors.TextSub, fontSize = 13.sp, maxLines = 1)
            }
            trailing?.let { Spacer(Modifier.width(8.dp)); it() }
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = RespecColors.CardBorder)
        Spacer(Modifier.height(14.dp))
        body()
    }
}

/** 그 모닝콜 알람의 이름 (기기에 알람이 없거나 이름이 비었으면 "모닝콜") */
private fun alarmName(s: ContentSession): String =
    s.alarmServerId?.let { id -> AlarmRepository.alarms.firstOrNull { it.serverId == id }?.label }?.takeIf { it.isNotBlank() } ?: "모닝콜"

/** "알람 이름 · 07:00" (시각은 그날 알람에 설정해 둔 시각 base_wake_time) */
private fun alarmCaption(s: ContentSession): String = listOfNotNull(alarmName(s), s.wakeTime?.take(5)).joinToString(" · ")

@Composable
private fun StretchCard(s: ContentSession) {
    val done = s.steps.count { it.status == "DONE" }
    ContentCard(
        icon = Icons.Filled.SelfImprovement,
        title = stretchAreaName(s.stretchArea)?.let { "$it 스트레칭" } ?: "스트레칭",
        session = s,
        trailing = { if (s.steps.isNotEmpty()) Pill("$done / ${s.steps.size}") }
    ) {
        if (s.steps.isEmpty()) {
            Sub("스트레칭을 시작하지 않았어요.")
            return@ContentCard
        }
        LinearProgressIndicator(
            progress = { done.toFloat() / s.steps.size },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = RespecColors.Mint,
            trackColor = RespecColors.Background,
            drawStopIndicator = {}
        )
        Spacer(Modifier.height(14.dp))
        s.steps.forEachIndexed { i, st ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(RespecColors.Background),
                    contentAlignment = Alignment.Center
                ) { Text("${st.order}", color = RespecColors.TextSub, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.name, color = RespecColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                StatusIcon(st.status)
            }
        }
    }
}

@Composable
private fun EnglishCard(s: ContentSession) {
    val phrases = s.steps.mapNotNull { st -> parseExpression(st.script)?.let { st to it } }
    val answer = s.steps.firstNotNullOfOrNull { st -> parseQuestion(st.script)?.let { st to it } }
    val done = s.steps.count { it.status == "DONE" }
    ContentCard(
        icon = Icons.Filled.Translate,
        title = "영어 따라 읽기",
        session = s,
        trailing = { if (s.steps.isNotEmpty()) Pill("$done / ${s.steps.size}") }
    ) {
        when {
            s.steps.isEmpty() -> Sub("영어 따라 읽기를 시작하지 않았어요.")
            phrases.isEmpty() && answer == null -> {
                // 서버가 문장을 앱에 주기 전: 단계 결과만
                s.steps.forEachIndexed { i, st ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(st.name, color = RespecColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        StatusIcon(st.status)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Sub("따라 읽은 문장은 서버 준비가 끝나면 여기에 보여요.")
            }
            else -> {
                phrases.forEachIndexed { i, (st, ex) ->
                    if (i > 0) Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(ex.english, color = RespecColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            ex.meaning?.let { Spacer(Modifier.height(3.dp)); Text(it, color = RespecColors.Text, fontSize = 14.sp) }
                        }
                        StatusIcon(st.status)
                    }
                }
                answer?.let { (st, q) ->
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("한마디 대답", color = RespecColors.AccentText, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        StatusIcon(st.status)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(q.question, color = RespecColors.Text, fontSize = 15.sp)
                    q.example?.let { Spacer(Modifier.height(3.dp)); Sub("예시 답: $it") }
                }
            }
        }
    }
}

/** 게임 탭 위: 끝말잇기 최고 기록(한 판에서 가장 많이 주고받은 단어 수)과 넥스티를 이긴 횟수 */
@Composable
private fun GameStats() {
    val context = LocalContext.current
    val best = WordChainStore.best(context)
    val wins = WordChainStore.wins(context)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // 최고 기록 = 한 판에서 가장 많이 주고받은 단어 수
        GameStat("한 판 최고 기록", best?.let { "단어 ${it}개" } ?: "-", Modifier.weight(1f))
        GameStat("넥스티를 이긴 횟수", "${wins}번", Modifier.weight(1f))
    }
}

@Composable
private fun GameStat(label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(label, color = RespecColors.TextSub, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, color = RespecColors.AccentText, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** 게임 카드: 끝말잇기·초성 게임·퀴즈를 제목으로 구분 */
@Composable
private fun GameCard(s: ContentSession, onResumeGame: (String) -> Unit) {
    when (s.contentOption) {
        "WORDCHAIN" -> WordChainCard(s, onResumeGame)
        CHOSUNG_OPTION -> ChosungCard(s)
        else -> QuizCard(s)
    }
}

@Composable
private fun WordChainCard(s: ContentSession, onResumeGame: (String) -> Unit) {
    val context = LocalContext.current
    // 승패는 기기에 저장한 기록에서 (돌아올 때마다 다시 읽음)
    val result = WordChainStore.get(context, s.sessionId).result
    // 이 판에서 주고받은 단어 수 (서버 기록이 있으면 그 값). 승패 아래에 표시. 최고 기록·이긴 횟수는 탭 위 GameStats에
    val score = s.steps.firstNotNullOfOrNull { it.score } ?: WordChainStore.score(WordChainStore.get(context, s.sessionId))
    ContentCard(
        icon = Icons.Filled.SportsEsports,
        title = "끝말잇기",
        session = s,
        trailing = { Pill(if (result == null) "진행 중" else "끝남") }
    ) {
        if (result != null) {
            val (head, _) = wordChainResultText(result)
            val won = result.winner == WordChainWinner.USER
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.EmojiEvents, null, tint = if (won) RespecColors.AccentText else RespecColors.TextSub, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(head, color = RespecColors.Text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Sub("주고받은 단어 ${score}개")
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = { onResumeGame(s.sessionId) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) { Text("주고받은 내용 보기", color = RespecColors.Text, fontWeight = FontWeight.Bold) }
        } else {
            Text("아직 승패가 나지 않았어요", color = RespecColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Sub("넥스티와 글자로 이어서 승부를 내 보세요.")
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onResumeGame(s.sessionId) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RespecColors.Button, contentColor = RespecColors.OnButton)
            ) { Text("이어 하기", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ChosungCard(s: ContentSession) {
    ContentCard(icon = Icons.Filled.SportsEsports, title = "초성 게임", session = s) {
        if (s.steps.isEmpty()) {
            Sub("초성 게임 기록이 없어요.")
            return@ContentCard
        }
        s.steps.forEachIndexed { i, st ->
            if (i > 0) Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(scriptField(st.script, "초성") ?: st.name, color = RespecColors.AccentText, fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                StatusIcon(st.status)
            }
            Spacer(Modifier.height(8.dp))
            if (st.words.isEmpty()) Sub("말한 단어 기록이 없어요.") else WordFlow(st.words)
        }
    }
}

@Composable
private fun QuizCard(s: ContentSession) {
    val done = s.steps.count { it.status == "DONE" }
    ContentCard(
        icon = Icons.Filled.SportsEsports,
        title = when (s.contentOption) { "PERSON" -> "인물 퀴즈"; "GENERAL" -> "상식 퀴즈"; else -> "퀴즈" },
        session = s,
        trailing = { if (s.steps.isNotEmpty()) Pill("$done / ${s.steps.size}") }
    ) {
        if (s.steps.isEmpty()) Sub("퀴즈를 시작하지 않았어요.")
        s.steps.forEachIndexed { i, st ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(st.name, color = RespecColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    scriptField(st.script, "문제")?.let { Sub(it) }
                }
                StatusIcon(st.status)
            }
        }
    }
}

// ---------------- 공통 ----------------

@Composable
private fun Empty(icon: ImageVector, title: String, sub: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(RespecColors.Card),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = RespecColors.TextSub, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, color = RespecColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        sub?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = RespecColors.TextSub, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Sub(text: String) {
    Text(text, color = RespecColors.TextSub, fontSize = 13.sp, lineHeight = 19.sp)
}

/** 완료 ✓ / 건너뜀 − / 미완 ○ */
@Composable
private fun StatusIcon(status: String) {
    val (icon, tint, desc) = when (status) {
        "DONE" -> Triple(Icons.Filled.CheckCircle, RespecColors.Mint, "완료")
        "SKIPPED" -> Triple(Icons.Filled.RemoveCircle, RespecColors.TextSub, "건너뜀")
        else -> Triple(Icons.Outlined.Circle, RespecColors.TextSub, "미완")
    }
    Spacer(Modifier.width(10.dp))
    Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp))
}

@Composable
private fun Pill(text: String) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(RespecColors.PurpleDark).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = RespecColors.AccentText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** 단어를 알약 모양으로 줄 바꿈하며 나열 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordFlow(words: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.forEach {
            Box(Modifier.clip(RoundedCornerShape(50)).background(RespecColors.Background).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(it, color = RespecColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
