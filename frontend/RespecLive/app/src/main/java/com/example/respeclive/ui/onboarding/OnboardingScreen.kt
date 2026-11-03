package com.example.respeclive.ui.onboarding

import android.Manifest
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChecklistRtl
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.data.ApiClient
import com.example.respeclive.data.DeviceLocation
import com.example.respeclive.data.RespecApi
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

// ---------------- 선택지 (서버 값 ↔ 화면 이름, backend/domain/codes.py) ----------------

private data class Choice(val code: String, val label: String)

private val GENDERS = listOf(Choice("MALE", "남성"), Choice("FEMALE", "여성"), Choice("NONE", "선택 안 함"))
private val TONES = listOf(Choice("FORMAL", "존댓말"), Choice("CASUAL", "반말"))
private val SPEECH_STYLES = listOf(Choice("KIND", "다정한 말투"), Choice("FRIENDLY", "친근한 말투"), Choice("FIRM", "단호한 말투"))
private val VOICES = listOf(Choice("FEMALE", "여성 목소리"), Choice("MALE", "남성 목소리"), Choice("NEUTRAL", "중성적인 목소리"))
private val GENRES = listOf(
    Choice("POP", "팝"), Choice("JAZZ", "재즈"), Choice("CLASSIC", "클래식"), Choice("DANCE", "댄스"), Choice("ACOUSTIC", "어쿠스틱")
)

private data class ContentKind(val code: String, val label: String, val desc: String, val icon: String)

private val CONTENTS = listOf(
    ContentKind("MEDITATION", "명상", "호흡과 몸 감각에 집중하며 차분하게 깨어나요", "🧘"),
    ContentKind("EXERCISE", "운동", "가벼운 스트레칭과 동작으로 몸을 깨워요", "🤸"),
    ContentKind("LANGUAGE", "외국어", "아침마다 짧은 표현을 소리 내어 익혀요", "🗣️"),
    ContentKind("QUIZ", "퀴즈", "가벼운 퀴즈로 머리를 깨워요", "💡"),
)

private const val CONTENT_MIN = 5            // 콘텐츠 알람은 고정 5분 (11/01 팀 결정)
private const val ROUTINE_MAX_MIN = 15       // 루틴 합계 상한

private val STEP_TITLES = listOf("기본 정보 입력", "페르소나 설정", "루틴 선택", "시작하기")

// 루틴 카드 배경 (사진 대신)
private val CARD_GRADIENTS = listOf(
    listOf(Color(0xFF3A3F5C), Color(0xFF8C7A6B)),
    listOf(Color(0xFF2F4A4A), Color(0xFF8DA08A)),
    listOf(Color(0xFF3B2F4F), Color(0xFF9A7FA8)),
    listOf(Color(0xFF4A3A2A), Color(0xFFB08D62)),
    listOf(Color(0xFF26384F), Color(0xFF6E8FB0)),
)

/** 말투 미리보기 문장 (어조 × 말투) */
private fun previewLine(tone: String, style: String): String = when (tone to style) {
    "FORMAL" to "FRIENDLY" -> "좋은 아침이에요~ 오늘 하루도 신나게 시작해 봐요!"
    "FORMAL" to "FIRM" -> "일어날 시간입니다. 오늘 할 일을 바로 시작하세요."
    "CASUAL" to "KIND" -> "좋은 아침이야! 오늘도 함께 시작해 볼까?"
    "CASUAL" to "FRIENDLY" -> "좋은 아침~ 오늘 하루도 신나게 시작해 보자!"
    "CASUAL" to "FIRM" -> "일어날 시간이야. 오늘 할 일 바로 시작하자."
    else -> "좋은 아침이에요! 오늘도 함께 시작해 볼까요?"
}

private fun labelOf(list: List<Choice>, code: String) = list.firstOrNull { it.code == code }?.label ?: code

private fun minutesText(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return if (s == 0) "${m}분" else "${m}분 ${s}초"
}

