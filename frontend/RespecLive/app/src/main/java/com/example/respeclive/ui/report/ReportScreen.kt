package com.example.respeclive.ui.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.audio.AlarmSoundPlayer
import com.example.respeclive.data.ApiClient
import com.example.respeclive.data.RespecApi
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.diary.RateRing
import com.example.respeclive.ui.diary.text
import com.example.respeclive.ui.theme.RespecColors
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val PERIODS = listOf("week" to "주", "month" to "월", "year" to "연")
private val WEEKDAYS = listOf("월", "화", "수", "목", "금", "토", "일")
private val Flame = Color(0xFFFF8A65)
private val AXIS_LABEL_W = 34.dp   // y축 눈금 글씨 칸 (시각 "23:00")
private val LEVEL_LABEL_W = 10.dp  // 기상 단계 그래프의 y축 눈금 칸 (한 자리 숫자라 좁게)
private val AXIS_GAP = 6.dp
private val CHART_PAD = 7.dp       // 그래프 위아래 여백 (맨 위·아래 눈금 글씨가 걸치는 자리)
private val CHART_MIN_H = 110.dp
private val CHART_ROW_H = 22.dp    // 눈금 한 칸 높이: 눈금이 많아지면 그래프가 그만큼 길어짐

/** 눈금 칸 수에 맞춘 그래프 높이 (글씨가 겹치거나 잘리지 않도록) */
private fun chartHeight(steps: Int): Dp = maxOf(CHART_MIN_H, CHART_ROW_H * steps + CHART_PAD * 2)

/** 그래프 한 칸 (series 항목). 시각은 자정부터 지난 분, 취침은 자정을 넘기면 24시 이후로 이어 셈 */
private class Bucket(val label: String, val wakeMin: Int?, val bedMin: Int?)

private fun clockToMin(s: String?): Int? =
    s?.split(":")?.takeIf { it.size == 2 }?.let { (h, m) -> h.toIntOrNull()?.let { hh -> m.toIntOrNull()?.let { hh * 60 + it } } }

/** 취침 시각: 정오 전(00:30 등)은 전날 밤에서 이어지도록 24시간을 더함 (00:30 → 24:30) */
private fun bedClockToMin(s: String?): Int? = clockToMin(s)?.let { if (it < 12 * 60) it + 24 * 60 else it }

private fun JSONObject.rate(key: String): Float? = text(key)?.toFloatOrNull()

private fun JSONObject.buckets(): List<Bucket> {
    val arr = optJSONArray("series") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
        Bucket(
            label = it.optString("label"),
            wakeMin = clockToMin(it.text("avg_wake_clock")),
            bedMin = bedClockToMin(it.text("avg_bed_clock"))
        )
    }
}

private fun percent(rate: Float?) = rate?.let { "${Math.round(it * 100)}%" } ?: "-"

/**
 * 리포트 (GET /reports). 상단은 전체 누적 기상·취침 성공률, 그래프 두 개(기상·취침 시간 추이)는 각각 주·월·연을 고를 수 있고,
 * 아래는 현재·최장 연속 기상일.
 * 취침 값(서버 backend/domain/bedtime_rules.py, 팀 결정 11/06): overall.bedtime_success_rate
 * (밤 대화를 마친 날 ÷ 첫 기록일부터 어제까지의 날 수, 오늘 밤 대화를 마쳤으면 오늘까지),
 * totals.avg_bed_clock · series[].avg_bed_clock (밤 대화를 마친 시각의 평균 "HH:MM"). 기록이 없으면 '-'와 빈 그래프.
 */
