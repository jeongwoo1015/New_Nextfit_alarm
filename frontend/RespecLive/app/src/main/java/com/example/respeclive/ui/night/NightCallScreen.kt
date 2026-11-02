package com.example.respeclive.ui.night

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.ui.theme.RespecColors

@Composable
fun NightCallScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    bedtimeLabel: String = "오후 10:00"
) {
    BackHandler(onBack = onDecline)

    val infinite = rememberInfiniteTransition(label = "call")
    val ringScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
        label = "ringScale"
    )
    val ringAlpha by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
        label = "ringAlpha"
    )
    val acceptPulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "acceptPulse"
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            .padding(horizontal = 36.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 상단 안내
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Call, null, tint = RespecColors.Lime, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text("AI 굿나잇콜", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("오늘 하루를 함께 마무리해요", color = RespecColors.TextSub, fontSize = 14.sp)
        }

        // 발신자 (캐릭터 없이 아이콘 + 파동)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
                Box(
                    Modifier
                        .size(210.dp)
                        .scale(ringScale)
                        .alpha(ringAlpha)
                        .border(2.dp, RespecColors.Lime, CircleShape)
                )
                Box(
                    Modifier
                        .size(210.dp)
                        .clip(CircleShape)
                        .background(RespecColors.Card)
                        .border(1.dp, RespecColors.CardBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Bedtime, null, tint = RespecColors.Lime, modifier = Modifier.size(96.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("리스펙", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(RespecColors.Card)
                    .border(1.dp, RespecColors.CardBorder, RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Bedtime, null, tint = RespecColors.Lime, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(bedtimeLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 하단 버튼
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CallButton(
                icon = { Icon(Icons.Filled.CallEnd, null, tint = Color.White, modifier = Modifier.size(30.dp)) },
                background = Color(0xFFE5685F),
                label = "나중에 하기",
                onClick = onDecline
            )
            CallButton(
                icon = { Icon(Icons.Filled.Call, null, tint = Color.Black, modifier = Modifier.size(30.dp)) },
                background = RespecColors.Lime,
                label = "받기",
                scale = acceptPulse,
                onClick = onAccept
            )
        }
    }
}

@Composable
private fun CallButton(
    icon: @Composable () -> Unit,
    background: Color,
    label: String,
    scale: Float = 1f,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(76.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(background)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.height(12.dp))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}