/**
 * 온보딩 4단계: 기본 정보 → 페르소나 → 루틴(프리셋) / 콘텐츠 선택 → 확인 후 POST /onboarding.
 * 루틴 시간 조정은 알람 세부 설정에서 한다 (기본 10분, 상한 15분).
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 아침 브리핑 날씨용 위치 권한을 미리 받음. 거부해도 온보딩은 끝남 (날씨 없이 브리핑)
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }

    var step by rememberSaveable { mutableIntStateOf(0) }

    // 1단계
    var name by rememberSaveable { mutableStateOf("") }
    var birth by rememberSaveable { mutableStateOf<String?>(null) }     // "YYYY-MM-DD"
    var gender by rememberSaveable { mutableStateOf("NONE") }
    // 2단계
    var callName by rememberSaveable { mutableStateOf("") }
    var tone by rememberSaveable { mutableStateOf("FORMAL") }
    var style by rememberSaveable { mutableStateOf("KIND") }
    var voice by rememberSaveable { mutableStateOf("FEMALE") }
    var genre by rememberSaveable { mutableStateOf("POP") }
    // 3단계
    var isContent by rememberSaveable { mutableStateOf(false) }
    var templateId by rememberSaveable { mutableStateOf<String?>(null) }
    var contentType by rememberSaveable { mutableStateOf<String?>(null) }

    var presets by remember { mutableStateOf<List<JSONObject>?>(null) }
    var presetsError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    fun loadPresets() {
        presetsError = null
        scope.launch {
            val list = RespecApi.presets()
            presets = list
            if (list == null) presetsError = ApiClient.lastError ?: "루틴 목록을 불러오지 못했어요"
        }
    }
    LaunchedEffect(Unit) { loadPresets() }

    val selectedPreset = presets?.firstOrNull { it.optString("template_id") == templateId }

    val canNext = when (step) {
        0 -> name.isNotBlank() && birth != null
        1 -> callName.isNotBlank()
        2 -> if (isContent) contentType != null else selectedPreset != null
        else -> !submitting
    }

    fun submit() {
        val body = JSONObject()
            .put("user_name", name.trim())
            .put("birth_date", birth)
            .put("gender", gender)
            .put("user_call_name", callName.trim())
            .put("tone", tone)
            .put("speech_style", style)
            .put("voice_type", voice)
            .put("music_genre", genre)
            .put("morning_call_type", if (isContent) "CONTENT" else "ROUTINE")
        if (isContent) body.put("content_type", contentType) else body.put("template_id", templateId)
        submitting = true
        submitError = null
        scope.launch {
            val ok = RespecApi.onboard(body)
            submitting = false
            when {
                !ok -> submitError = ApiClient.lastError ?: "저장하지 못했어요. 다시 시도해 주세요"
                DeviceLocation.hasPermission(context) -> onDone()
                else -> locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    }

    fun next() {
        if (step == 0 && callName.isBlank()) callName = name.trim()   // 호칭 기본값 = 이름
        if (step < 3) step++ else submit()
    }

    BackHandler(enabled = step > 0) { step-- }

    Column(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Re:Spec", color = RespecColors.Lime, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(18.dp))
        StepIndicator(step)
        Spacer(Modifier.height(22.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (step) {
                0 -> BasicInfoStep(name, { name = it }, birth, { birth = it }, gender, { gender = it })
                1 -> PersonaStep(
                    callName, { callName = it }, tone, { tone = it }, style, { style = it },
                    voice, { voice = it }, genre, { genre = it }
                )
                2 -> RoutineStep(
                    isContent = isContent,
                    onTypeChange = { isContent = it },
                    presets = presets,
                    presetsError = presetsError,
                    onRetry = { loadPresets() },
                    templateId = templateId,
                    onPreset = { templateId = it },
                    contentType = contentType,
                    onContent = { contentType = it }
                )
                else -> SummaryStep(
                    callName = callName, tone = tone, style = style, voice = voice, genre = genre,
                    isContent = isContent, preset = selectedPreset, contentType = contentType,
                    error = submitError,
                    onEdit = { step = it }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) {
                OutlinedButton(
                    onClick = { step-- },
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(30.dp),
                    border = BorderStroke(1.dp, RespecColors.CardBorder)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    FitText("이전으로", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Button(
                onClick = { next() },
                enabled = canNext,
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RespecColors.Lime,
                    contentColor = Color.Black,
                    disabledContainerColor = RespecColors.Disabled,
                    disabledContentColor = RespecColors.TextSub
                )
            ) {
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.Black, strokeWidth = 2.dp)
                } else {
                    FitText(if (step == 3) "시작하기" else "다음으로", color = if (canNext) Color.Black else RespecColors.TextSub,
                        fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------- 공통 ----------------

@Composable
private fun StepIndicator(step: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        STEP_TITLES.forEachIndexed { i, title ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
                    // 원 사이 연결선 (지나온 구간은 라임)
                    Row(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.weight(1f).height(2.dp).align(Alignment.CenterVertically)
                                .background(if (i == 0) Color.Transparent else if (i <= step) RespecColors.Lime else RespecColors.CardBorder)
                        )
                        Box(
                            Modifier.weight(1f).height(2.dp).align(Alignment.CenterVertically)
                                .background(if (i == STEP_TITLES.lastIndex) Color.Transparent else if (i < step) RespecColors.Lime else RespecColors.CardBorder)
                        )
                    }
                    val current = i == step
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (current) RespecColors.Lime else RespecColors.Card)
                            .border(1.5.dp, if (current) RespecColors.Lime else RespecColors.CardBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${i + 1}",
                            color = if (current) Color.Black else RespecColors.TextSub,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    title,
                    color = if (i == step) RespecColors.Lime else RespecColors.TextSub,
                    fontSize = 12.sp,
                    fontWeight = if (i == step) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, sub: String? = null) {
    Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    if (sub != null) {
        Spacer(Modifier.height(6.dp))
        Text(sub, color = RespecColors.TextSub, fontSize = 14.sp)
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(choices: List<Choice>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { c -> Pill(c.label, c.code == selected) { onSelect(c.code) } }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) RespecColors.Lime else RespecColors.Background)
            .border(1.dp, if (selected) RespecColors.Lime else RespecColors.CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.Black else Color.White,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun InputField(value: String, onChange: (String) -> Unit, placeholder: String, maxLen: Int) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxLen) onChange(it) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        placeholder = { Text(placeholder, color = RespecColors.TextSub) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedContainerColor = RespecColors.Background,
            unfocusedContainerColor = RespecColors.Background,
            focusedBorderColor = RespecColors.Lime,
            unfocusedBorderColor = RespecColors.CardBorder,
            cursorColor = RespecColors.Lime
        )
    )
}

// ---------------- 1. 기본 정보 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BasicInfoStep(
    name: String, onName: (String) -> Unit,
    birth: String?, onBirth: (String) -> Unit,
    gender: String, onGender: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val utc = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepTitle("기본 정보를 입력해 주세요", "AI가 나에게 맞게 대화할 수 있도록 알려주세요.")
        FormCard {
            FieldLabel("이름")
            InputField(name, onName, "이름을 입력해 주세요", 50)
            Spacer(Modifier.height(8.dp))
            FieldLabel("생년월일")
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(shape)
                    .background(RespecColors.Background)
                    .border(1.dp, RespecColors.CardBorder, shape)
                    .clickable { showPicker = true }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    birth?.replace("-", ". ") ?: "생년월일을 선택해 주세요",
                    color = if (birth != null) Color.White else RespecColors.TextSub,
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            FieldLabel("성별")
            ChipRow(GENDERS, gender, onGender)
        }
    }

    if (showPicker) {
        val initial = birth?.let { runCatching { utc.parse(it)?.time }.getOrNull() } ?: utc.parse("2000-01-01")!!.time
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        if (ms <= System.currentTimeMillis()) onBirth(utc.format(Date(ms)))
                    }
                    showPicker = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("취소") } }
        ) {
            DatePicker(state = state)
        }
    }
}

// ---------------- 2. 페르소나 ----------------

@Composable
private fun PersonaStep(
    callName: String, onCallName: (String) -> Unit,
    tone: String, onTone: (String) -> Unit,
    style: String, onStyle: (String) -> Unit,
    voice: String, onVoice: (String) -> Unit,
    genre: String, onGenre: (String) -> Unit
) {
    val line = previewLine(tone, style)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepTitle("페르소나를 설정해 주세요")
        FormCard {
            FieldLabel("호칭")
            InputField(callName, onCallName, "AI가 나를 부를 이름", 30)
            FieldLabel("어조")
            ChipRow(TONES, tone, onTone)
            FieldLabel("말투")
            ChipRow(SPEECH_STYLES, style, onStyle)

            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(RespecColors.Background)
                    .border(1.dp, RespecColors.CardBorder, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(RespecColors.MintDark),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.WbSunny, null, tint = RespecColors.Mint, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("미리보기", color = RespecColors.TextSub, fontSize = 12.sp)
                    Text("“$line”", color = Color.White, fontSize = 15.sp)
                }
            }

            FieldLabel("목소리")
            ChipRow(VOICES, voice, onVoice)
            VoicePreview(voice, line)

            FieldLabel("음악 장르")
            ChipRow(GENRES, genre, onGenre)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 목소리 미리 듣기. 실제 통화 목소리(Gemini Live)는 서버 토큰으로만 들을 수 있어
 * 여기서는 기기 TTS의 음높이로 느낌만 보여 준다.
 */