@Composable
fun ReportScreen() {
    var wakePeriod by rememberSaveable { mutableStateOf("week") }
    var bedPeriod by rememberSaveable { mutableStateOf("week") }
    var levelPeriod by rememberSaveable { mutableStateOf("week") }
    var next by remember { mutableStateOf<JSONObject?>(null) }
    val reports = remember { mutableStateMapOf<String, JSONObject>() }
    var error by remember { mutableStateOf<String?>(null) }

    // 고른 기간의 리포트만 받아 둠 (전체 누적·연속 달성일은 어느 기간 응답에나 같이 들어 있음)
    suspend fun load(period: String) {
        if (period in reports) return
        val r = RespecApi.report(period)
        if (r != null) reports[period] = r else error = ApiClient.lastError ?: "리포트를 불러오지 못했어요."
    }
    LaunchedEffect(wakePeriod) { load(wakePeriod) }
    LaunchedEffect(bedPeriod) { load(bedPeriod) }
    LaunchedEffect(levelPeriod) { load(levelPeriod) }
    LaunchedEffect(Unit) { next = RespecApi.wakeVolumeNext() }

    val any = reports[wakePeriod] ?: reports[bedPeriod] ?: reports.values.firstOrNull()
    val overall = any?.optJSONObject("overall")
    val wake = reports[wakePeriod]
    val bed = reports[bedPeriod]

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // 전체 누적 성공률
        ReportCard {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                RateColumn(overall?.rate("wake_success_rate"), "기상 성공률", RespecColors.Lime, Modifier.weight(1f))
                VerticalLine(Modifier.padding(vertical = 4.dp))
                RateColumn(overall?.rate("bedtime_success_rate"), "취침 성공률", RespecColors.Purple, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(16.dp))

        // 기상 시간 추이: y축 기본 05:00~08:00
        ReportCard {
            CardHeader(Icons.Outlined.Schedule, "기상 시간 추이", wakePeriod) { wakePeriod = it }
            Spacer(Modifier.height(14.dp))
            SummaryLine("평균 기상 시간", wake?.optJSONObject("totals")?.text("avg_wake_clock") ?: "--:--")
            Spacer(Modifier.height(14.dp))
            ClockLineChart(wake?.buckets().orEmpty().map { it.label to it.wakeMin }, 5, 8, RespecColors.Lime, Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(16.dp))

        // 취침 시간 추이: y축 기본 21:00~다음 날 01:00
        ReportCard {
            CardHeader(Icons.Outlined.Bedtime, "취침 시간 추이", bedPeriod) { bedPeriod = it }
            Spacer(Modifier.height(14.dp))
            SummaryLine("평균 취침 시간", bed?.optJSONObject("totals")?.text("avg_bed_clock") ?: "--:--")
            Spacer(Modifier.height(14.dp))
            ClockLineChart(bed?.buckets().orEmpty().map { it.label to it.bedMin }, 21, 25, RespecColors.Purple, Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(16.dp))

        // 기상 단계 변화 (평일·주말 통합, 서버 wake_levels)
        WakeLevelCard(reports[levelPeriod], next, levelPeriod) { levelPeriod = it }

        Spacer(Modifier.height(16.dp))

        // 연속 기상일 (서버가 기상 성공한 날로 계산)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StreakCard(Icons.Outlined.LocalFireDepartment, Flame, "현재 연속 기상", any?.optInt("current_streak"), Modifier.weight(1f))
            StreakCard(Icons.Outlined.EmojiEvents, RespecColors.AccentText, "최장 연속 기상", any?.optInt("best_streak"), Modifier.weight(1f))
        }

        error?.takeIf { any == null }?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = RespecColors.Danger, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReportCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        content = content
    )
}

@Composable
private fun VerticalLine(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).fillMaxHeight().background(RespecColors.CardBorder))
}

