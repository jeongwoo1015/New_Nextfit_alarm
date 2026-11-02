package com.example.respeclive.ui.routine

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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.data.Routine
import com.example.respeclive.data.RespecApi
import com.example.respeclive.data.RoutineRepository
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.launch

@Composable
fun RoutineScreen() {
    val items = RoutineRepository.items
    val doneCount = items.count { it.done }
    val scope = rememberCoroutineScope()

    // 오늘 서버 세션이 있으면 그 단계로 체크리스트를 채움 (없으면 404 → 기본 항목 유지)
    LaunchedEffect(Unit) {
        RespecApi.todaySession()?.let { RoutineRepository.applyView(it) }
    }

    fun toggle(index: Int, item: Routine) {
        val sid = RoutineRepository.sessionId
        val order = item.stepOrder
        if (sid == null || order == null) {
            RoutineRepository.setDone(index, !item.done)
            return
        }
        scope.launch {
            RespecApi.markStep(sid, order, if (item.done) "PENDING" else "DONE")
                ?.let { RoutineRepository.applyView(it) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Text(
            "오늘의 체크리스트",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { if (items.isEmpty()) 0f else doneCount / items.size.toFloat() },
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = RespecColors.Lime,
                trackColor = RespecColors.Disabled,
                drawStopIndicator = {}
            )
            Spacer(Modifier.width(12.dp))
            Text("$doneCount / ${items.size} 완료", color = RespecColors.TextSub, fontSize = 14.sp)
        }
        Spacer(Modifier.height(16.dp))

        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items.forEachIndexed { i, item ->
                RoutineRow(item) { toggle(i, item) }
            }
            if (RoutineRepository.sessionId == null) {
                TextButton(
                    onClick = { RoutineRepository.resetAll() },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("[테스트] 체크리스트 초기화", color = RespecColors.TextSub, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun RoutineRow(item: Routine, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            item.title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        if (item.done) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(RespecColors.Mint),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        } else {
            Box(
                Modifier
                    .size(28.dp)
                    .border(BorderStroke(2.dp, RespecColors.TextSub), CircleShape)
            )
        }
    }
}