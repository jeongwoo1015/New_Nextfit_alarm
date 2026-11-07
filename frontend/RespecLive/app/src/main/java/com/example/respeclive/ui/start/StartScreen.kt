package com.example.respeclive.ui.start

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.ui.nexty.NextyCharacter
import com.example.respeclive.ui.nexty.NextyMotion

// 화면 수정 명세 01: 시작 화면은 앱 테마와 상관없이 아침 크림색
private val BACKGROUND = Color(0xFFFFF4E4)
private val TITLE = Color(0xFF2B2522)
private val SUBTITLE = Color(0xFF6B5E55)

/** 앱을 켜면 서버 확인이 끝날 때까지 보이는 화면: 반기는 넥스티와 앱 이름 */
@Composable
fun StartScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .background(BACKGROUND)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        NextyCharacter(NextyMotion.WELCOME, Modifier.size(260.dp))
        Spacer(Modifier.height(12.dp))
        Text("Re:Spec", color = TITLE, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text("더 나은 나를 위한 아침 습관", color = SUBTITLE, fontSize = 14.sp)
    }
}