/** 그래프 위 요약 한 줄: "평균 기상 시간 06:45", "평균 취침 시간 23:10" */
@Composable
private fun SummaryLine(label: String, value: String, detail: String? = null) {
    // 글씨 크기가 달라도 세로 가운데를 맞춰 같은 높이로
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = RespecColors.Text.copy(alpha = 0.85f), fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        Text(value, color = RespecColors.AccentText, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        detail?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = RespecColors.TextSub, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RateColumn(rate: Float?, label: String, color: Color, modifier: Modifier) {
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RateRing(rate = rate ?: 0f, size = 84.dp, stroke = 9.dp, label = percent(rate), labelSize = 18.sp, color = color)
        Spacer(Modifier.height(10.dp))
        Text(label, color = RespecColors.Text, fontSize = 15.sp)
    }
}

@Composable
private fun CardHeader(
    icon: ImageVector, title: String, period: String,
    periods: List<Pair<String, String>> = PERIODS, onPeriod: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = RespecColors.AccentText, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        FitText(title, color = RespecColors.Text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            periods.forEach { (key, label) ->
                val selected = key == period
                Box(
                    Modifier
                        .size(width = 30.dp, height = 24.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) RespecColors.Lime else RespecColors.Disabled)
                        .clickable { onPeriod(key) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (selected) RespecColors.OnAccent else RespecColors.TextSub,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** 칸 수가 많은(연간) 그래프는 x축 글씨를 줄임 */
private fun axisLabelSize(count: Int) = if (count > 7) 8.sp else 12.sp

/**
 * 칸별 평균 시각 꺾은선 (buckets: x축 글씨 to 자정부터 지난 분). y축은 기본 [defaultLow, defaultHigh]시,
 * 기록이 벗어나면 정시 단위로 넓힘. 24시 이상(자정 넘긴 취침)은 눈금에 00시·01시로 표시
 */
@Composable
private fun ClockLineChart(buckets: List<Pair<String, Int?>>, defaultLow: Int, defaultHigh: Int, color: Color, modifier: Modifier) {
    val values = buckets.mapNotNull { it.second }
    val lowHour = minOf(defaultLow, (values.minOrNull() ?: (defaultLow * 60)) / 60)
    val highHour = maxOf(defaultHigh, ((values.maxOrNull() ?: (defaultHigh * 60)) + 59) / 60)
    val hours = (highHour downTo lowHour).toList()
    val lime = color

    Column(modifier) {
        ChartArea(hours.map { "%02d:00".format(it % 24) }) {
                if (buckets.isEmpty()) return@ChartArea
                val span = (highHour - lowHour) * 60f
                val inset = 10.dp.toPx()
                val stepX = if (buckets.size > 1) (size.width - inset * 2) / (buckets.size - 1) else 0f
                val points = buckets.mapIndexedNotNull { i, b ->
                    b.second?.let {
                        val x = if (buckets.size > 1) inset + stepX * i else size.width / 2
                        Offset(x, size.height * (highHour * 60 - it) / span)
                    }
                }
                if (points.size > 1) {
                    val path = Path().apply {
                        moveTo(points[0].x, points[0].y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, lime, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                points.forEach { drawCircle(lime, 4.dp.toPx(), it) }
        }
        XAxisLabels(buckets.map { it.first }, axisLabelSize(buckets.size))
    }
}

/**
 * y축 눈금 글씨(위에서 아래로)와 그래프. 글씨는 각 가로줄 높이에 가운데를 맞추고,
 * 눈금이 많으면 그만큼 그래프를 길게 해 모든 눈금(한 시간마다)에 글씨를 표시.
 * draw는 가로줄 위에 그림: 그리는 영역은 위아래 CHART_PAD를 뺀 곳, 맨 위 줄 y=0, 맨 아래 줄 y=height
 */
@Composable
private fun ChartArea(yLabels: List<String>, labelWidth: Dp = AXIS_LABEL_W, draw: DrawScope.() -> Unit) {
    val steps = (yLabels.size - 1).coerceAtLeast(1)
    val grid = RespecColors.CardBorder
    Row(Modifier.fillMaxWidth().height(chartHeight(steps))) {
        Layout(
            content = {
                yLabels.forEach { label ->
                    Text(
                        label, color = RespecColors.TextSub, fontSize = 10.sp,
                        maxLines = 1, softWrap = false, textAlign = TextAlign.End, modifier = Modifier.width(labelWidth)
                    )
                }
            },
            modifier = Modifier.width(labelWidth).fillMaxHeight()
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
            val pad = CHART_PAD.roundToPx()
            val inner = constraints.maxHeight - pad * 2
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { i, p -> p.place(0, pad + inner * i / steps - p.height / 2) }
            }
        }
        Spacer(Modifier.width(AXIS_GAP))
        Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = CHART_PAD)) {
            for (i in 0..steps) {
                val y = size.height * i / steps
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            draw()
        }
    }
}

/** x축 글씨: 점과 같은 위치 (양 끝 10dp 안쪽) */
@Composable
private fun XAxisLabels(labels: List<String>, fontSize: TextUnit, labelWidth: Dp = AXIS_LABEL_W) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Spacer(Modifier.width(labelWidth + AXIS_GAP))
        // BoxWithConstraints(SubcomposeLayout)는 IntrinsicSize 안에서 측정할 수 없어 Layout으로 직접 배치
        Layout(
            content = {
                labels.forEach {
                    Text(
                        it, color = RespecColors.TextSub, fontSize = fontSize, textAlign = TextAlign.Center,
                        maxLines = 1, softWrap = false, modifier = Modifier.width(28.dp)
                    )
                }
            },
            modifier = Modifier.weight(1f)
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(Constraints()) }
            val width = constraints.maxWidth
            val inset = 10.dp.toPx()
            val step = if (placeables.size > 1) (width - inset * 2) / (placeables.size - 1) else 0f
            layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
                placeables.forEachIndexed { i, p ->
                    val center = if (placeables.size > 1) inset + step * i else width / 2f
                    p.placeRelative((center - p.width / 2f).toInt(), 0)
                }
            }
        }
    }
}

/** 그 아침의 기상 단계 기록 (WakeLogOut) */
private class WakeDay(val date: String, val start: Int?, val received: Int?, val state: String?)

private fun JSONObject.wakeDays(): List<WakeDay> {
    val arr = optJSONArray("wake_levels") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
        val date = o.optString("session_date").takeIf { it.length == 10 } ?: return@mapNotNull null
        fun lv(key: String) = if (o.isNull(key)) null else o.optInt(key).takeIf { it in 1..AlarmSoundPlayer.WAKE_MAX_LEVEL }
        WakeDay(date, lv("start_level") ?: lv("applied_level"), lv("received_level"), o.text("state"))
    }
}

/** 리포트 series 칸: (x축 글씨, 시작 날짜, 끝 날짜(포함 안 함)) "YYYY-MM-DD" */
private fun JSONObject.seriesRanges(): List<Triple<String, String, String>> {
    val arr = optJSONArray("series") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
        val from = o.text("start") ?: return@mapNotNull null
        val until = o.text("end") ?: return@mapNotNull null
        Triple(o.optString("label"), from.take(10), until.take(10))
    }
}

