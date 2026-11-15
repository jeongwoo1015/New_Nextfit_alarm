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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChecklistRtl
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

internal data class Choice(val code: String, val label: String)

internal val GENDERS = listOf(Choice("MALE", "남성"), Choice("FEMALE", "여성"), Choice("NONE", "선택 안 함"))
internal val TONES = listOf(Choice("FORMAL", "존댓말"), Choice("CASUAL", "반말"))
/** 성격 (RS_USER_SETTING.PERSONALITY). 예전 말투(SPEECH_STYLE)를 대신함 */
internal val PERSONALITIES = listOf(
    Choice("KIND", "다정한"), Choice("FRIENDLY", "친근한"), Choice("STRICT", "엄격한"), Choice("CHEERFUL", "유쾌한")
)
/** 깨우는 말의 강도 (RS_USER_SETTING.INTENSITY 1~3) */
internal val INTENSITIES = listOf(Choice("1", "약하게"), Choice("2", "보통"), Choice("3", "강하게"))
/** 영어 콘텐츠 수준 (RS_USER_SETTING.ENGLISH_LEVEL) */
internal val ENGLISH_LEVELS = listOf(Choice("BEGINNER", "초급"), Choice("INTERMEDIATE", "중급"))

internal const val DEFAULT_PERSONALITY = "KIND"
internal const val DEFAULT_INTENSITY = 2
internal const val DEFAULT_ENGLISH_LEVEL = "BEGINNER"

/** 자동 강도 올리기 설명 (온보딩·내 정보) */
internal const val AUTO_INTENSITY_DESC = "잘 일어나지 못한 날이 이어지면 깨우는 강도를 알아서 한 단계씩 올려요"
internal val VOICES = listOf(Choice("FEMALE", "여성 목소리"), Choice("MALE", "남성 목소리"), Choice("NEUTRAL", "중성적인 목소리"))
internal val GENRES = listOf(
    Choice("POP", "팝"), Choice("JAZZ", "재즈"), Choice("CLASSIC", "클래식"), Choice("DANCE", "댄스"), Choice("ACOUSTIC", "어쿠스틱")
)

private data class ContentKind(val code: String, val label: String, val desc: String, val icon: ImageVector)

private val CONTENTS = listOf(
    ContentKind("EXERCISE", "스트레칭", "넥스티와 함께 몸을 깨우며 건강한 아침을 시작해요", Icons.Filled.Accessibility),
    ContentKind("LANGUAGE", "영어", "매일 다른 영어 문장을 듣고 소리 내어 따라 읽어요", Icons.Filled.Translate),
)

private val STEP_TITLES = listOf("기본 정보", "페르소나 설정", "아침 콘텐츠", "시작하기")

/** 미리보기 문장: 성격 → 강도(1~3) → (존댓말, 반말) */
private val PREVIEW_LINES = mapOf(
    "KIND" to listOf(
        "좋은 아침이에요. 천천히 일어나도 괜찮아요." to "좋은 아침이야. 천천히 일어나도 괜찮아.",
        "좋은 아침이에요! 오늘도 함께 시작해 볼까요?" to "좋은 아침이야! 오늘도 함께 시작해 볼까?",
        "좋은 아침이에요! 이제 일어날 시간이에요, 같이 힘내 봐요!" to "좋은 아침이야! 이제 일어날 시간이야, 같이 힘내 보자!"
    ),
    "FRIENDLY" to listOf(
        "좋은 아침이에요~ 기지개 한번 켜 볼까요?" to "좋은 아침~ 기지개 한번 켜 볼까?",
        "좋은 아침이에요~ 오늘 하루도 신나게 시작해 봐요!" to "좋은 아침~ 오늘 하루도 신나게 시작해 보자!",
        "일어나요, 일어나요~ 벌써 아침이에요! 얼른 시작해요!" to "일어나, 일어나~ 벌써 아침이야! 얼른 시작하자!"
    ),
    "STRICT" to listOf(
        "일어날 시간이에요. 준비되면 시작해요." to "일어날 시간이야. 준비되면 시작하자.",
        "일어날 시간입니다. 오늘 할 일을 바로 시작하세요." to "일어날 시간이야. 오늘 할 일 바로 시작하자.",
        "지금 바로 일어나세요. 더 미룰 시간 없습니다." to "지금 바로 일어나. 더 미룰 시간 없어."
    ),
    "CHEERFUL" to listOf(
        "굿모닝이에요! 오늘은 어떤 재밌는 일이 있을까요?" to "굿모닝! 오늘은 어떤 재밌는 일이 있을까?",
        "짜잔, 아침이 왔어요! 오늘도 즐겁게 출발해 봐요!" to "짜잔, 아침이 왔어! 오늘도 즐겁게 출발하자!",
        "기상 나팔 빰빠밤! 이불 밖으로 탈출할 시간이에요!" to "기상 나팔 빰빠밤! 이불 밖으로 탈출할 시간이야!"
    )
)

