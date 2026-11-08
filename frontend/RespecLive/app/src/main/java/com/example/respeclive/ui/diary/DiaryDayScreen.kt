package com.example.respeclive.ui.diary

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.data.ApiClient
import com.example.respeclive.data.JpegEncoder
import com.example.respeclive.data.RespecApi
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val DAY_NAMES = listOf("일", "월", "화", "수", "목", "금", "토")

/**
 * 하루 상세 (GET /diary/{date}): 사진, 그날 밤의 한 줄 일기, 그날 아침의 리포트(기상·루틴).
 * 사진은 올리거나 바꿀 수 있음 (JPEG로 바꿔 PUT /diary/{date}/photo). 미래 날짜는 서버가 받지 않음.
 * 올린 사진은 확인을 거쳐 지울 수 있음 (DELETE /diary/{date}/photo).
 */
@Composable
fun DiaryDayScreen(date: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var day by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    val cal = remember(date) {
        Calendar.getInstance().apply {
            clear()
            val (y, m, d) = date.split("-").map { it.toInt() }
            set(y, m - 1, d)
        }
    }
    val future = cal.after(Calendar.getInstance())

    LaunchedEffect(date, reload) {
        val r = RespecApi.diaryDay(date)
        if (r == null) error = ApiClient.lastError ?: "기록을 불러오지 못했어요" else day = r
    }

    // 사진 고르기 (권한 없이 쓰는 시스템 사진 선택기) → JPEG로 바꿔 올림
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            val jpeg = JpegEncoder.fromUri(context, uri)
            val url = jpeg?.let { RespecApi.uploadDiaryPhoto(date, it) }
            uploading = false
            if (url == null) {
                Toast.makeText(context, if (jpeg == null) "사진을 읽지 못했어요" else ApiClient.lastError ?: "사진을 올리지 못했어요", Toast.LENGTH_SHORT).show()
            } else {
                reload++
            }
        }
    }

    BackHandler(onBack = onBack)

    val night = day?.optJSONObject("night")
    val sessions = day?.optJSONArray("sessions")
    val session = sessions?.optJSONObject(0)?.optJSONObject("session")
    val steps = (0 until (sessions?.length() ?: 0)).flatMap { i ->
        val arr = sessions!!.optJSONObject(i)?.optJSONArray("steps")
        (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
    }
    val summary = day?.optJSONObject("summary")
    val photoUrl = day?.text("photo_url")
    // 사진이 없을 때 대신 보여 줄, 서버가 한 줄 일기에 맞춰 만든 넥스티 그림
    val nextyImageUrl = day?.text("nexty_image_url")

    Column(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // 헤더
        Row(Modifier.padding(top = 12.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(RespecColors.PurpleDark).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = RespecColors.Purple)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                "${cal.get(Calendar.MONTH) + 1}월 ${cal.get(Calendar.DAY_OF_MONTH)}일 ${DAY_NAMES[cal.get(Calendar.DAY_OF_WEEK) - 1]}요일",
                color = RespecColors.AccentText, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold
            )
        }

        // 사진
        val photoShape = RoundedCornerShape(24.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(photoShape)
                .border(1.dp, RespecColors.CardBorder, photoShape)
                .clickable(enabled = !future && !uploading && !deleting) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
        ) {
            if (photoUrl != null || nextyImageUrl != null) {
                PhotoBox(photoUrl ?: nextyImageUrl!!, cal.get(Calendar.DAY_OF_MONTH), maxPx = 1400, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    Modifier.fillMaxSize().background(RespecColors.Card),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.AddAPhoto, null, tint = RespecColors.Purple, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (future) "아직 오지 않은 날이에요" else "오늘을 남길 사진을 올려 보세요",
                        color = RespecColors.TextSub, fontSize = 15.sp
                    )
                }
            }
            if (!future && (photoUrl != null || nextyImageUrl != null || uploading)) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(12.dp)
                        .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        when {
                            uploading -> "올리는 중…"
                            deleting -> "지우는 중…"
                            photoUrl == null -> "사진 올리기"   // 넥스티 그림 위: 직접 찍은 사진으로 바꿀 수 있음
                            else -> "사진 바꾸기"
                        },
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            if (photoUrl != null && !uploading && !deleting) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(12.dp)
                        .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                        .clickable { confirmDelete = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("사진 삭제", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 한 줄 일기 (그날 밤 대화에서 정리)
        SectionTitle("한 줄 일기")
        Card {
            val diary = night?.text("diary_text")
            Text(
                diary ?: when {
                    night == null -> "이날은 밤 대화 기록이 없어요"
                    night.optString("extract_status") == "PENDING" -> "밤 대화를 정리하는 중이에요. 잠시 뒤에 다시 확인해 주세요"
                    else -> "한 줄 일기가 없어요"
                },
                color = if (diary != null) RespecColors.Text else RespecColors.TextSub,
                fontSize = if (diary != null) 18.sp else 15.sp,
                fontWeight = if (diary != null) FontWeight.SemiBold else FontWeight.Normal,
                lineHeight = 26.sp
            )
        }

        // 이날의 리포트 (그날 아침 세션): 기상 시간 · 루틴 수행 · 루틴 성공률
        SectionTitle("이날의 리포트")
        if (session == null) {
            Card { Text("이날은 모닝콜 기록이 없어요", color = RespecColors.TextSub, fontSize = 15.sp) }
        } else {
            val done = summary?.optInt("routine_done") ?: 0
            val total = summary?.optInt("routine_total") ?: 0
            val rate = summary?.text("routine_completion_rate")?.toFloatOrNull()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("기상 시간", Modifier.weight(1f)) {
                    FitText(clockOf(session.text("first_response_at")) ?: "-", color = RespecColors.AccentText,
                        fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                }
                StatTile("루틴 수행", Modifier.weight(1f)) {
                    FitText(if (total > 0) "$done / $total" else "-", color = RespecColors.AccentText,
                        fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                }
                StatTile("루틴 성공률", Modifier.weight(1f)) {
                    if (total > 0) {
                        // 칸에 맞춰 링 크기를 정함 (최대 68dp)
                        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val ring = minOf(68.dp, maxWidth, maxHeight)
                            RateRing(rate ?: 0f, ring, ring * 7 / 68, "${((rate ?: 0f) * 100).toInt()}%", 17.sp * (ring / 68.dp),
                                color = RespecColors.AccentText)
                        }
                    } else {
                        Text("-", color = RespecColors.AccentText, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            if (session.optBoolean("coaching_skipped")) {
                Spacer(Modifier.height(10.dp))
                Text("이날은 루틴을 쉬어 간 날이에요", color = RespecColors.TextSub, fontSize = 13.sp)
            }
            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card {
                    steps.forEachIndexed { i, s ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        StepRow(s)
                    }
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = RespecColors.Danger, fontSize = 13.sp)
        }
        Spacer(Modifier.height(32.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = RespecColors.Card,
            title = { Text("사진을 삭제할까요?", color = RespecColors.Text, fontWeight = FontWeight.Bold) },
            text = { Text("삭제한 사진은 되돌릴 수 없어요.", color = RespecColors.TextSub) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    scope.launch {
                        val ok = RespecApi.deleteDiaryPhoto(date)
                        deleting = false
                        if (ok) reload++
                        else Toast.makeText(context, ApiClient.lastError ?: "사진을 삭제하지 못했어요", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("삭제", color = RespecColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("취소", color = RespecColors.TextSub) }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = RespecColors.AccentText, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(top = 26.dp, bottom = 12.dp))
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape).padding(18.dp),
        content = content
    )
}

@Composable
private fun Chip(text: String, fg: Color, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** 리포트 칸: 위에 이름, 가운데에 값 */
@Composable
private fun StatTile(label: String, modifier: Modifier, value: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.height(132.dp).clip(shape).background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape).padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FitText(label, color = RespecColors.TextSub, fontSize = 14.sp)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { value() }
    }
}

/** 루틴 단계 한 줄: 이름 · 계획 시간 · 결과 */
@Composable
private fun StepRow(step: JSONObject) {
    val (label, fg, bg) = when (step.optString("step_status")) {
        "DONE" -> Triple("완료", RespecColors.Mint, RespecColors.MintDark)
        "SKIPPED" -> Triple("건너뜀", RespecColors.TextSub, RespecColors.Disabled)
        else -> Triple("미완", RespecColors.Danger, RespecColors.Danger.copy(alpha = 0.15f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(step.optString("step_name"), color = RespecColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        step.text("planned_duration_sec")?.toIntOrNull()?.takeIf { it > 0 }?.let { sec ->
            Text(if (sec % 60 == 0) "${sec / 60}분" else "${sec / 60}분 ${sec % 60}초", color = RespecColors.TextSub, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
        }
        Chip(label, fg, bg)
    }
}

/** 서버 시각(ISO, 시간대 포함) → 기기 시간대의 "HH:MM" */
internal fun clockOf(iso: String?): String? {
    iso ?: return null
    return try {
        val clean = iso.replace(Regex("\\.\\d+"), "").replace(Regex("Z$"), "+00:00")
        val parsed: Date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(clean) ?: return null
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(parsed)
    } catch (e: Exception) {
        null
    }
}