/** "YYYY-MM-DD" 날짜 [start, end)를 하루씩 (minSdk 24라 java.time 대신 Calendar) */
private fun daysBetween(start: String?, end: String?): List<Calendar> {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val from = start?.let { runCatching { fmt.parse(it) }.getOrNull() } ?: return emptyList()
    val until = end?.let { runCatching { fmt.parse(it) }.getOrNull() } ?: return emptyList()
    val c = Calendar.getInstance().apply { time = from }
    return buildList {
        while (c.time.before(until) && size < 31) { add(c.clone() as Calendar); c.add(Calendar.DAY_OF_MONTH, 1) }
    }
}

private fun Calendar.iso() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(time)

private fun averageLevel(levels: List<Int>) = if (levels.isEmpty()) "-" else "%.1f단계".format(levels.average())

/**
 * 기상 단계 변화: 다음 아침 시작 단계(GET /wake-volume/next, 없으면 마지막 기록), 시작 단계(선)와
 * 반응한 단계(속이 빈 동그라미: 전화를 받거나 미루기를 누른 단계), 기간 평균. 평일·주말은 나누지 않음 (11/26).
 * 주는 날짜마다(시작 단계는 계단선), 월은 주별·연은 월별 평균(꺾은선)
 */
@Composable
private fun WakeLevelCard(report: JSONObject?, next: JSONObject?, period: String, onPeriod: (String) -> Unit) {
    val days = report?.wakeDays().orEmpty()
    val last = days.lastOrNull()
    val current = next?.optInt("start_level")?.takeIf { it in 1..AlarmSoundPlayer.WAKE_MAX_LEVEL } ?: last?.start
    val subtitle: String? = when (next?.text("state") ?: last?.state) {
        "USER" -> "직접 정한 단계로 깨워요."
        "RECOMMENDED" -> "넥스티가 찾은 단계로 깨워요."
        else -> null
    }

    // x축 — 주: 월~일 (날짜마다). 월: 1주~5주, 연: 1월~12월 (칸별 평균)
    // 칸은 다른 그래프와 같게 서버 series의 [start, end) 날짜 범위로 나눔
    val daily = period == "week"
    val points: List<Pair<Float?, Float?>>
    val labels: List<String>
    if (daily) {
        val byDate = days.associateBy { it.date }
        val slots = daysBetween(report?.text("start"), report?.text("end"))
        points = slots.map { d -> byDate[d.iso()].let { it?.start?.toFloat() to it?.received?.toFloat() } }
        labels = slots.map { d -> WEEKDAYS[(d.get(Calendar.DAY_OF_WEEK) + 5) % 7] }
    } else {
        val ranges = report?.seriesRanges().orEmpty()
        points = ranges.map { (_, from, until) ->
            val list = days.filter { it.date >= from && it.date < until }   // "YYYY-MM-DD"는 글자 순서 = 날짜 순서
            list.mapNotNull { it.start }.averageOrNull() to list.mapNotNull { it.received }.averageOrNull()
        }
        labels = ranges.map { it.first }
    }
    var showGuide by rememberSaveable { mutableStateOf(false) }

    ReportCard {
        CardHeader(Icons.Outlined.VolumeUp, "기상 단계 변화", period, onPeriod = onPeriod)
        subtitle?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = RespecColors.TextSub, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))
        SummaryLine("다음 시작 단계", current?.let { "${it}단계" } ?: "-")
        Spacer(Modifier.height(14.dp))
        LevelChart(points, labels, stepLine = daily, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(RespecColors.Lime, if (daily) "시작 단계" else "평균 시작")
            Spacer(Modifier.width(14.dp))
            LegendDot(RespecColors.Purple, if (daily) "반응한 단계" else "평균 반응", hollow = true)
            Spacer(Modifier.weight(1f))
            // 그래프 보는 법 (눌러서 펼치기)
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { showGuide = !showGuide }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = RespecColors.TextSub, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text("보는 법", color = RespecColors.TextSub, fontSize = 12.sp)
            }
        }
        if (showGuide) {
            Spacer(Modifier.height(10.dp))
            LevelGuide(period)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LevelStat("평균 시작 단계", averageLevel(days.mapNotNull { it.start }), Modifier.weight(1f))
            LevelStat("평균 반응한 단계", averageLevel(days.mapNotNull { it.received }), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text("단계가 높을수록 알람이 크게 울려요.", color = RespecColors.TextSub, fontSize = 12.sp)
    }
}

