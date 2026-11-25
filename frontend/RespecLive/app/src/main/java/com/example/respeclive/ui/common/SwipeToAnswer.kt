package com.example.respeclive.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.ui.theme.RespecColors
import kotlin.math.roundToInt

/** 전화 수신 화면(모닝콜·굿나잇콜)의 "밀어서 받기". 오른쪽 끝까지 밀면 onComplete */
@Composable
fun SwipeToAnswer(onComplete: () -> Unit) {
    val density = LocalDensity.current
    val knob = 68.dp
    val inset = 4.dp
    var widthPx by remember { mutableFloatStateOf(0f) }
    var offsetPx by remember { mutableFloatStateOf(0f) }
    val maxOffset = (widthPx - with(density) { (knob + inset * 2).toPx() }).coerceAtLeast(0f)
    val shape = RoundedCornerShape(50)

    val infinite = rememberInfiniteTransition(label = "chevron")
    val chevronAlpha by infinite.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "chevronAlpha"
    )
    val hintAlpha = if (maxOffset > 0f) 1f - (offsetPx / maxOffset) else 1f

    Box(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .onSizeChanged { widthPx = it.width.toFloat() },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 84.dp, end = 16.dp)
                .alpha(hintAlpha),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("밀어서 받기", color = RespecColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Row {
                repeat(3) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        null,
                        tint = RespecColors.AccentText,
                        modifier = Modifier
                            .size(20.dp)
                            .alpha(chevronAlpha)
                    )
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .padding(inset)
                .size(knob)
                .clip(CircleShape)
                .background(RespecColors.Lime)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        offsetPx = (offsetPx + delta).coerceIn(0f, maxOffset)
                    },
                    onDragStopped = {
                        if (offsetPx > maxOffset * 0.85f) onComplete()
                        offsetPx = 0f
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Call, null, tint = RespecColors.OnAccent, modifier = Modifier.size(30.dp))
        }
    }
}
