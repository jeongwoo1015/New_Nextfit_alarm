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
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.data.ApiClient
import com.example.respeclive.data.RespecApi
import com.example.respeclive.ui.diary.RateRing
import com.example.respeclive.ui.diary.text
import com.example.respeclive.ui.theme.RespecColors
import org.json.JSONObject

private val PERIODS = listOf("week" to "주", "month" to "월", "year" to "연")
private val Flame = Color(0xFFFF8A65)
private val AXIS_LABEL_W = 34.dp   // y축 눈금 글씨 칸
private val AXIS_GAP = 6.dp

/** 그래프 한 칸 (series 항목) */
private class Bucket(val label: String, val wakeMin: Int?, val rate: Float?)

private fun clockToMin(s: String?): Int? =
    s?.split(":")?.takeIf { it.size == 2 }?.let { (h, m) -> h.toIntOrNull()?.let { hh -> m.toIntOrNull()?.let { hh * 60 + it } } }

private fun JSONObject.rate(key: String): Float? = text(key)?.toFloatOrNull()

private fun JSONObject.buckets(): List<Bucket> {
    val arr = optJSONArray("series") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
        Bucket(
            label = it.optString("label"),
            wakeMin = clockToMin(it.text("avg_wake_clock")),
            rate = it.takeIf { b -> b.optInt("routine_total") > 0 }?.rate("routine_completion_rate")
        )
    }
}

private fun percent(rate: Float?) = rate?.let { "${Math.round(it * 100)}%" } ?: "-"

/**
 * 리포트 (GET /reports). 상단은 전체 누적 기상·루틴 성공률, 그래프 두 개는 각각 주·월·연을 고를 수 있고,
 * 아래는 현재·최장 연속 달성일.
 */