/** 미리보기 문장 (어조 × 성격 × 강도) */
internal fun previewLine(tone: String, personality: String, intensity: String): String {
    val lines = PREVIEW_LINES[personality] ?: PREVIEW_LINES.getValue("KIND")
    val pair = lines[((intensity.toIntOrNull() ?: 2) - 1).coerceIn(0, lines.lastIndex)]
    return if (tone == "CASUAL") pair.second else pair.first
}

internal fun labelOf(list: List<Choice>, code: String) = list.firstOrNull { it.code == code }?.label ?: code

/** 온보딩 4단계: 기본 정보 → 페르소나 → 아침 콘텐츠 선택 → 확인 후 POST /onboarding */
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
    var personality by rememberSaveable { mutableStateOf(DEFAULT_PERSONALITY) }
    var intensity by rememberSaveable { mutableStateOf(DEFAULT_INTENSITY.toString()) }
    var autoIntensity by rememberSaveable { mutableStateOf(true) }
    var voice by rememberSaveable { mutableStateOf("FEMALE") }
    var genre by rememberSaveable { mutableStateOf("POP") }
    // 3단계
    var contentType by rememberSaveable { mutableStateOf<String?>(null) }
    var englishLevel by rememberSaveable { mutableStateOf(DEFAULT_ENGLISH_LEVEL) }

    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    val canNext = when (step) {
        0 -> name.isNotBlank() && birth != null
        1 -> callName.isNotBlank()
        2 -> contentType != null
        else -> !submitting
    }

    fun submit() {
        val body = JSONObject()
            .put("user_name", name.trim())
            .put("birth_date", birth)
            .put("gender", gender)
            .put("user_call_name", callName.trim())
            .put("tone", tone)
            .put("personality", personality)
            .put("intensity", intensity.toInt())
            .put("auto_intensity", autoIntensity)
            .put("english_level", englishLevel)
            .put("voice_type", voice)
            .put("music_genre", genre)
            .put("morning_call_type", "CONTENT")
            .put("content_type", contentType)
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
        Text("Re:Spec", color = RespecColors.AccentText, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(18.dp))
        StepIndicator(step)
        Spacer(Modifier.height(22.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (step) {
                0 -> BasicInfoStep(name, { name = it }, birth, { birth = it }, gender, { gender = it })
                1 -> PersonaStep(
                    callName, { callName = it }, tone, { tone = it }, personality, { personality = it },
                    intensity, { intensity = it }, autoIntensity, { autoIntensity = it },
                    voice, { voice = it }, genre, { genre = it }
                )
                2 -> ContentStep(contentType, { contentType = it }, englishLevel, { englishLevel = it })
                else -> SummaryStep(
                    callName = callName, tone = tone, personality = personality, intensity = intensity,
                    autoIntensity = autoIntensity, voice = voice, genre = genre,
                    contentType = contentType, englishLevel = englishLevel,
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
                    modifier = Modifier.weight(1f).height(58.dp),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, RespecColors.CardBorder)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RespecColors.Text)
                    Spacer(Modifier.width(8.dp))
                    FitText("이전으로", color = RespecColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Button(
                onClick = { next() },
                enabled = canNext,
                modifier = Modifier.weight(1f).height(58.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RespecColors.Button,
                    contentColor = RespecColors.OnButton,
                    disabledContainerColor = RespecColors.Disabled,
                    disabledContentColor = RespecColors.TextSub
                )
            ) {
                // 글자·아이콘은 주요 버튼 색(Button) 위의 색(OnButton). 라이트 테마에서 OnAccent는 버튼과 같은 진한 갈색이라 안 보임
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = RespecColors.OnButton, strokeWidth = 2.dp)
                } else {
                    FitText(if (step == 3) "시작하기" else "다음으로", color = if (canNext) RespecColors.OnButton else RespecColors.TextSub,
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
                            color = if (current) RespecColors.OnAccent else RespecColors.TextSub,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    title,
                    color = if (i == step) RespecColors.AccentText else RespecColors.TextSub,
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
    Text(title, color = RespecColors.Text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
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
    Text(text, color = RespecColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(choices: List<Choice>, selected: String, onSelect: (String) -> Unit) {
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
            color = if (selected) RespecColors.OnAccent else RespecColors.Text,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/** 켜기·끄기 한 줄: 제목 · 설명 · 스위치 */
@Composable
internal fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = RespecColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = RespecColors.TextSub, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RespecColors.OnAccent,
                checkedTrackColor = RespecColors.Lime,
                uncheckedThumbColor = RespecColors.TextSub,
                uncheckedTrackColor = RespecColors.Background,
                uncheckedBorderColor = RespecColors.CardBorder
            )
        )
    }
}

@Composable
internal fun InputField(value: String, onChange: (String) -> Unit, placeholder: String, maxLen: Int) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxLen) onChange(it) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        placeholder = { Text(placeholder, color = RespecColors.TextSub) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = RespecColors.Text,
            unfocusedTextColor = RespecColors.Text,
            focusedContainerColor = RespecColors.Background,
            unfocusedContainerColor = RespecColors.Background,
            focusedBorderColor = RespecColors.Lime,
            unfocusedBorderColor = RespecColors.CardBorder,
            cursorColor = RespecColors.Lime
        )
    )
}

// ---------------- 1. 기본 정보 ----------------

@Composable
private fun BasicInfoStep(
    name: String, onName: (String) -> Unit,
    birth: String?, onBirth: (String) -> Unit,
    gender: String, onGender: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }

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
                    color = if (birth != null) RespecColors.Text else RespecColors.TextSub,
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            FieldLabel("성별")
            ChipRow(GENDERS, gender, onGender)
        }
    }

    if (showPicker) BirthDatePicker(birth, onBirth) { showPicker = false }
}