@Composable
private fun VoicePreview(voice: String, line: String) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.KOREAN
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { speaking = true }
                    override fun onDone(id: String?) { speaking = false }
                    override fun onStop(id: String?, interrupted: Boolean) { speaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { speaking = false }
                })
                ready = true
            }
        }
        engine
    }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }

    fun play() {
        if (!ready) return
        tts.setPitch(
            when (voice) {
                "MALE" -> 0.75f
                "NEUTRAL" -> 1.0f
                else -> 1.25f
            }
        )
        tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(50)
        Row(
            Modifier
                .clip(shape)
                .background(RespecColors.MintDark)
                .border(1.dp, RespecColors.Mint.copy(alpha = 0.4f), shape)
                .clickable(enabled = ready) { play() }
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.PlayArrow, null, tint = RespecColors.Mint)
            Spacer(Modifier.width(8.dp))
            Text("미리 듣기", color = RespecColors.Mint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Waveform(speaking)
    }
}

@Composable
private fun Waveform(active: Boolean) {
    val infinite = rememberInfiniteTransition(label = "wave")
    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "phase"
    )
    val base = listOf(0.3f, 0.6f, 1f, 0.5f, 0.3f, 0.7f, 0.4f)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        base.forEachIndexed { i, h ->
            val k = if (active) (if (i % 2 == 0) phase else 1f - phase) * 0.6f + 0.4f else 0.35f
            Box(
                Modifier
                    .width(5.dp)
                    .height((28 * h * k).coerceAtLeast(5f).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (active) RespecColors.Mint else RespecColors.CardBorder)
            )
        }
    }
}

