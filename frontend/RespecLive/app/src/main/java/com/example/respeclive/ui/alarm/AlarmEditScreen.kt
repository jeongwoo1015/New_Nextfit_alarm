package com.example.respeclive.ui.alarm

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.alarm.AlarmScheduler
import com.example.respeclive.data.Alarm
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.MORNING_CONTENTS
import com.example.respeclive.ui.common.SubScreenHeaderTop
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.launch
import kotlin.math.abs

private val ITEM_H = 56.dp
private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")
private const val NAME_MAX = 20

/**
 * 알람 만들기·고치기 (alarmId == -1 이면 새 알람): 시간 → 요일 → 이름 → 모닝콜 / 굿나잇콜.
 * 모닝콜이면 아침 콘텐츠(운동·외국어·퀴즈)를 고르고, 굿나잇콜은 더 고를 것이 없음.
 * 모닝콜은 서버에, 굿나잇콜은 기기에만 저장. 종류를 바꾸면 저장할 때 AlarmRepository가 옮김.
 */
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
    var isBedtime by remember { mutableStateOf(existing?.type == AlarmType.BEDTIME) }

    // 아침 콘텐츠: 이 알람의 값 → 다른 모닝콜 알람의 값. 루틴 알람이거나 이제 고를 수 없는 값(명상)이면 선택 안 함
    val sample = existing ?: AlarmRepository.alarms.firstOrNull { it.type == AlarmType.WAKE }
    var contentType by remember {
        mutableStateOf(sample?.contentType?.takeIf { code -> MORNING_CONTENTS.any { it.first == code } })
    }

    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }     // 서버에 저장하는 중
    var deleting by remember { mutableStateOf(false) }   // 서버에서 지우는 중 (둘 중 하나라도 진행 중이면 버튼을 막음)
    val busy = saving || deleting
    // 저장할 수 없는 이유 (없으면 저장 가능). 버튼 위에 그대로 안내함
    val missing = when {
        label.isBlank() -> "알람 이름을 입력해 주세요"
        !isBedtime && contentType == null -> "아침 콘텐츠를 골라 주세요"
        else -> null
    }
    val canSave = !busy && missing == null

    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    // 처음 값과 비교해 바뀐 것이 있으면 저장하지 않고 나갈 때 확인함
    val initial = remember { listOf(ampm, hour12, minute, days, label, isBedtime, contentType) }
    val changed = listOf(ampm, hour12, minute, days, label, isBedtime, contentType) != initial
    fun leave() {
        if (changed && !busy) confirmLeave = true else onBack()
    }
    BackHandler { leave() }

    fun save() {
        val base = existing ?: Alarm(
            id = AlarmRepository.nextId(), label = "", hour = 0, minute = 0, days = emptySet(),
            type = if (isBedtime) AlarmType.BEDTIME else AlarmType.WAKE
        )
        val alarm = base.copy(
            type = if (isBedtime) AlarmType.BEDTIME else AlarmType.WAKE,
            label = label.trim(),
            hour = (hour12 % 12) + if (ampm == 1) 12 else 0,
            minute = minute,
            days = days,
            enabled = true,
            templateId = null,
            contentType = if (isBedtime) null else contentType,
            // 세부 주제는 고르지 않음. 같은 콘텐츠면 이전 값 유지
            contentOption = if (isBedtime) null else existing?.contentOption?.takeIf { existing.contentType == contentType }
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
            Modifier.padding(top = SubScreenHeaderTop, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(RespecColors.PurpleDark)
                    .clickable { leave() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = RespecColors.AccentText)
            }
            Spacer(Modifier.width(14.dp))
            Text("알람 설정", color = RespecColors.AccentText, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
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
                Text(":", color = RespecColors.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Wheel((0..59).map { "%02d".format(it) }, minute, modifier = Modifier.weight(1f)) { minute = it }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 요일: 고른 요일은 포인트 색으로 채우고, 안 고른 요일은 테두리만 (두 테마 모두 한눈에 구분되게)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DAY_NAMES.forEachIndexed { index, name ->
                val on = index in days
                Box(
                    Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(if (on) RespecColors.Lime else RespecColors.Disabled)
                        .border(1.dp, if (on) RespecColors.Lime else RespecColors.CardBorder, CircleShape)
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
                        color = if (on) RespecColors.OnAccent else RespecColors.TextSub,
                        fontSize = 15.sp,
                        fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium
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
                focusedTextColor = RespecColors.Text,
                unfocusedTextColor = RespecColors.Text,
                focusedContainerColor = RespecColors.Card,
                unfocusedContainerColor = RespecColors.Card,
                focusedBorderColor = RespecColors.Purple,
                unfocusedBorderColor = RespecColors.CardBorder,
                cursorColor = RespecColors.Lime
            )
        )

        Spacer(Modifier.height(28.dp))

        // 알람 종류
        SectionTitle(Icons.Filled.Alarm, "알람 종류")
        Spacer(Modifier.height(12.dp))
        AlarmKindToggle(isBedtime) { isBedtime = it }

        // 모닝콜이면 아침 콘텐츠. 굿나잇콜은 더 고를 것이 없음
        if (!isBedtime) {
            Spacer(Modifier.height(28.dp))
            SectionTitle(Icons.Filled.WbSunny, "아침 콘텐츠")
            Spacer(Modifier.height(12.dp))
            MorningContentPicker(contentType) { contentType = it }
        }

        Spacer(Modifier.height(28.dp))
        // 저장 버튼이 꺼져 있는 이유
        if (missing != null && !busy) {
            Text(
                missing,
                color = RespecColors.AccentText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )
        }
        Button(
            onClick = { save() },
            enabled = canSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = RespecColors.Button,
                contentColor = RespecColors.OnButton,
                disabledContainerColor = RespecColors.Disabled,
                disabledContentColor = RespecColors.TextSub
            )
        ) {
            Text(if (saving) "저장 중…" else "저장하기", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }

        if (existing != null) {
            TextButton(
                onClick = { confirmDelete = true },
                enabled = !busy,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(if (deleting) "삭제 중…" else "이 알람 삭제", color = RespecColors.Danger)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = RespecColors.Card,
            title = { Text("이 알람을 삭제할까요?", color = RespecColors.Text, fontWeight = FontWeight.Bold) },
            text = { Text("삭제한 알람은 되돌릴 수 없어요.", color = RespecColors.TextSub) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    scope.launch {
                        val error = AlarmRepository.delete(context, existing.id)
                        deleting = false
                        if (error == null) onBack() else Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    }
                }) { Text("삭제", color = RespecColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("취소", color = RespecColors.TextSub) }
            }
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = RespecColors.Card,
            title = { Text("저장하지 않고 나갈까요?", color = RespecColors.Text, fontWeight = FontWeight.Bold) },
            text = { Text("바꾼 내용은 저장되지 않아요.", color = RespecColors.TextSub) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onBack()
                }) { Text("나가기", color = RespecColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("계속 수정", color = RespecColors.TextSub) }
            }
        )
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = RespecColors.Purple, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = RespecColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                    color = if (selected) RespecColors.Text else RespecColors.TextSub,
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