@Composable
fun ReportScreen() {
    var wakePeriod by rememberSaveable { mutableStateOf("week") }
    var routinePeriod by rememberSaveable { mutableStateOf("week") }
    val reports = remember { mutableStateMapOf<String, JSONObject>() }
    var error by remember { mutableStateOf<String?>(null) }

    // 고른 기간의 리포트만 받아 둠 (전체 누적·연속 달성일은 어느 기간 응답에나 같이 들어 있음)
    suspend fun load(period: String) {
        if (period in reports) return
        val r = RespecApi.report(period)
        if (r != null) reports[period] = r else error = ApiClient.lastError ?: "리포트를 불러오지 못했어요"
    }
    LaunchedEffect(wakePeriod) { load(wakePeriod) }
    LaunchedEffect(routinePeriod) { load(routinePeriod) }

    val any = reports[wakePeriod] ?: reports[routinePeriod] ?: reports.values.firstOrNull()
    val overall = any?.optJSONObject("overall")
    val wake = reports[wakePeriod]
    val routine = reports[routinePeriod]

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
                RateColumn(overall?.rate("routine_completion_rate"), "루틴 성공률", RespecColors.Mint, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(16.dp))

        // 기상 시간 추이
        ReportCard {
            CardHeader(Icons.Outlined.Schedule, "기상 시간 추이", wakePeriod) { wakePeriod = it }
            Spacer(Modifier.height(14.dp))
            SummaryLine("평균 기상 시간", wake?.optJSONObject("totals")?.text("avg_wake_clock") ?: "--:--")
            Spacer(Modifier.height(14.dp))
            WakeLineChart(wake?.buckets().orEmpty(), Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(16.dp))

        // 루틴 수행 그래프
        ReportCard {
            CardHeader(Icons.Outlined.CheckBox, "루틴 수행 그래프", routinePeriod) { routinePeriod = it }
            Spacer(Modifier.height(14.dp))
            val totals = routine?.optJSONObject("totals")
            SummaryLine(
                "루틴 완료율",
                percent(totals?.takeIf { it.optInt("routine_total") > 0 }?.rate("routine_completion_rate")),
                totals?.let { "(${it.optInt("routine_done")} / ${it.optInt("routine_total")})" }
            )
            Spacer(Modifier.height(14.dp))
            RoutineBarChart(routine?.buckets().orEmpty(), Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(16.dp))

        // 연속 달성일
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StreakCard(Icons.Outlined.LocalFireDepartment, Flame, "현재 연속 달성일", any?.optInt("current_streak"), Modifier.weight(1f))
            StreakCard(Icons.Outlined.EmojiEvents, RespecColors.Lime, "최장 연속 달성일", any?.optInt("best_streak"), Modifier.weight(1f))
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

/** 그래프 위 요약 한 줄: "평균 기상 시간 06:45", "루틴 완료율 74% (52 / 70)" */
@Composable
private fun SummaryLine(label: String, value: String, detail: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
        Spacer(Modifier.width(10.dp))
        Text(value, color = RespecColors.Lime, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        detail?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = RespecColors.TextSub, fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

@Composable
private fun RateColumn(rate: Float?, label: String, color: Color, modifier: Modifier) {
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RateRing(rate = rate ?: 0f, size = 84.dp, stroke = 9.dp, label = percent(rate), labelSize = 18.sp, color = color)
        Spacer(Modifier.height(10.dp))
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun CardHeader(icon: ImageVector, title: String, period: String, onPeriod: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = RespecColors.Lime, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PERIODS.forEach { (key, label) ->
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
                        color = if (selected) Color.Black else RespecColors.TextSub,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** 칸 수가 많은(연간) 그래프는 x축 글씨를 줄임 */
private fun axisLabelSize(count: Int) = if (count > 7) 8.sp else 12.sp

/** 평균 기상 시각 꺾은선. y축은 기본 05:00~08:00, 기록이 벗어나면 정시 단위로 넓힘 */
@Composable
private fun WakeLineChart(buckets: List<Bucket>, modifier: Modifier) {
    val values = buckets.mapNotNull { it.wakeMin }
    val lowHour = minOf(5, (values.minOrNull() ?: 300) / 60)
    val highHour = maxOf(8, ((values.maxOrNull() ?: 480) + 59) / 60)
    val hours = (highHour downTo lowHour).toList()
    val lime = RespecColors.Lime
    val grid = RespecColors.CardBorder

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(110.dp)) {
            // y축 눈금
            Column(Modifier.fillMaxHeight().padding(end = AXIS_GAP), verticalArrangement = Arrangement.SpaceBetween) {
                hours.forEach {
                    Text("%02d:00".format(it), color = RespecColors.TextSub, fontSize = 10.sp, modifier = Modifier.width(AXIS_LABEL_W), textAlign = TextAlign.End)
                }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 7.dp)) {
                val steps = hours.size - 1
                for (i in 0..steps) {
                    val y = size.height * i / steps
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                if (buckets.isEmpty()) return@Canvas
                val span = (highHour - lowHour) * 60f
                val inset = 10.dp.toPx()
                val stepX = if (buckets.size > 1) (size.width - inset * 2) / (buckets.size - 1) else 0f
                val points = buckets.mapIndexedNotNull { i, b ->
                    b.wakeMin?.let {
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
        }
        // x축: 꺾은선 점과 같은 위치 (양 끝 10dp 안쪽)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Spacer(Modifier.width(AXIS_LABEL_W + AXIS_GAP))
            // BoxWithConstraints(SubcomposeLayout)는 IntrinsicSize 안에서 측정할 수 없어 Layout으로 직접 배치
            Layout(
                content = {
                    buckets.forEach { b ->
                        Text(
                            b.label,
                            color = RespecColors.TextSub,
                            fontSize = axisLabelSize(buckets.size),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.width(28.dp)
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
}

/** 칸별 루틴 완료율 막대. 가장 높은 칸은 라임, 기록이 없는 칸은 회색 */
@Composable
private fun RoutineBarChart(buckets: List<Bucket>, modifier: Modifier) {
    val best = buckets.withIndex().filter { it.value.rate != null }.maxByOrNull { it.value.rate!! }?.index
    val mint = RespecColors.Mint
    val lime = RespecColors.Lime
    val empty = Color(0xFF3A3D42)
    val grid = RespecColors.CardBorder

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(100.dp)) {
            Column(Modifier.fillMaxHeight().padding(end = AXIS_GAP), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("100%", "50%", "0%").forEach {
                    Text(it, color = RespecColors.TextSub, fontSize = 10.sp, modifier = Modifier.width(AXIS_LABEL_W), textAlign = TextAlign.End)
                }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 7.dp)) {
                listOf(0f, 0.5f, 1f).forEach {
                    val y = size.height * it
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                if (buckets.isEmpty()) return@Canvas
                val slot = size.width / buckets.size
                val barW = minOf(slot * 0.76f, 28.dp.toPx())
                val radius = CornerRadius(minOf(barW / 4, 5.dp.toPx()))
                buckets.forEachIndexed { i, b ->
                    val h = size.height * (b.rate?.coerceIn(0f, 1f) ?: 0.4f)
                    val color = when {
                        b.rate == null -> empty
                        i == best -> lime
                        else -> mint
                    }
                    val left = slot * i + (slot - barW) / 2
                    drawRoundRect(color, Offset(left, size.height - h), Size(barW, h), radius)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Spacer(Modifier.width(AXIS_LABEL_W + AXIS_GAP))
            buckets.forEach {
                Text(
                    it.label,
                    color = RespecColors.TextSub,
                    fontSize = axisLabelSize(buckets.size),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f)
                )
            }
        }
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
            Text(label, color = Color.White, fontSize = 14.sp, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(days?.toString() ?: "-", color = RespecColors.Lime, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(6.dp))
            Text("일", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}