// ---------------- 3. 루틴 / 콘텐츠 선택 ----------------

@Composable
private fun RoutineStep(
    isContent: Boolean,
    onTypeChange: (Boolean) -> Unit,
    presets: List<JSONObject>?,
    presetsError: String?,
    onRetry: () -> Unit,
    templateId: String?,
    onPreset: (String) -> Unit,
    contentType: String?,
    onContent: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepTitle(
            if (isContent) "콘텐츠를 선택해 주세요" else "루틴을 선택해 주세요",
            if (isContent) "매일 아침 5분, 리스펙과 함께할 콘텐츠예요." else "탭해서 내 루틴에 담아 보세요."
        )

        // 루틴 / 콘텐츠 전환
        val shape = RoundedCornerShape(50)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(RespecColors.Card)
                .border(1.dp, RespecColors.CardBorder, shape)
                .padding(4.dp)
        ) {
            listOf(false to "루틴", true to "콘텐츠").forEach { (content, label) ->
                val selected = isContent == content
                Box(
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(if (selected) RespecColors.Lime else Color.Transparent)
                        .clickable { onTypeChange(content) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (selected) Color.Black else RespecColors.TextSub,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (isContent) {
            ContentPicker(contentType, onContent)
        } else {
            when {
                presets == null && presetsError == null -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = RespecColors.Lime)
                }
                presets.isNullOrEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        presetsError ?: "선택할 수 있는 루틴이 없어요",
                        color = RespecColors.TextSub, fontSize = 14.sp, textAlign = TextAlign.Center
                    )
                    TextButton(onClick = onRetry) { Text("다시 불러오기", color = RespecColors.Lime) }
                }
                else -> PresetPager(presets, templateId, onPreset)
            }
        }
    }
}

