package com.example.respeclive.ui.nexty

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.ui.onboarding.ChipRow
import com.example.respeclive.ui.onboarding.Choice
import com.example.respeclive.ui.theme.RespecColors

private val MORNING_BG = Color(0xFFFFF4E4)
private val NIGHT_BG = Color(0xFF1F2547)
private val INK = Color(0xFF3A2E2A)
private val INK_SUB = Color(0xFF8A7464)
private val ACCENT = Color(0xFFF5A95B)
private val ACCENT_TRACK = Color(0xFFF3E2CC)
private val BANNER_CHECK = Color(0xFFFFD45C)

private val FACE_LABELS = mapOf(
    NextyFace.NORMAL to "기본",
    NextyFace.HAPPY to "칭찬",
    NextyFace.GRIN to "활짝",
    NextyFace.WINCE to "찡그림",
    NextyFace.SLEEPY to "졸림",
    NextyFace.FOCUS to "집중"
)

/** 넥스티 움직임 확인용 (홈 화면 맨 아래 디버그 버튼). 동작 하나씩 보기, 또는 아침 루틴 화면 시안 */
@Composable
fun NextyDebugScreen(onBack: () -> Unit) {
    var mode by remember { mutableStateOf("MOTION") }

    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(RespecColors.PurpleDark).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = RespecColors.Purple)
            }
            Spacer(Modifier.width(14.dp))
            Text("넥스티 디버그", color = RespecColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        ChipRow(listOf(Choice("MOTION", "동작 보기"), Choice("ROUTINE", "루틴 화면")), mode) { mode = it }
        Spacer(Modifier.height(12.dp))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (mode == "MOTION") MotionPanel() else RoutineDemo()
        }
    }
}

@Composable
private fun MotionPanel() {
    var motion by remember { mutableStateOf(NextyMotion.IDLE) }
    var face by remember { mutableStateOf(NextyMotion.IDLE.face) }
    var night by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(if (night) NIGHT_BG else MORNING_BG)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            NextyCharacter(motion, Modifier.fillMaxSize(), face = face, night = night, speaking = speaking)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Label("동작")
            ChipRow(NextyMotion.entries.map { Choice(it.name, it.label) }, motion.name) {
                motion = NextyMotion.valueOf(it)
                face = motion.face
            }
            Label("표정")
            ChipRow(NextyFace.entries.map { Choice(it.name, FACE_LABELS.getValue(it)) }, face.name) { face = NextyFace.valueOf(it) }
            Label("시간대")
            ChipRow(listOf(Choice("DAY", "아침"), Choice("NIGHT", "밤")), if (night) "NIGHT" else "DAY") { night = it == "NIGHT" }
            Label("말하기")
            ChipRow(listOf(Choice("OFF", "끔"), Choice("ON", "켬")), if (speaking) "ON" else "OFF") { speaking = it == "ON" }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = RespecColors.TextSub, fontSize = 13.sp, fontWeight = FontWeight.Bold)
}

// ---------------- 아침 루틴 화면 시안 ----------------

private class DemoStep(val title: String, val time: String, val motion: NextyMotion, val ask: String, val doneLine: String)

private val DEMO_STEPS = listOf(
    DemoStep("물 한 컵 마시기", "1분", NextyMotion.WATER, "좋은 아침이에요! 먼저 물 한 컵 마시면서 몸을 깨워 볼까요?", "좋아요, 물 한 컵 끝났어요!"),
    DemoStep("가벼운 스트레칭", "5분", NextyMotion.STRETCH, "두 팔을 위로 쭉 뻗어 볼까요? 동작을 따라 해 보세요.", "좋아요, 스트레칭까지 끝났어요!"),
    DemoStep("오늘 할 일 한 가지 정하기", "1분", NextyMotion.TODO, "오늘 꼭 끝낼 일 하나만 정해 볼까요?", "오늘 할 일도 정했어요! 멋진 하루 보내요.")
)

/** 시안 화면: 실제 세션과 연결하지 않고, [다음]을 누르면 단계와 완료 상태가 차례로 바뀜 */
@Composable
private fun RoutineDemo() {
    var doneCount by remember { mutableIntStateOf(0) }
    var justDone by remember { mutableStateOf(false) }   // 방금 끝낸 단계의 칭찬을 보여 주는 중

    val current = DEMO_STEPS.getOrNull(doneCount)
    val lastDone = DEMO_STEPS.getOrNull(doneCount - 1)
    val cheering = justDone && lastDone != null
    val message = when {
        cheering && current != null -> "${lastDone!!.doneLine} 다음으로 ${current.title}, 시작해 볼까요?"
        cheering -> lastDone!!.doneLine
        else -> current?.ask.orEmpty()
    }

    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(MORNING_BG)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("아침 루틴", color = INK, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text("$doneCount / ${DEMO_STEPS.size} 완료", color = ACCENT, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(ACCENT_TRACK)) {
            Box(
                Modifier
                    .fillMaxWidth(doneCount / DEMO_STEPS.size.toFloat())
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(ACCENT)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            color = INK,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )

        NextyCharacter(
            motion = if (cheering || current == null) NextyMotion.CHEER else current.motion,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        if (cheering) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(INK)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(BANNER_CHECK), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = INK, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("${lastDone!!.title} 완료!", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }

        DEMO_STEPS.forEachIndexed { i, step ->
            DemoRow(step, done = i < doneCount, now = i == doneCount)
            Spacer(Modifier.height(6.dp))
        }

        TextButton(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            onClick = {
                when {
                    current == null -> {
                        doneCount = 0
                        justDone = false
                    }
                    justDone -> justDone = false
                    else -> {
                        doneCount += 1
                        justDone = true
                    }
                }
            }
        ) {
            Text(
                when {
                    current == null -> "처음부터"
                    justDone -> "다음 단계 시작"
                    else -> "이 단계 완료"
                },
                color = INK_SUB,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun DemoRow(step: DemoStep, done: Boolean, now: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .then(if (now) Modifier.border(1.5.dp, ACCENT, shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (done) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(ACCENT), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        } else {
            Box(Modifier.size(22.dp).border(1.5.dp, if (now) ACCENT else ACCENT_TRACK, CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            step.title,
            color = if (done) INK_SUB else INK,
            fontSize = 14.sp,
            fontWeight = if (now) FontWeight.Bold else FontWeight.Medium,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (now) "지금" else step.time,
            color = if (now) ACCENT else INK_SUB,
            fontSize = 12.sp,
            fontWeight = if (now) FontWeight.Bold else FontWeight.Normal
        )
    }
}
