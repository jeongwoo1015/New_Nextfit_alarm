package com.example.respeclive.ui.content

import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.data.ApiClient
import com.example.respeclive.data.RespecApi
import com.example.respeclive.data.WordChainDictionary
import com.example.respeclive.data.WordChainLine
import com.example.respeclive.data.WordChainMorning
import com.example.respeclive.data.WordChainReason
import com.example.respeclive.data.WordChainResult
import com.example.respeclive.data.WordChainRules
import com.example.respeclive.data.WordChainStore
import com.example.respeclive.data.WordChainWinner
import com.example.respeclive.ui.common.SubScreenHeaderTop
import com.example.respeclive.ui.nexty.NextyCharacter
import com.example.respeclive.ui.nexty.NextyMotion
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 이어 하기에서 내 차례 제한 시간 */
private const val TURN_SECONDS = 20
/** 넥스티가 단어를 고르는 척 잠깐 기다림 */
private const val NEXTY_THINK_MS = 700L
/** 끝말잇기는 한 단계짜리 게임 (AI content_steps.wordchain_steps) */
private const val WORDCHAIN_STEP = 1

/**
 * 끝말잇기 기록·이어 하기 (글자로 입력). 위에는 모닝콜에서 주고받은 말, 아래는 이어 한 단어.
 * 승패(시간 초과 또는 더 이을 단어가 없음)가 났으면 기록만 보여 주고, 아니면 넥스티(앱의 단어 목록)와 이어 함.
 * 기록은 기기에만 저장. 이어 하기에서 승패가 나면 서버 단계를 '완료'로 맞춤
 */