@Composable
private fun LegendDot(color: Color, label: String, hollow: Boolean = false) {
    // 그래프와 같은 모양: 시작 단계는 꽉 찬 점, 반응한 단계는 속이 빈 동그라미
    val dot = Modifier.size(9.dp).clip(RoundedCornerShape(50))
    Box(if (hollow) dot.border(2.dp, color, RoundedCornerShape(50)) else dot.background(color))
    Spacer(Modifier.width(5.dp))
    Text(label, color = RespecColors.TextSub, fontSize = 12.sp)
}

@Composable
private fun LevelStat(label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).border(1.dp, RespecColors.CardBorder, shape).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, color = RespecColors.TextSub, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, color = RespecColors.AccentText, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** 그래프 보는 법: 펼쳤을 때만 보이는 짧은 설명 */
@Composable
private fun LevelGuide(period: String) {
    // 주 보기는 날마다, 월·연 보기는 칸(주·달)마다 평균
    val unit = when (period) { "month" -> "그 주"; "year" -> "그달"; else -> null }
    val shape = RoundedCornerShape(16.dp)
    val lines = listOf(
        "단계는 알람 소리 크기예요. 1단계가 가장 작고 5단계가 가장 커요. 받지 않으면 10초마다 한 단계씩 커져요.",
        if (unit != null) "노란 선은 ${unit}에 알람이 울리기 시작한 단계의 평균이에요."
        else "노란 선은 그날 알람이 울리기 시작한 단계예요. 넥스티가 기록을 보고 나에게 맞게 바꿔 가요.",
        if (unit != null) "보라 동그라미는 ${unit}에 전화를 받거나 미루기를 누른 단계의 평균이에요."
        else "보라 동그라미는 전화를 받거나 미루기를 누른 순간의 단계예요. 끝까지 반응하지 않은 날은 없어요.",
        "동그라미가 노란 선과 같은 높이면 시작 소리에 바로 깬 거예요. 동그라미가 위에 있을수록 소리가 커진 뒤에야 깼다는 뜻이에요."
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Background)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        lines.forEach { line ->
            Row {
                Text("•", color = RespecColors.TextSub, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                Text(line, color = RespecColors.Text, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

private fun List<Int>.averageOrNull(): Float? = if (isEmpty()) null else average().toFloat()

/**
 * 시작 단계(노란 선·점)와 반응한 단계(보라 속 빈 동그라미). y축 1~5단계.
 * stepLine이면 이어진 날끼리 계단선(주·월), 아니면 월 평균 꺾은선(연). 값이 없는 칸에서 선이 끊김
 */
@Composable
private fun LevelChart(points: List<Pair<Float?, Float?>>, labels: List<String>, stepLine: Boolean, modifier: Modifier) {
    val top = AlarmSoundPlayer.WAKE_MAX_LEVEL
    val lime = RespecColors.Lime
    val purple = RespecColors.Purple
    Column(modifier) {
        ChartArea((top downTo 1).map { "$it" }, LEVEL_LABEL_W) {
            if (points.isEmpty()) return@ChartArea
            val steps = top - 1
            val inset = 10.dp.toPx()
            val stepX = if (points.size > 1) (size.width - inset * 2) / (points.size - 1) else 0f
            fun x(i: Int) = if (points.size > 1) inset + stepX * i else size.width / 2
            fun y(level: Float) = size.height * (top - level) / steps
            val starts = points.mapIndexed { i, p -> p.first?.let { Offset(x(i), y(it)) } }
            val stroke = 2.5.dp.toPx()
            starts.zipWithNext().forEach { (a, b) ->
                if (a != null && b != null) {
                    if (stepLine) {
                        // 가로로 갔다가 세로로 바뀌는 계단 (시작 단계는 날마다 정수)
                        val corner = Offset(b.x, a.y)
                        drawLine(lime, a, corner, strokeWidth = stroke, cap = StrokeCap.Round)
                        drawLine(lime, corner, b, strokeWidth = stroke, cap = StrokeCap.Round)
                    } else {
                        drawLine(lime, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
                    }
                }
            }
            starts.forEach { p -> p?.let { drawCircle(lime, 4.dp.toPx(), it) } }
            points.forEachIndexed { i, p ->
                p.second?.let { drawCircle(purple, 3.5.dp.toPx(), Offset(x(i), y(it)), style = Stroke(2.dp.toPx())) }
            }
        }
        XAxisLabels(labels, if (stepLine) 12.sp else axisLabelSize(labels.size), LEVEL_LABEL_W)
    }
}

@Composable
private fun StreakCard(icon: ImageVector, tint: Color, label: String, days: Int?, modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = RespecColors.Text, fontSize = 14.sp, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(days?.toString() ?: "-", color = RespecColors.AccentText, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(6.dp))
            Text("일", color = RespecColors.Text, fontSize = 15.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}
