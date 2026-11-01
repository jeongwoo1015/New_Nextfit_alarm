package com.example.respeclive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.respeclive.live.LiveClient
import com.example.respeclive.live.LiveSetup
import org.json.JSONArray
import org.json.JSONObject

/** Gemini Live API 시험 화면 */
class MainActivity : ComponentActivity() {
    private lateinit var client: LiveClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        client = LiveClient(this)
        // 함수 호출 시험: 모델이 mark_routine_done을 부르면 기록만 하고 성공으로 응답
        client.onToolCall = { call ->
            JSONObject().put("status", "recorded").put("routine", call.args.optString("routine"))
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { LiveTestScreen(client) }
            }
        }
    }

    override fun onDestroy() {
        client.release()
        super.onDestroy()
    }
}

// API 연결 테스트용 임시 프롬프트
private val TEST_PROMPT = """
너는 '리스펙'이라는 아침 코칭 AI야. 한국어로, 친근하고 짧게(한두 문장) 말해.
통화가 시작되면 먼저 인사하고, 오늘 아침 루틴 3가지를 하나씩 안내해.
1) 물 한 컵 마시기(water)  2) 가벼운 스트레칭(stretch)  3) 오늘 할 일 한 가지 정하기(priority)
사용자가 어떤 루틴을 마쳤다고 말하면 mark_routine_done 함수를 호출한 뒤 짧게 칭찬하고 다음 루틴으로 넘어가.
아직 안 했다고 하면 재촉하지 말고 기다려.
""".trimIndent()

private fun testTools(): JSONArray {
    val routine = JSONObject()
        .put("type", "STRING")
        .put("description", "마친 루틴")
        .put("enum", JSONArray().put("water").put("stretch").put("priority"))
    val params = JSONObject()
        .put("type", "OBJECT")
        .put("properties", JSONObject().put("routine", routine))
        .put("required", JSONArray().put("routine"))
    val fn = JSONObject()
        .put("name", "mark_routine_done")
        .put("description", "사용자가 루틴 하나를 마쳤다고 말했을 때 호출한다")
        .put("parameters", params)
    return JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(fn)))
}

@Composable
private fun LiveTestScreen(client: LiveClient) {
    val context = LocalContext.current
    val status by client.status.collectAsState()
    val speaking by client.aiSpeaking.collectAsState()
    val level by client.userLevel.collectAsState()
    val lines by client.transcript.collectAsState()
    val tools by client.toolLog.collectAsState()
    val message by client.message.collectAsState()
    var muted by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(true) }

    fun begin() {
        client.start(
            LiveSetup(systemPrompt = TEST_PROMPT, tools = testTools()),
            kickoffText = "(통화가 연결되었어요. 먼저 인사하고 첫 번째 루틴을 안내해 주세요.)"
        )
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else Toast.makeText(context, "마이크 권한이 필요해요", Toast.LENGTH_SHORT).show()
    }

    fun startWithPermission() {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (ok) begin() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val active = status == LiveClient.Status.Connecting ||
            status == LiveClient.Status.Live ||
            status == LiveClient.Status.Reconnecting

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Gemini Live 시험", fontSize = 22.sp)
        Text("상태: $status   |   AI 말하는 중: ${if (speaking) "예" else "아니오"}")

        Text("내 목소리 크기")
        LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())

        message?.let { Text("메시지: $it", color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!active) {
                Button(onClick = { startWithPermission() }) { Text("연결 시작") }
            } else {
                Button(onClick = { client.stop() }) { Text("종료") }
            }
            OutlinedButton(
                enabled = status == LiveClient.Status.Live,
                onClick = { client.debugDropConnection() }
            ) { Text("연결 강제 끊기") }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("음소거")
            Switch(checked = muted, onCheckedChange = { muted = it; client.setMuted(it) })
            Spacer(Modifier.height(0.dp))
            Text("스피커폰")
            Switch(checked = speaker, onCheckedChange = { speaker = it; client.setSpeaker(it) })
        }

        OutlinedButton(
            enabled = status == LiveClient.Status.Live,
            onClick = { client.sendText("안녕하세요, 잘 들리나요?") }
        ) { Text("텍스트로 말 걸기") }

        Text("대화 (받아쓰기)", fontSize = 16.sp)
        lines.takeLast(10).forEach { l ->
            val who = if (l.role == LiveClient.Role.User) "나" else "AI"
            Text("$who: ${l.text}")
        }

        Text("함수 호출 기록", fontSize = 16.sp)
        if (tools.isEmpty()) Text("(없음)") else tools.forEach { Text(it) }
    }
}