/** 생년월일 달력 ("YYYY-MM-DD"). 미래 날짜는 고를 수 없음 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthDatePicker(birth: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val utc = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
    val initial = birth?.let { runCatching { utc.parse(it)?.time }.getOrNull() } ?: utc.parse("2000-01-01")!!.time
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    if (ms <= System.currentTimeMillis()) onPick(utc.format(Date(ms)))
                }
                onDismiss()
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    ) {
        DatePicker(state = state)
    }
}

// ---------------- 2. 페르소나 ----------------

@Composable
private fun PersonaStep(
    callName: String, onCallName: (String) -> Unit,
    tone: String, onTone: (String) -> Unit,
    personality: String, onPersonality: (String) -> Unit,
    intensity: String, onIntensity: (String) -> Unit,
    autoIntensity: Boolean, onAutoIntensity: (Boolean) -> Unit,
    voice: String, onVoice: (String) -> Unit,
    genre: String, onGenre: (String) -> Unit
) {
    val line = previewLine(tone, personality, intensity)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepTitle("페르소나를 설정해 주세요")
        FormCard {
            FieldLabel("호칭")
            InputField(callName, onCallName, "AI가 나를 부를 이름", 30)
            FieldLabel("어조")
            ChipRow(TONES, tone, onTone)
            FieldLabel("성격")
            ChipRow(PERSONALITIES, personality, onPersonality)
            FieldLabel("강도")
            ChipRow(INTENSITIES, intensity, onIntensity)
            ToggleRow("자동 강도 올리기", AUTO_INTENSITY_DESC, autoIntensity, onAutoIntensity)

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
                    Text("“$line”", color = RespecColors.Text, fontSize = 15.sp)
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
internal fun VoicePreview(voice: String, line: String) {
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

// ---------------- 3. 아침 콘텐츠 선택 ----------------

@Composable
private fun ContentStep(
    selected: String?, onSelect: (String) -> Unit,
    englishLevel: String, onEnglishLevel: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            StepTitle("모닝콜 끝에\n무엇을 함께 할까요?", "브리핑이 끝나면 넥스티가 하나를 같이 해요.")
        }
        CONTENTS.forEach { c ->
            val on = c.code == selected
            val shape = RoundedCornerShape(22.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (on) RespecColors.Lime.copy(alpha = 0.15f) else RespecColors.Card)
                    .border(if (on) 2.dp else 1.dp, if (on) RespecColors.Lime else RespecColors.CardBorder, shape)
                    .clickable { onSelect(c.code) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) RespecColors.Lime else RespecColors.Background),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(c.icon, null, tint = if (on) RespecColors.OnAccent else RespecColors.AccentText, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.label, color = RespecColors.Text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(2.dp))
                    Text(c.desc, color = RespecColors.TextSub, fontSize = 13.sp, lineHeight = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                // 선택: 체크가 든 원 / 나머지: 빈 원
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (on) RespecColors.Text else Color.Transparent)
                        .border(1.5.dp, if (on) RespecColors.Text else RespecColors.TextSub, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (on) Icon(Icons.Filled.Check, "선택됨", tint = RespecColors.Background, modifier = Modifier.size(16.dp))
                }
            }
        }
        // 영어를 고르면 수준도 고름
        if (selected == "LANGUAGE") {
            FormCard {
                FieldLabel("영어 수준")
                ChipRow(ENGLISH_LEVELS, englishLevel, onEnglishLevel)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "나중에 알람 설정에서 언제든 바꿀 수 있어요.",
            color = RespecColors.TextSub,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------- 4. 확인 ----------------

@Composable
private fun SummaryStep(
    callName: String, tone: String, personality: String, intensity: String, autoIntensity: Boolean,
    voice: String, genre: String,
    contentType: String?, englishLevel: String,
    error: String?,
    onEdit: (Int) -> Unit
) {
    val content = CONTENTS.firstOrNull { it.code == contentType }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepTitle("이제, 시작할 준비가 되었어요!", "지금까지 설정한 내용을 확인하고\n내일 아침부터 넥스티와 함께 시작해 보세요.")
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(RespecColors.Card)
                .border(1.dp, RespecColors.CardBorder, shape)
        ) {
            SummaryRow(Icons.Filled.PersonOutline, "호칭", callName) { onEdit(1) }
            SummaryRow(
                Icons.Filled.ChatBubbleOutline, "성격",
                "${labelOf(PERSONALITIES, personality)} · ${labelOf(INTENSITIES, intensity)}" +
                    if (autoIntensity) " · 자동 강도 올리기" else "",
                badge = labelOf(TONES, tone)
            ) { onEdit(1) }
            SummaryRow(Icons.Filled.GraphicEq, "목소리", labelOf(VOICES, voice)) { onEdit(1) }
            SummaryRow(Icons.Filled.MusicNote, "음악 장르", labelOf(GENRES, genre)) { onEdit(1) }
            val contentText = when {
                content == null -> "-"
                content.code == "LANGUAGE" -> "${content.label} · ${labelOf(ENGLISH_LEVELS, englishLevel)}"
                else -> content.label
            }
            SummaryRow(Icons.Filled.ChecklistRtl, "아침 콘텐츠", contentText, last = true) { onEdit(2) }
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
            Icon(icon, null, tint = RespecColors.AccentText, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = RespecColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(value, color = RespecColors.TextSub, fontSize = 13.sp)
            }
            if (badge != null) {
                Text(
                    badge,
                    color = RespecColors.AccentText,
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
