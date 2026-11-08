package com.example.respeclive.ui.diary

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.data.ApiClient
import com.example.respeclive.data.PhotoLoader
import com.example.respeclive.data.RespecApi
import com.example.respeclive.ui.nexty.NextyMiniFace
import com.example.respeclive.ui.theme.RespecColors
import org.json.JSONObject
import java.util.Calendar

private val WEEKDAYS = listOf("일", "월", "화", "수", "목", "금", "토")
private val SWIPE_THRESHOLD = 60.dp           // 이만큼 옆으로 밀어야 달이 넘어감

/** 사진을 받기 전(또는 받지 못했을 때) 칸을 채우는 그라데이션 */
internal val PHOTO_GRADIENTS = listOf(
    Color(0xFFF7B267) to Color(0xFFEE6C8E),
    Color(0xFF8CC8D6) to Color(0xFF3F7F9C),
    Color(0xFF8FD18F) to Color(0xFF3C7A55),
    Color(0xFFB39DDB) to Color(0xFF5D5478),
    Color(0xFF8EC5E8) to Color(0xFF4A6893),
    Color(0xFFF8C59A) to Color(0xFFE07A6A),
)

/** "YYYY-MM" ↔ 년·월 */
private data class YearMonth(val year: Int, val month: Int) {
    val key: String get() = "%04d-%02d".format(year, month)
    fun plus(n: Int): YearMonth {
        val total = year * 12 + (month - 1) + n
        return YearMonth(total / 12, total % 12 + 1)
    }
    companion object {
        fun now() = Calendar.getInstance().let { YearMonth(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1) }
    }
}

/**
 * 한 줄 일기 달력 (GET /diary/calendar). 사진을 올린 날은 사진, 없는 날은 한 줄 일기에 맞춘 넥스티 그림(없으면 넥스티 얼굴).
 * 날짜를 누르면 그날의 한 줄 일기와 리포트(onOpenDay "YYYY-MM-DD"). 달은 화살표나 옆으로 밀어서 이동.
 */
@Composable
fun DiaryScreen(onOpenDay: (String) -> Unit) {
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.now().key) }
    val ym = monthKey.split("-").let { YearMonth(it[0].toInt(), it[1].toInt()) }
    val thisMonth = YearMonth.now()
    var days by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(monthKey) {
        val list = RespecApi.diaryCalendar(monthKey)
        error = if (list == null) ApiClient.lastError ?: "달력을 불러오지 못했어요" else null
        days = list.orEmpty().associateBy { it.optString("date") }
    }

    val today = Calendar.getInstance()
    val todayKey = "%04d-%02d-%02d".format(today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH))

    val canNext = ym.key < thisMonth.key
    fun prevMonth() { monthKey = ym.plus(-1).key }
    fun nextMonth() { if (canNext) monthKey = ym.plus(1).key }
    val swipeThreshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }

    Column(
        Modifier
            .fillMaxSize()
            // 옆으로 밀어 달 이동: 오른쪽으로 밀면 이전 달, 왼쪽으로 밀면 다음 달 (이번 달 이후는 없음)
            .pointerInput(monthKey) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (dragged > swipeThreshold) prevMonth()
                        else if (dragged < -swipeThreshold) nextMonth()
                    }
                ) { _, dx -> dragged += dx }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // 월 이동
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonthButton("‹", enabled = true) { prevMonth() }
            Text(
                "${ym.year}년 ${ym.month}월",
                color = RespecColors.Text,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            MonthButton("›", enabled = canNext) { nextMonth() }
        }

        // 요일 (일요일부터)
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            WEEKDAYS.forEach {
                Text(
                    it,
                    color = RespecColors.TextSub,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        // 날짜 칸
        val first = Calendar.getInstance().apply { clear(); set(ym.year, ym.month - 1, 1) }
        val blanks = first.get(Calendar.DAY_OF_WEEK) - 1                 // 일요일 = 0칸
        val dayCount = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val cells = List(blanks) { null } + (1..dayCount).toList()
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until 7) {
                    val day = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(0.64f)) {
                        if (day != null) {
                            val key = "%s-%02d".format(ym.key, day)
                            DayCell(day, days[key], future = key > todayKey) { onOpenDay(key) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("날짜를 누르면 그날의 한 줄 일기와 리포트를 볼 수 있어요.", color = RespecColors.TextSub, fontSize = 13.sp)
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = RespecColors.Danger, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MonthButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) RespecColors.Text else RespecColors.Disabled, fontSize = 22.sp)
    }
}

/**
 * 하루 칸: 사진 / 한 줄 일기 넥스티 그림 / 작은 넥스티 얼굴 / 날짜만.
 * 넥스티 그림은 서버가 한 줄 일기 내용에 맞춰 넥스티가 그 일을 하는 모습으로 만들어 저장한 이미지 (nexty_image_url).
 * 직접 올린 사진이 있으면 사진이 먼저이고, 기록은 있지만 그림이 아직 없으면(만드는 중·한 줄 일기 없음) 넥스티 얼굴
 */
@Composable
private fun DayCell(day: Int, info: JSONObject?, future: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val imageUrl = info?.text("photo_url") ?: info?.text("nexty_image_url")

    if (imageUrl != null) {
        Box(Modifier.fillMaxSize().clip(shape).clickable(onClick = onClick)) {
            PhotoBox(imageUrl, day, maxPx = 360, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.padding(6.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("$day", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .clickable(enabled = !future, onClick = onClick)
    ) {
        Text(
            "$day",
            color = if (future) RespecColors.Disabled else RespecColors.TextSub,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp, top = 6.dp)
        )
        // 달력은 모든 날이 오므로, 아침 세션이나 밤 기록이 있는 날만
        val hasRecord = info != null && (info.optBoolean("has_session") || info.optBoolean("has_night_record"))
        if (hasRecord && !future) {
            Box(Modifier.fillMaxSize().padding(top = 20.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                NextyMiniFace(Modifier.fillMaxWidth(0.62f).aspectRatio(0.8f))
            }
        }
    }
}

/** 서버 사진(서명 URL). 받는 동안이나 받지 못하면 날짜에 따라 정한 그라데이션 */
@Composable
internal fun PhotoBox(url: String, seed: Int, maxPx: Int, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) { bitmap = PhotoLoader.load(url, maxPx) }
    val (a, b) = PHOTO_GRADIENTS[Math.floorMod(seed, PHOTO_GRADIENTS.size)]
    Box(modifier.background(Brush.linearGradient(listOf(a, b)))) {
        bitmap?.let {
            Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 루틴 성공률 링 (rate 0~1). label이 있으면 가운데에 표시 */
@Composable
internal fun RateRing(rate: Float, size: Dp, stroke: Dp, label: String?, labelSize: TextUnit = 14.sp, color: Color = RespecColors.Mint) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(RespecColors.Disabled, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
            drawArc(color, -90f, 360f * rate.coerceIn(0f, 1f), false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        label?.let { Text(it, color = RespecColors.Text, fontSize = labelSize, fontWeight = FontWeight.Bold) }
    }
}

/** null(JSONObject.NULL)·빈 값이면 null */
internal fun JSONObject.text(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
