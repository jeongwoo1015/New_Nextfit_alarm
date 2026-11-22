package com.example.respeclive.ui.home

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.alarm.AlarmScheduler
import com.example.respeclive.data.Alarm
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.WakeHistory
import com.example.respeclive.data.WakeOverrides
import com.example.respeclive.ui.theme.AppTheme
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onStartNightChat: () -> Unit,
    onCreateAlarm: () -> Unit,
    onEditAlarm: (Int) -> Unit,
    onOpenDebug: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 켜진 알람 중 다음에 울릴 순서, 꺼진 알람은 아래로. 같은 시각이면 먼저 만든 순 (CREATED_AT)
    val alarms = AlarmRepository.alarms.sortedWith(
        compareBy<Alarm>({ !it.enabled }, { AlarmScheduler.nextTriggerMillis(it) ?: Long.MAX_VALUE }, { it.createdAt ?: "" })
    )

    // 홈을 열 때마다 서버 알람 목록으로 맞춤 (실패하면 기기에 저장된 목록 그대로)
    LaunchedEffect(Unit) {
        AlarmRepository.sync(context)
        // 밤 대화로 하루만 바꾼 기상 시각을 다시 확인 (굿나잇콜 직후엔 추출이 안 끝났을 수 있음)
        WakeOverrides.refresh(context)
        // 기록이 적을 때 알람 진동 세기를 정하는 이번 주 기상 성공률 (알람이 울릴 때 기다리지 않도록 미리 받아 둠)
        WakeHistory.refreshSuccessRate(context)
    }

    Column(Modifier.fillMaxSize()) {
        // 넥스티 장면은 화면 양옆 끝까지, 나머지는 좌우 여백 안에
        HomeScene(night = AppTheme.isDark)

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text("알람 목록", color = RespecColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${alarms.size}개", color = RespecColors.TextSub, fontSize = 14.sp)
            }

            // 목록을 내리면 대화 버튼을 아이콘만 남게 줄이고, 올리면 다시 글자를 펼침
            val scrollState = rememberScrollState()
            var fabExpanded by remember { mutableStateOf(true) }
            LaunchedEffect(scrollState) {
                var last = scrollState.value
                snapshotFlow { scrollState.value }.collect { value ->
                    if (value > last) fabExpanded = false
                    else if (value < last) fabExpanded = true
                    last = value
                }
            }

            Box(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    alarms.forEach { alarm ->
                        AlarmCard(
                            alarm = alarm,
                            onClick = { onEditAlarm(alarm.id) },
                            onToggle = { checked ->
                                scope.launch {
                                    val error = AlarmRepository.setEnabled(context, alarm.id, checked)
                                    val message = error ?: if (checked) AlarmScheduler.describeRemaining(alarm) else null
                                    message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                }
                            }
                        )
                    }

                    OutlinedButton(
                        onClick = onCreateAlarm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(2.dp, RespecColors.Purple)
                    ) {
                        Text("+ 나만의 알람 만들기", color = RespecColors.Purple, fontWeight = FontWeight.Bold)
                    }
                    // 디버그 (나만의 알람 만들기 바로 아래): 넥스티 디버그 화면
                    TextButton(onClick = onOpenDebug, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("디버그", color = RespecColors.TextSub, fontSize = 13.sp)
                    }
                    // 떠 있는 대화 버튼이 마지막 카드의 스위치를 가리지 않도록 버튼 높이만큼 비워 둠
                    Spacer(Modifier.height(72.dp))
                }

                // 누르면 넥스티 전화가 걸려 오는 화면으로.
                // 밝은 테마는 남색 버튼, 다크 테마는 남색 배경과 구분되도록 노란 주요 버튼 색 (남색 버튼은 배경에 묻혔음)
                val dark = AppTheme.isDark
                val fabContainer = if (dark) RespecColors.Button else RespecColors.Banner
                val fabContent = if (dark) RespecColors.OnButton else RespecColors.OnBanner
                ExtendedFloatingActionButton(
                    text = { Text("굿나잇콜", fontWeight = FontWeight.Bold) },
                    icon = {
                        Icon(Icons.Filled.Bedtime, contentDescription = null, tint = if (dark) fabContent else RespecColors.Sun)
                    },
                    onClick = onStartNightChat,
                    expanded = fabExpanded,
                    containerColor = fabContainer,
                    contentColor = fabContent,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp)
                )
            }
        }
    }
}

/** 알람 카드: 종류 · 이름 · 켜기 스위치와 시각. 요일·아침 콘텐츠는 카드를 눌러 알람 설정 화면에서 */
@Composable
private fun AlarmCard(
    alarm: Alarm,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val dim = if (alarm.enabled) 1f else 0.5f
    val night = alarm.type == AlarmType.BEDTIME

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 알람 종류 옆에 알람 이름
                TypeBadge(night = night)
                Spacer(Modifier.width(8.dp))
                Text(
                    alarm.label,
                    color = RespecColors.TextSub, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = RespecColors.OnAccent,
                        checkedTrackColor = RespecColors.Lime,
                        checkedBorderColor = RespecColors.Lime,
                        uncheckedThumbColor = RespecColors.TextSub,
                        uncheckedTrackColor = RespecColors.Disabled,
                        uncheckedBorderColor = RespecColors.Disabled
                    )
                )
            }
            // 밤 대화로 하루만 바꾼 시각이 다음 차례면 그 시각을 보여 줌. 그날 울리고 나면 원래 시각으로 돌아옴
            val override = WakeOverrides.upcoming(alarm)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    override?.let { "%02d:%02d".format(it.hour, it.minute) } ?: alarm.timeText(),
                    color = RespecColors.Text,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.alpha(dim)
                )
                if (override != null) {
                    Spacer(Modifier.width(10.dp))
                    Column {
                        val today = WakeOverrides.dateKey(java.util.Date())
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(RespecColors.PurpleDark)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (override.date == today) "오늘만" else "내일만",
                                color = RespecColors.AccentText, fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Text("원래 ${alarm.timeText()}", color = RespecColors.TextSub, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** 알람 종류 배지: 모닝콜 / 굿나잇콜. 굿나잇콜은 카드 배경과 구분되도록 진한 채움색 */
@Composable
private fun TypeBadge(night: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (night) RespecColors.Purple else RespecColors.Lime.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            if (night) "굿나잇콜" else "모닝콜",
            color = if (night) RespecColors.Background else RespecColors.AccentText,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}
