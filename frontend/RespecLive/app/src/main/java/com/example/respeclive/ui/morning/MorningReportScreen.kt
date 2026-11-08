package com.example.respeclive.ui.morning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.data.ApiClient
import com.example.respeclive.data.RespecApi
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.diary.clockOf
import com.example.respeclive.ui.diary.text
import com.example.respeclive.ui.theme.RespecColors
import org.json.JSONObject
import java.util.Calendar

private val DAY_NAMES = listOf("일", "월", "화", "수", "목", "금", "토")

/**
 * 모닝콜이 끝난 뒤의 간단한 리포트: 기상 시간 · 대화 시간 · 연속 달성일과 오늘 아침 진행 순서.
 * POST /morning/sessions/{id}/end는 여러 번 불러도 처음 종료 시각을 유지하므로, 최종 결과를 받는 데에도 쓴다.
 * talkSec: 이번 통화의 길이 (앱에서 잰 값)
 */
@Composable
fun MorningReportScreen(sessionId: String, talkSec: Int, onOpenReport: () -> Unit, onHome: () -> Unit) {
    var view by remember { mutableStateOf<JSONObject?>(null) }
    var streak by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        val v = RespecApi.endMorning(sessionId)
        if (v == null) {
            error = ApiClient.lastError ?: "리포트를 불러오지 못했어요"
            return@LaunchedEffect
        }
        view = v
        streak = RespecApi.report("week")?.optInt("current_streak")
    }

    BackHandler(onBack = onHome)

    Column(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val loaded = view
            val session = loaded?.optJSONObject("session")
            ReportHeader(session?.text("session_date"))
            when {
                loaded != null && session != null -> {
                    ReportTitle(loaded.optJSONObject("progress"), session.text("content_type"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("기상 시간", clockOf(session.text("first_response_at")) ?: "-", RespecColors.Text, Modifier.weight(1f))
                        StatTile("대화 시간", talkLabel(talkSec), RespecColors.Text, Modifier.weight(1f))
                        StatTile("연속 달성", streak?.let { "${it}일" } ?: "-", RespecColors.AccentText, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))
                    MorningTimeline(session, loaded)
                }
                error != null -> Text(
                    error.orEmpty(),
                    color = RespecColors.Danger,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 32.dp)
                )
                else -> Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = RespecColors.AccentText)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onOpenReport,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, RespecColors.CardBorder)
            ) {
                FitText("리포트 보기", color = RespecColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onHome,
                modifier = Modifier.weight(1.4f).height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RespecColors.Button, contentColor = RespecColors.OnButton)
            ) {
                FitText("홈으로", color = RespecColors.OnButton, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 왼쪽 "AI 모닝콜 종료" 칩, 오른쪽 날짜 */
@Composable
private fun ReportHeader(date: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(50)
        Row(
            Modifier
                .clip(shape)
                .background(RespecColors.Card)
                .border(1.dp, RespecColors.CardBorder, shape)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(RespecColors.Mint))
            Spacer(Modifier.width(7.dp))
            Text("AI 모닝콜 종료", color = RespecColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        dateLabel(date)?.let { Text(it, color = RespecColors.TextSub, fontSize = 13.sp) }
    }
}

/** 별이 흩어진 축하 문구. 오늘 한 아침 콘텐츠와 마친 정도에 맞춰 문구가 달라짐 */
@Composable
private fun ReportTitle(progress: JSONObject?, contentType: String?) {
    val total = progress?.optInt("total") ?: 0
    val done = progress?.optInt("done") ?: 0
    val (title, sub) = when {
        done > 0 && done == total -> "오늘 아침도 해냈어요!" to when (contentType) {
            "EXERCISE" -> "스트레칭으로 몸까지 활짝 깨웠어요"
            "QUIZ" -> "퀴즈로 머리까지 말끔하게 깨웠어요"
            "LANGUAGE" -> "오늘의 영어 문장까지 챙겼어요"
            else -> "오늘 아침 할 일을 모두 마쳤어요"
        }
        done > 0 -> "오늘 아침도 해냈어요!" to "${total}개 중 ${done}개를 해냈어요. 시작이 반이에요"
        total > 0 -> "좋은 아침이에요!" to "제때 일어난 것만으로도 충분히 잘했어요"
        else -> "좋은 아침이에요!" to "오늘도 좋은 하루 보내세요"
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Sparkles(Modifier.matchParentSize())
        Column(
            Modifier.fillMaxWidth().padding(vertical = 34.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = RespecColors.Text, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(sub, color = RespecColors.TextSub, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}

/** 축하 장식: 네 갈래 별과 작은 점. 위치는 영역 크기에 대한 비율 */
@Composable
private fun Sparkles(modifier: Modifier) {
    val accent = RespecColors.AccentText
    val purple = RespecColors.Purple
    val mint = RespecColors.Mint
    Canvas(modifier) {
        fun star(x: Float, y: Float, radius: Float, color: Color) {
            val c = Offset(size.width * x, size.height * y)
            val r = radius * density
            val waist = r * 0.22f
            val path = Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x + waist, c.y - waist)
                lineTo(c.x + r, c.y)
                lineTo(c.x + waist, c.y + waist)
                lineTo(c.x, c.y + r)
                lineTo(c.x - waist, c.y + waist)
                lineTo(c.x - r, c.y)
                lineTo(c.x - waist, c.y - waist)
                close()
            }
            drawPath(path, color)
        }
        fun dot(x: Float, y: Float, radius: Float, color: Color) =
            drawCircle(color, radius * density, Offset(size.width * x, size.height * y))

        star(0.37f, 0.10f, 8f, accent)
        star(0.10f, 0.34f, 6f, accent)
        star(0.84f, 0.20f, 8f, accent)
        star(0.91f, 0.88f, 6f, accent)
        dot(0.20f, 0.14f, 3f, purple)
        dot(0.64f, 0.08f, 2f, mint)
        dot(0.73f, 0.20f, 2.5f, purple)
        dot(0.90f, 0.46f, 2f, mint)
        dot(0.08f, 0.84f, 3.5f, purple)
    }
}

@Composable
private fun StatTile(label: String, value: String, valueColor: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FitText(label, color = RespecColors.TextSub, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        FitText(value, color = valueColor, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** 오늘 아침 대화: 전화를 받은 시각, 체크한 단계, 건너뛰거나 남은 단계 순서 */
@Composable
private fun MorningTimeline(session: JSONObject, view: JSONObject) {
    val rows = remember(view) { timelineRows(session, view) }
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Text("오늘 아침 대화", color = RespecColors.Text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(16.dp))
        if (rows.isEmpty()) {
            Text("이번 통화에는 남길 기록이 없어요", color = RespecColors.TextSub, fontSize = 14.sp)
        }
        rows.forEachIndexed { i, row -> TimelineItem(row, isLast = i == rows.lastIndex) }
    }
}

@Composable
private fun TimelineItem(row: TimelineRow, isLast: Boolean) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // 세로 줄 위에 점을 얹음. 완료는 채운 점, 건너뛰거나 남은 단계는 빈 점
        Box(Modifier.width(14.dp).fillMaxHeight()) {
            if (!isLast) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(RespecColors.CardBorder)
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(if (row.done) RespecColors.Lime else RespecColors.Card)
                    .border(1.5.dp, if (row.done) RespecColors.Lime else RespecColors.TextSub, CircleShape)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            row.time,
            color = RespecColors.TextSub,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 46.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            row.label,
            color = if (row.done) RespecColors.Text else RespecColors.TextSub,
            fontSize = 15.sp,
            fontWeight = if (row.done) FontWeight.SemiBold else FontWeight.Normal,
            lineHeight = 21.sp,
            modifier = Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 16.dp)
        )
    }
}

private data class TimelineRow(val time: String, val label: String, val done: Boolean)

private fun timelineRows(session: JSONObject, view: JSONObject): List<TimelineRow> {
    val rows = mutableListOf<TimelineRow>()
    clockOf(session.text("first_response_at"))?.let { rows += TimelineRow(it, "전화를 받고 기상 완료", done = true) }
    val arr = view.optJSONArray("steps")
    val steps = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
    // 체크한 단계를 먼저, 남은 단계를 뒤에 (각각 단계 순서 유지)
    steps.sortedBy { it.optString("step_status") == "PENDING" }.forEach { s ->
        val name = s.optString("step_name")
        rows += when (s.optString("step_status")) {
            "DONE" -> TimelineRow(clockOf(s.text("checked_at")) ?: "완료", name, done = true)
            "SKIPPED" -> TimelineRow("건너뜀", name, done = false)
            else -> TimelineRow("남음", name, done = false)
        }
    }
    return rows
}

/** "YYYY-MM-DD" → "9월 29일 (화)" */
private fun dateLabel(date: String?): String? {
    val parts = date?.split("-")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 } ?: return null
    val (y, m, d) = parts
    val cal = Calendar.getInstance().apply { clear(); set(y, m - 1, d) }
    return "${m}월 ${d}일 (${DAY_NAMES[cal.get(Calendar.DAY_OF_WEEK) - 1]})"
}

private fun talkLabel(sec: Int): String = if (sec < 60) "${sec}초" else "${sec / 60}분"
