package com.example.respeclive.ui.routine

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.Routine
import com.example.respeclive.data.RoutineRepository
import com.example.respeclive.data.durationText
import com.example.respeclive.ui.common.alarmSummary
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** 알람마다 이름 아래에 그 알람에서 진행하는 체크리스트 (홈 알람 목록과 같은 순서) */
@Composable
fun RoutineScreen() {
    val context = LocalContext.current
    val boards = RoutineRepository.boards
    val scope = rememberCoroutineScope()

    // 알람마다: 오늘 그 알람의 세션에 단계가 있으면 단계, 없으면 알람의 루틴 미리보기
    LaunchedEffect(Unit) { RoutineRepository.loadBoards() }

    // 세션 단계만 체크할 수 있음 (미리보기는 저장할 세션이 아직 없음)
    fun toggle(board: RoutineRepository.AlarmChecklist, item: Routine) {
        scope.launch {
            RoutineRepository.mark(board, item)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Text(
            "오늘의 체크리스트",
            color = RespecColors.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            "모닝콜에서 루틴을 시작하면 체크할 수 있어요",
            color = RespecColors.TextSub,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (boards.isEmpty()) {
                val hasWake = AlarmRepository.alarms.any { it.type == AlarmType.WAKE }
                Text(
                    if (hasWake) "오늘 울리는 알람이 없어요" else "기상 알람을 만들면 루틴이 여기에 보여요",
                    color = RespecColors.TextSub,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            boards.forEach { board -> AlarmSection(board) { toggle(board, it) } }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 알람 하나 = 카드 하나. 이름 · 내용 · 진행, 그 아래 체크리스트 */
@Composable
private fun AlarmSection(board: RoutineRepository.AlarmChecklist, onToggle: (Routine) -> Unit) {
    val alarm = board.alarm
    val items = board.items
    val done = items.count { it.done }
    val skipped = items.count { it.skipped }
    val dim = if (alarm.enabled) 1f else 0.5f
    val accent = if (alarm.isContent) RespecColors.Mint else RespecColors.AccentText   // 루틴과 콘텐츠를 색으로 구분
    val shape = RoundedCornerShape(28.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.alpha(dim).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            // 알람 유형 색 막대
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(alarm.label, color = RespecColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val summary = alarmSummary(alarm)
                if (summary.isNotEmpty()) {
                    Text(summary, color = accent, fontSize = 13.sp)
                }
            }
            when {
                board.hasSteps -> Text(
                    "$done / ${items.size} 완료" + if (skipped > 0) " · $skipped 건너뜀" else "",
                    color = RespecColors.TextSub,
                    fontSize = 13.sp
                )
                !alarm.enabled -> Text("꺼짐", color = RespecColors.TextSub, fontSize = 13.sp)
            }
        }
        if (board.hasSteps) {
            LinearProgressIndicator(
                progress = { if (items.isEmpty()) 0f else done / items.size.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = accent,
                trackColor = RespecColors.Disabled,
                drawStopIndicator = {}
            )
        }
        // 밤 대화에서 '내일 코칭 안 하기'에 동의한 날: 브리핑만, 단계 없음
        if (board.session?.coachingSkipped == true) {
            Notice("어젯밤 대화에서 오늘은 루틴을 쉬기로 했어요. 아침 브리핑만 진행해요.")
        }
        items.forEach { item ->
            RoutineRow(item, checkable = item.stepOrder != null) { onToggle(item) }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        color = RespecColors.Mint,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RespecColors.MintDark)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/** 한 줄: 이름 / 시간 · 건너뜀 · 체크 시각 / 체크 표시. 알람 카드 안이라 더 어두운 배경 */
@Composable
private fun RoutineRow(item: Routine, checkable: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Background)
            .clickable(enabled = checkable, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, color = RespecColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val sub = listOfNotNull(
                item.durationSec?.takeIf { it > 0 }?.let { durationText(it) },
                "건너뜀".takeIf { item.skipped },
                item.checkedAt?.takeIf { item.done }?.let { clockText(it) }?.let { "$it 완료" }
            )
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(sub.joinToString(" · "), color = RespecColors.TextSub, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            item.done -> Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(RespecColors.Mint),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Check, null, tint = RespecColors.OnAccent, modifier = Modifier.size(18.dp))
            }
            item.skipped -> Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(RespecColors.Disabled),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Remove, null, tint = RespecColors.TextSub, modifier = Modifier.size(18.dp))
            }
            checkable -> Box(
                Modifier
                    .size(28.dp)
                    .border(BorderStroke(2.dp, RespecColors.TextSub), CircleShape)
            )
        }
    }
}

/** ISO 시각(CHECKED_AT) → 기기 시간대 "HH:mm". 읽지 못하면 null */
private fun clockText(iso: String): String? = runCatching {
    val plain = iso.replace(Regex("""\.\d+"""), "")
    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(plain) ?: return null
    SimpleDateFormat("HH:mm", Locale.KOREAN).format(date)
}.getOrNull()
