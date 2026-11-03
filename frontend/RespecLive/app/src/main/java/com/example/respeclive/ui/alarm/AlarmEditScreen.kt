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
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Tune
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
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.RoutineCatalog
import com.example.respeclive.data.THEMES
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
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
    val isBedtime = existing?.type == AlarmType.BEDTIME

    // 알람 유형·프리셋·콘텐츠. 새 알람은 기존 모닝콜 알람의 값을 따름
    val sample = existing ?: AlarmRepository.alarms.firstOrNull { it.type == AlarmType.WAKE }
    var isContent by remember { mutableStateOf(sample?.isContent ?: false) }
    var templateId by remember {
        mutableStateOf(sample?.templateId ?: AlarmRepository.alarms.firstOrNull { it.templateId != null }?.templateId)
    }
    var contentType by remember { mutableStateOf(sample?.contentType ?: THEMES.first().first) }
    var contentOption by remember { mutableStateOf(existing?.contentOption) }

    // 항목 시간: 이 알람에 저장된 값(같은 프리셋일 때) 또는 프리셋 기본 시간. 바꾼 값만 durations에
    var routineItems by remember { mutableStateOf<List<RoutineCatalog.Item>>(emptyList()) }
    val durations = remember { mutableStateMapOf<String, Int>() }
    var itemsEdited by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf<List<JSONObject>?>(null) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var pendingPreset by remember { mutableStateOf<String?>(null) }   // 바꾸기 전 확인 중인 프리셋

    LaunchedEffect(Unit) {
        RoutineCatalog.loadLimits()
        presets = RoutineCatalog.loadPresets()
        if (templateId == null) templateId = presets?.firstOrNull()?.optString("template_id")?.takeIf { it.isNotBlank() }
    }
    LaunchedEffect(templateId) {
        val tid = templateId ?: return@LaunchedEffect
        RoutineCatalog.ensurePreset(tid)
        val saved = existing?.takeIf { it.templateId == tid }?.let { RoutineCatalog.loadRoutine(it, force = true) }
        routineItems = (saved ?: RoutineCatalog.presetRoutine(tid))?.items.orEmpty()
        durations.clear()
        itemsEdited = false
    }

    val total = routineItems.sumOf { durations[it.templateItemId] ?: it.durationSec }
    val routineValid = templateId != null && routineItems.isNotEmpty() &&
        total <= RoutineCatalog.limits.routineMaxSec && total > 0
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }   // 서버 저장·삭제 중에는 버튼을 막음

    /** 프리셋을 바꾸면 서버가 이 알람의 항목 시간 설정을 지우므로, 바꾼 값이 있으면 먼저 확인 */
    fun pickPreset(id: String) {
        showPresetDialog = false
        if (id == templateId) return
        val customized = itemsEdited || routineItems.any { it.customized }
        if (existing?.templateId != null && customized) pendingPreset = id else templateId = id
    }

    fun save() {
        val base = existing ?: Alarm(id = AlarmRepository.nextId(), label = "", hour = 0, minute = 0, days = emptySet())
        val content = isContent && !isBedtime
        val alarm = base.copy(
            label = label.trim(),
            hour = (hour12 % 12) + if (ampm == 1) 12 else 0,
            minute = minute,
            days = days,
            enabled = true,
            templateId = if (content) null else templateId,
            contentType = if (content) contentType else null,
            contentOption = if (content) contentOption else null
        )
        // 기본 시간과 다른 항목만 보냄 (보내지 않은 항목은 서버가 기본 시간으로)
        val items = if (!content && itemsEdited) JSONArray().apply {
            routineItems.forEach { item ->
                val sec = durations[item.templateItemId] ?: item.durationSec
                if (sec != item.defaultSec) put(JSONObject().put("template_item_id", item.templateItemId).put("duration_sec", sec))
            }
        } else null
        saving = true
        scope.launch {
            val error = AlarmRepository.save(context, alarm, items)
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

        // 알람 유형 (취침 알람에는 없음): 루틴이면 프리셋·항목 시간, 콘텐츠면 종류·세부
        if (!isBedtime) {
            SectionTitle(Icons.Filled.Tune, "아침에 할 일")
            Spacer(Modifier.height(12.dp))
            CallTypeToggle(isContent) { isContent = it }
            Spacer(Modifier.height(16.dp))
            if (isContent) {
                ContentEditor(
                    contentType = contentType,
                    contentOption = contentOption,
                    onType = { if (it != contentType) { contentType = it; contentOption = null } },
                    onOption = { contentOption = it }
                )
            } else {
                PresetRow(templateId?.let { RoutineCatalog.presetNames[it] }) { showPresetDialog = true }
                Spacer(Modifier.height(16.dp))
                RoutineItemsEditor(routineItems, durations) { id, sec ->
                    durations[id] = sec
                    itemsEdited = true
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        Button(
            onClick = { save() },
            enabled = label.isNotBlank() && !saving && (isBedtime || isContent || routineValid),
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

    // 프리셋 고르기: 이름 (TEMPLATE_NAME)과 대상 (TARGET_DESC)
    if (showPresetDialog) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            containerColor = RespecColors.Card,
            title = { Text("프리셋 선택", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val list = presets
                    if (list == null) Text("프리셋을 불러오지 못했어요", color = RespecColors.TextSub)
                    list?.forEach { p ->
                        val id = p.optString("template_id")
                        val on = id == templateId
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { pickPreset(id) }
                                .padding(vertical = 12.dp)
                        ) {
                            Text(
                                p.optString("template_name"),
                                color = if (on) RespecColors.Lime else Color.White,
                                fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            p.optString("target_desc").takeIf { it.isNotBlank() && it != "null" }?.let {
                                Text(it, color = RespecColors.TextSub, fontSize = 13.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresetDialog = false }) { Text("닫기", color = RespecColors.Purple) }
            }
        )
    }

    // 프리셋을 바꾸면 항목 시간 설정이 처음으로 돌아감 (서버 트리거가 RS_ALARM_ROUTINE_ITEM을 지움)
    pendingPreset?.let { next ->
        AlertDialog(
            onDismissRequest = { pendingPreset = null },
            containerColor = RespecColors.Card,
            title = { Text("프리셋을 바꿀까요?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("바꾸면 이 알람의 항목 시간 설정이 처음으로 돌아가요.", color = RespecColors.TextSub) },
            confirmButton = {
                TextButton(onClick = { templateId = next; pendingPreset = null }) { Text("바꾸기", color = RespecColors.Lime) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPreset = null }) { Text("취소", color = RespecColors.TextSub) }
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