@Composable
fun WordChainScreen(sessionId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dict = remember { WordChainDictionary.load(context) }
    val record = remember { WordChainStore.get(context, sessionId) }
    val morning = record.morning
    val game = remember { mutableStateListOf<WordChainLine>().apply { addAll(record.game) } }
    var result by remember { mutableStateOf(record.result) }
    val reviewOnly = record.result != null

    // 이어 갈 자리: 이어 한 단어가 있으면 그 마지막, 없으면 모닝콜에서 마지막으로 이어진 단어(또는 "'○'로 시작" 안내).
    // 마지막 단어가 내 말이면 넥스티 차례부터
    val point = remember { WordChainMorning.resumePoint(morning) }
    val morningWord = point?.word
    val morningWordMine = point?.mine == true
    val morningStart = remember { if (point == null) WordChainMorning.requiredStart(morning) else null }
    val previous = game.lastOrNull()?.text ?: morningWord
    val requiredStart = if (game.isEmpty()) morningStart else null
    val used = game.map { it.text } + listOfNotNull(morningWord)
    val myTurn = result == null && (
        game.lastOrNull()?.mine == false ||
            (game.isEmpty() && ((morningWord != null && !morningWordMine) || (morningWord == null && morningStart != null)))
        )

    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var secondsLeft by remember { mutableIntStateOf(TURN_SECONDS) }
    var checking by remember { mutableStateOf(false) }   // AI가 내 단어를 판단하는 중 (타이머 멈춤)

    fun finish(r: WordChainResult) {
        result = r
        WordChainStore.saveGame(context, sessionId, game.toList(), r)
        // 게임 기록(주고받은 단어 수)을 서버에 저장하고, 최고 기록이면 알려 줌
        val score = WordChainStore.score(WordChainStore.get(context, sessionId))
        val app = context.applicationContext
        ApiClient.scope.launch {
            RespecApi.markStep(sessionId, WORDCHAIN_STEP, "DONE")
            RespecApi.saveGameScore(sessionId, WORDCHAIN_STEP, score)?.let { out ->
                WordChainStore.saveBest(app, out.optInt("best_score", score))
                if (out.optBoolean("is_best") && score > 0) {
                    withContext(Dispatchers.Main) { Toast.makeText(app, "새 기록! ${score}번 주고받았어요", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    // 넥스티 차례: 이을 단어가 없으면 내 승리
    // [이어 하기 시작]을 누른 뒤에만 진행 (기록을 읽는 동안 시간이 흘러 지지 않도록)
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(game.size, result, started) {
        if (!started || result != null || myTurn) return@LaunchedEffect
        delay(NEXTY_THINK_MS)
        val word = dict.pick(previous, requiredStart, used)
        if (word == null) {
            finish(WordChainResult(WordChainWinner.USER, WordChainReason.NO_WORD, inMorning = false))
        } else {
            game.add(WordChainLine(mine = false, text = word))
            WordChainStore.saveGame(context, sessionId, game.toList(), null)
        }
    }
    // 내 차례 제한 시간: 넘기면 넥스티 승리
    LaunchedEffect(game.size, result, myTurn, started) {
        if (!started || !myTurn) return@LaunchedEffect
        secondsLeft = TURN_SECONDS
        while (secondsLeft > 0) {
            delay(1000)
            if (!checking) secondsLeft -= 1      // AI 판단을 기다리는 동안은 시간이 흐르지 않음
        }
        finish(WordChainResult(WordChainWinner.NEXTY, WordChainReason.TIMEOUT, inMorning = false))
    }

    // 내 단어: 앱 규칙(끝 글자·두음법칙·중복·글자 수)을 먼저 보고, 통과하면 AI에게 실제 단어인지 묻고 넥스티 단어를 받음.
    // AI가 답하지 못하면(서버 API 준비 전·연결 실패) 앱 규칙만으로 받아들이고 넥스티는 앱 단어 목록에서 고름
    fun submit() {
        if (!started || !myTurn || checking) return
        val word = WordChainRules.clean(input)
        val problem = WordChainRules.problem(word, previous, requiredStart, used)
        if (problem != null) {
            error = problem
            return
        }
        error = null
        checking = true
        scope.launch {
            val chain = WordChainMorning.chainWords(morning).map { it.text } + game.map { it.text }
            val turn = RespecApi.wordChainTurn(chain, word)
            checking = false
            if (result != null) return@launch          // 기다리는 사이 화면을 벗어났거나 끝남
            if (turn != null && !turn.valid) {
                error = turn.reason ?: "끝말잇기에 쓸 수 없는 단어예요"
                return@launch
            }
            input = ""
            game.add(WordChainLine(mine = true, text = word))
            if (turn != null) {
                // AI가 준 넥스티 단어도 규칙을 한 번 더 확인하고, 틀리면 앱 단어 목록에서
                val next = turn.next?.let { WordChainRules.clean(it) }
                    ?.takeIf { WordChainRules.problem(it, word, null, used + word) == null }
                    ?: dict.pick(word, null, used + word)
                if (turn.next == null || next == null) {
                    // AI가 이을 단어가 없다고 함 → 내 승리
                    finish(WordChainResult(WordChainWinner.USER, WordChainReason.NO_WORD, inMorning = false))
                    return@launch
                }
                game.add(WordChainLine(mine = false, text = next))
            }
            WordChainStore.saveGame(context, sessionId, game.toList(), null)
        }
    }

    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(RespecColors.Background).imePadding().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = SubScreenHeaderTop, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(RespecColors.PurpleDark).clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = RespecColors.AccentText) }
            Spacer(Modifier.width(14.dp))
            Text(if (reviewOnly) "끝말잇기 기록" else "끝말잇기 이어 하기", color = RespecColors.AccentText,
                fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }

        val listState = rememberLazyListState()
        // 모닝콜에서 주고받은 말과 이어 한 단어를 구분 없이 한 대화로
        val conversation = WordChainMorning.chainWords(morning) + game     // 끝말잇기 단어만
        val total = conversation.size + 1
        LaunchedEffect(game.size, result) { listState.animateScrollToItem(total - 1) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(conversation) { Bubble(it) }
            item { result?.let { ResultBanner(it) } }
        }

        if (result == null && !started) {
            Text(
                if (conversation.isEmpty()) "넥스티와 새로 끝말잇기를 시작해요" else "'${conversation.last().text}'에서 이어 해요",
                color = RespecColors.TextSub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { started = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RespecColors.Button, contentColor = RespecColors.OnButton)
            ) { Text("이어 하기 시작", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(23.dp)
            ) { Text("나중에 하기", color = RespecColors.TextSub, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(16.dp))
        } else if (result == null) {
            val starts = previous?.let { WordChainRules.nextStarts(it) } ?: requiredStart?.let { setOfNotNull(it, WordChainRules.dueum(it)) }
            Text(
                when {
                    !myTurn || checking -> "넥스티가 생각하는 중…"
                    starts != null -> "'${starts.joinToString("' 또는 '")}'(으)로 시작하는 단어 · ${secondsLeft}초"
                    else -> "단어를 말해 주세요 · ${secondsLeft}초"
                },
                color = if (myTurn && secondsLeft <= 5) RespecColors.Danger else RespecColors.TextSub,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            error?.let { Text(it, color = RespecColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)) }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 12) input = it; error = null },
                    modifier = Modifier.weight(1f),
                    enabled = myTurn && !checking,
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    placeholder = { Text("단어 입력", color = RespecColors.TextSub) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = RespecColors.Text,
                        unfocusedTextColor = RespecColors.Text,
                        disabledTextColor = RespecColors.TextSub,
                        focusedContainerColor = RespecColors.Card,
                        unfocusedContainerColor = RespecColors.Card,
                        disabledContainerColor = RespecColors.Card,
                        focusedBorderColor = RespecColors.Lime,
                        unfocusedBorderColor = RespecColors.CardBorder,
                        disabledBorderColor = RespecColors.CardBorder,
                        cursorColor = RespecColors.Lime
                    )
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { submit() },
                    enabled = myTurn && !checking,
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(RespecColors.Button)
                ) { Icon(Icons.AutoMirrored.Filled.Send, "보내기", tint = RespecColors.OnButton) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { finish(WordChainResult(WordChainWinner.NEXTY, WordChainReason.NO_WORD, inMorning = false)) } },
                    enabled = myTurn && !checking,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(23.dp)
                ) { Text("더 모르겠어요", color = RespecColors.Purple, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(23.dp)
                ) { Text("나중에 이어 하기", color = RespecColors.TextSub, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RespecColors.Button, contentColor = RespecColors.OnButton)
            ) { Text("닫기", fontWeight = FontWeight.Bold) }
        }
    }
}

/** 승패 한 줄: "내가 이겼어요 · 넥스티가 이을 단어가 없었어요" */
fun wordChainResultText(r: WordChainResult): Pair<String, String> {
    val head = if (r.winner == WordChainWinner.USER) "내가 이겼어요!" else "넥스티가 이겼어요"
    val why = when {
        r.reason == WordChainReason.TIMEOUT && r.winner == WordChainWinner.USER -> "넥스티의 시간이 다 됐어요"
        r.reason == WordChainReason.TIMEOUT -> "내 차례에 시간이 다 됐어요"
        r.winner == WordChainWinner.USER -> "넥스티가 이을 단어가 없었어요"
        else -> "내가 이을 단어가 없었어요"
    }
    return head to (if (r.inMorning) "모닝콜에서 · $why" else why)
}

@Composable
private fun ResultBanner(r: WordChainResult) {
    val (head, why) = wordChainResultText(r)
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).background(RespecColors.PurpleDark).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(head, color = RespecColors.AccentText, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text(why, color = RespecColors.Text, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/** 넥스티 말은 왼쪽(프로필 사진·이름과 함께), 내 말은 오른쪽 (통화 자막과 같은 색) */
@Composable
private fun Bubble(line: WordChainLine) {
    val shape = RoundedCornerShape(18.dp)
    val bubble = Modifier
        .widthIn(max = 260.dp)
        .clip(shape)
        .background(if (line.mine) RespecColors.Lime.copy(alpha = 0.12f) else RespecColors.Card)
        .border(1.dp, if (line.mine) RespecColors.Lime else RespecColors.CardBorder, shape)
        .padding(horizontal = 14.dp, vertical = 10.dp)
    if (line.mine) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(line.text, color = RespecColors.Text, fontSize = 16.sp, modifier = bubble)
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            NextyAvatar()
            Spacer(Modifier.width(8.dp))
            Column {
                Text("넥스티", color = RespecColors.TextSub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
                Text(line.text, color = RespecColors.Text, fontSize = 16.sp, modifier = bubble)
            }
        }
    }
}

/** 넥스티 프로필 사진: 원 안에 넥스티 상반신 */
@Composable
private fun NextyAvatar() {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(RespecColors.PurpleDark),
        contentAlignment = Alignment.TopCenter
    ) {
        NextyCharacter(NextyMotion.IDLE, Modifier.requiredSize(64.dp).offset(y = 4.dp))
    }
}
