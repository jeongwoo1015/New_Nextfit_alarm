package com.example.respeclive.ui.alarm

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.alarm.AlarmScheduler
import com.example.respeclive.data.Alarm
import com.example.respeclive.data.AlarmMode
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.THEMES
import com.example.respeclive.data.themeLabel
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.launch
import kotlin.math.abs

private val ITEM_H = 56.dp
private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")
private const val NAME_MAX = 20

/** alarmId == -1 이면 새 알람 */
@Composable
fun AlarmEditScreen(alarmId: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val existing = remember(alarmId) { AlarmRepository.get(alarmId) }

    var ampm by remember { mutableIntStateOf(if (existing != null && existing.hour >= 12) 1 else 0) }
    var hour12 by remember {
        mutableIntStateOf(existing?.let { (it.hour % 12).let { h -> if (h == 0) 12 else h } } ?: 7)
    }
    var minute by remember { mutableIntStateOf(existing?.minute ?: 0) }
    var days by remember { mutableStateOf(existing?.days ?: setOf(0, 1, 2, 3, 4)) }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var mode by remember { mutableStateOf(existing?.mode ?: AlarmMode.CONTINUOUS) }
    // 새 알람은 기존 모닝콜 알람의 테마를 따름
    var theme by remember {
        mutableStateOf(existing?.contentType ?: if (existing == null) AlarmRepository.alarms.firstOrNull { it.type == AlarmType.WAKE }?.contentType else null)
    }
    val isBedtime = existing?.type == AlarmType.BEDTIME
    var showThemeDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }   // 서버 저장·삭제 중에는 버튼을 막음

    fun save() {
        val base = existing ?: Alarm(id = AlarmRepository.nextId(), label = "", hour = 0, minute = 0, days = emptySet())
        val alarm = base.copy(
            label = label.trim(),
            hour = (hour12 % 12) + if (ampm == 1) 12 else 0,
            minute = minute,
            days = days,
            enabled = true,
            mode = mode,
            contentType = theme,
            // 콘텐츠 세부 옵션은 같은 테마일 때만 유지 (다른 종류의 옵션은 서버가 거절)
            contentOption = base.contentOption.takeIf { theme != null && theme == base.contentType }
        )
        saving = true
        scope.launch {
            val error = AlarmRepository.save(context, alarm)
            saving = false
            Toast.makeText(context, error ?: AlarmScheduler.describeRemaining(alarm), Toast.LENGTH_SHORT).show()
            if (error == null) onBack()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp)
    ) {
        // 헤더
        Row(
            Modifier.padding(top = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(RespecColors.PurpleDark)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = RespecColors.Purple)
            }
            Spacer(Modifier.width(14.dp))
            Text("알람 설정", color = RespecColors.Lime, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        }

        // 시간 휠
        val cardShape = RoundedCornerShape(28.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(RespecColors.Card)
                .border(1.dp, RespecColors.CardBorder, cardShape)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(ITEM_H)
                    .clip(RoundedCornerShape(20.dp))
                    .background(RespecColors.PurpleDark.copy(alpha = 0.6f))
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Wheel(listOf("오전", "오후"), ampm, small = true, modifier = Modifier.weight(1f)) { ampm = it }
                Wheel((1..12).map { "$it" }, hour12 - 1, modifier = Modifier.weight(1f)) { hour12 = it + 1 }
                Text(":", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Wheel((0..59).map { "%02d".format(it) }, minute, modifier = Modifier.weight(1f)) { minute = it }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 요일
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DAY_NAMES.forEachIndexed { index, name ->
                val on = index in days
                Box(
                    Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(if (on) RespecColors.PurpleDark else RespecColors.Disabled)
                        .clickable {
                            if (on && days.size == 1) {
                                Toast.makeText(context, "요일을 하나 이상 선택해 주세요", Toast.LENGTH_SHORT).show()
                            } else {
                                days = if (on) days - index else days + index
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        name,
                        color = if (on) Color.White else RespecColors.TextSub,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // 알람 이름
        SectionTitle(Icons.Filled.LocalOffer, "알람 이름")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { if (it.length <= NAME_MAX) label = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            placeholder = { Text("알람 이름을 지어주세요", color = RespecColors.TextSub) },
            trailingIcon = {
                Text(
                    "${label.length} / $NAME_MAX",
                    color = RespecColors.Purple,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 16.dp)
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = RespecColors.Card,
                unfocusedContainerColor = RespecColors.Card,
                focusedBorderColor = RespecColors.Purple,
                unfocusedBorderColor = RespecColors.CardBorder,
                cursorColor = RespecColors.Lime
            )
        )

        Spacer(Modifier.height(28.dp))

        // 알람 방식
        SectionTitle(Icons.Filled.Schedule, "알람 방식")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AlarmMode.entries.forEach { m ->
                val selected = m == mode
                Box(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(if (selected) RespecColors.PurpleDark else RespecColors.Card)
                        .clickable { mode = m },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        m.label,
                        color = if (selected) Color.White else RespecColors.TextSub,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // 테마 (취침 알람에는 없음)
        if (!isBedtime) {
            Text("테마", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text("하루를 시작할 테마를 선택해 보세요", color = RespecColors.TextSub, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            val themeShape = RoundedCornerShape(24.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(themeShape)
                    .background(RespecColors.Card)
                    .border(1.dp, RespecColors.CardBorder, themeShape)
                    .clickable { showThemeDialog = true }
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(RespecColors.PurpleDark),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Add, null, tint = RespecColors.Purple)
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    themeLabel(theme)?.let { "테마: $it · 탭해서 변경" } ?: "테마 추가하기 (지금은 루틴 알람)",
                    color = RespecColors.Purple,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(24.dp))
        }

        Button(
            onClick = { save() },
            enabled = label.isNotBlank() && !saving,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(30.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RespecColors.Lime,
                contentColor = Color.Black,
                disabledContainerColor = RespecColors.Banner,
                disabledContentColor = RespecColors.TextSub
            )
        ) {
            Text(if (saving) "저장 중…" else "저장하기", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }

        if (existing != null) {
            TextButton(
                onClick = {
                    saving = true
                    scope.launch {
                        val error = AlarmRepository.delete(context, existing.id)
                        saving = false
                        if (error == null) onBack() else Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !saving,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("이 알람 삭제", color = RespecColors.Danger)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            containerColor = RespecColors.Card,
            title = { Text("테마 선택", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    THEMES.forEach { (code, name) ->
                        Text(
                            name,
                            color = if (code == theme) RespecColors.Lime else Color.White,
                            fontWeight = if (code == theme) FontWeight.ExtraBold else FontWeight.Normal,
                            fontSize = 17.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    theme = code
                                    showThemeDialog = false
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                    if (theme != null) {
                        Text(
                            "테마 없음 (루틴 알람)",
                            color = RespecColors.TextSub,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    theme = null
                                    showThemeDialog = false
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("닫기", color = RespecColors.Purple)
                }
            }
        )
    }
}

@Composable
private fun SectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = RespecColors.Purple, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** 스크롤하면 가운데 칸에 멈추는 휠 선택기 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Wheel(
    items: List<String>,
    initialIndex: Int,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    onSelected: (Int) -> Unit
) {
    val state = rememberLazyListState()
    val centerIndex by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2 - center) }
                ?.index ?: initialIndex
        }
    }

    LaunchedEffect(Unit) {
        state.scrollToItem(initialIndex)
        snapshotFlow { centerIndex }.collect { onSelected(it) }
    }

    LazyColumn(
        state = state,
        modifier = modifier.height(ITEM_H * 3),
        contentPadding = PaddingValues(vertical = ITEM_H),
        flingBehavior = rememberSnapFlingBehavior(state),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(items) { i, text ->
            val selected = i == centerIndex
            Box(Modifier.height(ITEM_H).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text,
                    color = if (selected) Color.White else RespecColors.TextSub,
                    fontSize = when {
                        small -> if (selected) 22.sp else 18.sp
                        else -> if (selected) 44.sp else 30.sp
                    },
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}