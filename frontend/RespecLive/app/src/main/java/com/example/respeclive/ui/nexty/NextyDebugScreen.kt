package com.example.respeclive.ui.nexty

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.ui.morning.FortuneScene
import com.example.respeclive.ui.morning.FortuneShow
import com.example.respeclive.ui.onboarding.ChipRow
import com.example.respeclive.ui.onboarding.Choice
import com.example.respeclive.ui.theme.RespecColors

private val MORNING_BG = Color(0xFFFFF4E4)
private val NIGHT_BG = Color(0xFF1F2547)

private val FACE_LABELS = mapOf(
    NextyFace.NORMAL to "기본",
    NextyFace.HAPPY to "칭찬",
    NextyFace.GRIN to "활짝",
    NextyFace.WINCE to "찡그림",
    NextyFace.SLEEPY to "졸림",
    NextyFace.FOCUS to "집중"
)

/** 넥스티 움직임 확인용 (홈 화면 맨 아래 디버그 버튼). 동작 하나씩 보기, 포춘쿠키 깨지는 모션 */
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = RespecColors.AccentText)
            }
            Spacer(Modifier.width(14.dp))
            Text("넥스티 디버그", color = RespecColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        ChipRow(listOf(Choice("MOTION", "동작 보기"), Choice("FORTUNE", "포춘쿠키")), mode) { mode = it }
        Spacer(Modifier.height(12.dp))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (mode == "MOTION") MotionPanel() else FortuneDemo()
        }
    }
}

/** 포춘쿠키 깨지는 모션 확인용. 쿠키를 누르거나 [깨기]로 열고, [다시 보기]로 닫힌 쿠키부터 다시 */
@Composable
private fun FortuneDemo() {
    var style by remember { mutableStateOf("COOKIE") }
    var replay by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf(false) }
    val fortune = FortuneShow(
        text = if (style == "HOROSCOPE") "오늘은 작은 결심이 큰 흐름을 만드는 날이에요. 아침의 한 걸음을 믿어 보세요."
        else "어젯밤 걱정했던 일, 오늘은 생각보다 쉽게 풀릴 거예요. 시작만 하면 돼요!",
        style = style,
        basedOn = "어젯밤 한마디 · \"내일 발표가 걱정돼\""
    )

    Column(Modifier.fillMaxSize()) {
        key(replay) {
            FortuneScene(
                fortune = fortune,
                opened = opened,
                aiLine = "오늘의 포춘쿠키를 가져왔어요. 같이 열어 볼까요?",
                speaking = !opened,
                onOpen = { opened = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MORNING_BG)
                    .padding(16.dp)
            )
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("종류")
            ChipRow(listOf(Choice("COOKIE", "포춘쿠키"), Choice("HOROSCOPE", "오늘의 운세")), style) { style = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { opened = true }, enabled = !opened) { Text("깨기", fontSize = 14.sp) }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { opened = false; replay++ }) { Text("다시 보기", fontSize = 14.sp) }
            }
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