@Composable
private fun PresetPager(presets: List<JSONObject>, templateId: String?, onPreset: (String) -> Unit) {
    val pager = rememberPagerState { presets.size }
    val current = presets[pager.currentPage.coerceIn(presets.indices)]
    val currentId = current.optString("template_id")
    val picked = currentId == templateId

    // 계정 헤더 (인스타그램 피드 형태)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(RespecColors.Lime),
            contentAlignment = Alignment.Center
        ) {
            Text("R", color = Color.Black, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Re:Spec 추천 루틴", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("모닝 루틴 · ${presets.size}가지 스타일", color = RespecColors.TextSub, fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(12.dp))

    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(end = 40.dp),
        pageSpacing = 12.dp,
        modifier = Modifier.fillMaxWidth()
    ) { page ->
        val p = presets[page]
        PresetCard(
            preset = p,
            index = page,
            total = presets.size,
            selected = p.optString("template_id") == templateId,
            onClick = { onPreset(p.optString("template_id")) }
        )
    }

    Spacer(Modifier.height(10.dp))
    // 페이지 점
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(presets.size) { i ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (i == pager.currentPage) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (i == pager.currentPage) RespecColors.Lime else RespecColors.CardBorder)
            )
        }
    }

    // 담기
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPreset(currentId) }) {
            Text(
                if (picked) "담기됨" else "담기",
                color = if (picked) RespecColors.Lime else Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (picked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                null,
                tint = if (picked) RespecColors.Lime else Color.White
            )
        }
    }

    Text(
        buildString {
            append("Re:Spec  ")
            append(current.optString("template_name"))
            if (presets.size > 1) append(" 외 ${presets.size - 1}개")
        },
        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
    )
    Text("옆으로 넘겨 다른 루틴도 확인해 보세요.", color = RespecColors.TextSub, fontSize = 13.sp)

    Spacer(Modifier.height(16.dp))
    val totalSec = current.optInt("total_sec", 600)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("루틴 시간", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(RespecColors.Lime)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("기본 ${minutesText(totalSec)}", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "항목별 시간은 알람 설정에서 30초 단위로 바꿀 수 있어요 (최대 ${ROUTINE_MAX_MIN}분)",
        color = RespecColors.TextSub, fontSize = 12.sp
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun PresetCard(preset: JSONObject, index: Int, total: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val items = preset.optJSONArray("items")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.sortedBy { it.optInt("item_order") }
    }.orEmpty()
    val badge = preset.optString("target_desc").takeIf { it.isNotBlank() && it != "null" } ?: "추천 루틴 ${index + 1}"

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = maxWidth * 1.25f)
                .clip(shape)
                .background(Brush.verticalGradient(CARD_GRADIENTS[index % CARD_GRADIENTS.size]))
                .border(if (selected) 2.dp else 1.dp, if (selected) RespecColors.Lime else RespecColors.CardBorder, shape)
                .clickable(onClick = onClick)
                .padding(14.dp)
        ) {
            Text(
                badge,
                color = Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(6.dp))
                    .background(RespecColors.Mint)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .widthIn(max = 180.dp)
            )
            Text(
                "${index + 1}/$total",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )

            // 위쪽 배지와 겹치지 않도록 위 여백을 둠 (항목이 많으면 카드가 늘어남)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(top = 36.dp)) {
                Text(
                    preset.optString("template_name"),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 28.sp
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(RespecColors.Mint.copy(alpha = 0.92f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(item.optString("item_name"), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentPicker(selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CONTENTS.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { c ->
                    val on = c.code == selected
                    val shape = RoundedCornerShape(22.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 150.dp)
                            .clip(shape)
                            .background(if (on) RespecColors.Lime.copy(alpha = 0.12f) else RespecColors.Card)
                            .border(if (on) 2.dp else 1.dp, if (on) RespecColors.Lime else RespecColors.CardBorder, shape)
                            .clickable { onSelect(c.code) }
                            .padding(16.dp)
                    ) {
                        Text(c.icon, fontSize = 28.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(c.label, color = if (on) RespecColors.Lime else Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(4.dp))
                        Text(c.desc, color = RespecColors.TextSub, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
        Text(
            "콘텐츠는 매일 ${CONTENT_MIN}분으로 진행돼요. 세부 주제는 알람 설정에서 고를 수 있어요.",
            color = RespecColors.TextSub, fontSize = 12.sp
        )
    }
}

// ---------------- 4. 확인 ----------------

@Composable
private fun SummaryStep(
    callName: String, tone: String, style: String, voice: String, genre: String,
    isContent: Boolean, preset: JSONObject?, contentType: String?,
    error: String?,
    onEdit: (Int) -> Unit
) {
    val content = CONTENTS.firstOrNull { it.code == contentType }
    val stepCount = preset?.optJSONArray("items")?.length() ?: 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepTitle("이제, 시작할 준비가 되었어요!", "지금까지 설정한 내용을 확인하고\n내일 아침부터 새로운 루틴을 시작해 보세요.")
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(RespecColors.Card)
                .border(1.dp, RespecColors.CardBorder, shape)
        ) {
            SummaryRow(Icons.Filled.PersonOutline, "호칭", callName) { onEdit(1) }
            SummaryRow(Icons.Filled.ChatBubbleOutline, "말투", labelOf(SPEECH_STYLES, style), badge = labelOf(TONES, tone)) { onEdit(1) }
            SummaryRow(Icons.Filled.GraphicEq, "목소리", labelOf(VOICES, voice)) { onEdit(1) }
            SummaryRow(Icons.Filled.MusicNote, "음악 장르", labelOf(GENRES, genre)) { onEdit(1) }
            if (isContent) {
                SummaryRow(Icons.Filled.ChecklistRtl, "선택한 콘텐츠", content?.label ?: "-") { onEdit(2) }
                SummaryRow(Icons.Filled.Timer, "콘텐츠 시간", "${CONTENT_MIN}분 (고정)", last = true) { onEdit(2) }
            } else {
                SummaryRow(
                    Icons.Filled.ChecklistRtl, "선택한 루틴",
                    preset?.let { "${it.optString("template_name")} (${stepCount}단계)" } ?: "-"
                ) { onEdit(2) }
                SummaryRow(
                    Icons.Filled.Timer, "루틴 시간",
                    "기본 ${minutesText(preset?.optInt("total_sec", 600) ?: 600)} · 알람 설정에서 조정",
                    last = true
                ) { onEdit(2) }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = RespecColors.Danger, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryRow(
    icon: ImageVector,
    title: String,
    value: String,
    badge: String? = null,
    last: Boolean = false,
    onClick: () -> Unit
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = RespecColors.Lime, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(value, color = RespecColors.TextSub, fontSize = 13.sp)
            }
            if (badge != null) {
                Text(
                    badge,
                    color = RespecColors.Lime,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(RespecColors.Lime.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = RespecColors.TextSub)
        }
        if (!last) {
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = RespecColors.CardBorder)
        }
    }
}
