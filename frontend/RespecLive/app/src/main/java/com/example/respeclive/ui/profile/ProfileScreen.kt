package com.example.respeclive.ui.profile

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.data.ApiClient
import com.example.respeclive.data.RespecApi
import com.example.respeclive.ui.onboarding.BirthDatePicker
import com.example.respeclive.ui.onboarding.ChipRow
import com.example.respeclive.ui.onboarding.Choice
import com.example.respeclive.ui.onboarding.GENDERS
import com.example.respeclive.ui.onboarding.GENRES
import com.example.respeclive.ui.onboarding.InputField
import com.example.respeclive.ui.onboarding.AUTO_INTENSITY_DESC
import com.example.respeclive.ui.onboarding.DEFAULT_ENGLISH_LEVEL
import com.example.respeclive.ui.onboarding.DEFAULT_INTENSITY
import com.example.respeclive.ui.onboarding.DEFAULT_PERSONALITY
import com.example.respeclive.ui.onboarding.ENGLISH_LEVELS
import com.example.respeclive.ui.onboarding.INTENSITIES
import com.example.respeclive.ui.onboarding.ToggleRow
import com.example.respeclive.ui.onboarding.PERSONALITIES
import com.example.respeclive.ui.onboarding.TONES
import com.example.respeclive.ui.onboarding.VOICES
import com.example.respeclive.ui.onboarding.VoicePreview
import com.example.respeclive.ui.onboarding.labelOf
import com.example.respeclive.ui.onboarding.previewLine
import com.example.respeclive.ui.theme.AppTheme
import com.example.respeclive.ui.theme.RespecColors
import com.example.respeclive.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val FIELD_BIRTH = "birth_date"
private const val FIELD_TONE = "tone"
private const val FIELD_PERSONALITY = "personality"
private const val FIELD_INTENSITY = "intensity"
private const val FIELD_AUTO_INTENSITY = "auto_intensity"
private const val FIELD_ENGLISH_LEVEL = "english_level"
private const val FIELD_VOICE = "voice_type"
private const val FIELD_THEME = "theme"

/** 기본 정보(PATCH /users/me)에 저장하는 항목. 나머지는 페르소나(PATCH /settings) */
private val USER_FIELDS = setOf("user_name", FIELD_BIRTH, "gender")

/** 자정부터 지난 분 → "07:03" */
private fun clockText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

/** 항목 하나를 고치는 창: choices가 있으면 선택 칩, 없으면 글자 입력 */
private class EditField(
    val key: String,
    val title: String,
    val current: String,
    val choices: List<Choice>? = null,
    val maxLen: Int = 0
)

/** 내 정보: 온보딩에서 설정한 값을 보여 주고, 항목을 누르면 그 항목만 고쳐 저장함 */
@Composable
fun ProfileScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<JSONObject?>(null) }
    var setting by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<EditField?>(null) }
    var pickingBirth by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        error = null
        val profile = RespecApi.profile()
        if (profile == null) {
            error = ApiClient.lastError ?: "내 정보를 불러오지 못했어요"
        } else {
            user = profile.optJSONObject("user")
            setting = profile.optJSONObject("setting")
        }
    }

    // 서버가 돌려준 값으로 화면을 바꿈. 실패하면 false. 강도는 숫자, 자동 강도 올리기는 참·거짓으로 보냄
    suspend fun save(key: String, value: String): Boolean {
        val body = when (key) {
            FIELD_INTENSITY -> JSONObject().put(key, value.toInt())
            FIELD_AUTO_INTENSITY -> JSONObject().put(key, value.toBoolean())
            else -> JSONObject().put(key, value)
        }
        return if (key in USER_FIELDS) RespecApi.updateUser(body)?.also { user = it } != null
        else RespecApi.updateSetting(body)?.also { setting = it } != null
    }

    val shownUser = user
    val shownSetting = setting
    when {
        shownUser != null && shownSetting != null -> {
            val tone = shownSetting.optString(FIELD_TONE)
            val personality = shownSetting.optString(FIELD_PERSONALITY).ifBlank { DEFAULT_PERSONALITY }
            val intensity = shownSetting.optInt(FIELD_INTENSITY, DEFAULT_INTENSITY).toString()
            val autoIntensity = shownSetting.optBoolean(FIELD_AUTO_INTENSITY, true)
            val englishLevel = shownSetting.optString(FIELD_ENGLISH_LEVEL).ifBlank { DEFAULT_ENGLISH_LEVEL }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                SectionTitle("기본 정보")
                InfoCard {
                    val name = shownUser.optString("user_name")
                    val birth = shownUser.optString(FIELD_BIRTH)
                    val gender = shownUser.optString("gender")
                    InfoRow("이름", name, first = true) { editing = EditField("user_name", "이름", name, maxLen = 50) }
                    InfoRow("생년월일", birth.replace("-", ". ")) { pickingBirth = true }
                    InfoRow("성별", labelOf(GENDERS, gender)) { editing = EditField("gender", "성별", gender, GENDERS) }
                }

                SectionTitle("페르소나")
                InfoCard {
                    val callName = shownSetting.optString("user_call_name")
                    val voice = shownSetting.optString(FIELD_VOICE)
                    val genre = shownSetting.optString("music_genre")
                    InfoRow("호칭", callName, first = true) { editing = EditField("user_call_name", "호칭", callName, maxLen = 30) }
                    InfoRow("어조", labelOf(TONES, tone)) { editing = EditField(FIELD_TONE, "어조", tone, TONES) }
                    InfoRow("성격", labelOf(PERSONALITIES, personality)) {
                        editing = EditField(FIELD_PERSONALITY, "성격", personality, PERSONALITIES)
                    }
                    InfoRow("강도", labelOf(INTENSITIES, intensity)) {
                        editing = EditField(FIELD_INTENSITY, "강도", intensity, INTENSITIES)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = RespecColors.CardBorder)
                    // 누르면 바로 저장 (실패하면 알림)
                    Box(Modifier.padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                        ToggleRow("자동 강도 올리기", AUTO_INTENSITY_DESC, autoIntensity) { on ->
                            scope.launch {
                                if (!save(FIELD_AUTO_INTENSITY, on.toString())) {
                                    Toast.makeText(context, ApiClient.lastError ?: "저장하지 못했어요", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    InfoRow("목소리", labelOf(VOICES, voice)) { editing = EditField(FIELD_VOICE, "목소리", voice, VOICES) }
                    InfoRow("음악 장르", labelOf(GENRES, genre)) { editing = EditField("music_genre", "음악 장르", genre, GENRES) }
                }

                SectionTitle("아침 콘텐츠")
                InfoCard {
                    InfoRow("영어 수준", labelOf(ENGLISH_LEVELS, englishLevel), first = true) {
                        editing = EditField(FIELD_ENGLISH_LEVEL, "영어 수준", englishLevel, ENGLISH_LEVELS)
                    }
                }

                SectionTitle("화면 테마")
                // 화면은 바로 바꾸고 서버에 저장. 저장이 안 되면 기기에는 남기고 알림만 (다음 시작 때 서버 값으로 돌아갈 수 있음)
                ChipRow(ThemeMode.entries.map { Choice(it.name, it.label) }, AppTheme.mode.name) { code ->
                    AppTheme.select(context, ThemeMode.valueOf(code))
                    scope.launch {
                        if (!save(FIELD_THEME, code)) {
                            Toast.makeText(context, ApiClient.lastError ?: "테마를 서버에 저장하지 못했어요", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                if (AppTheme.mode == ThemeMode.AUTO) {
                    val sun = AppTheme.sun
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (sun != null) "해 뜨면 라이트, 해 지면 다크로 바뀌어요 (오늘 ${clockText(sun.riseMinute)} · ${clockText(sun.setMinute)})"
                        else "해가 뜨고 지지 않는 날이라 계절에 맞춰 보여 줘요",
                        color = RespecColors.TextSub,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.height(24.dp))
            }

            editing?.let { field ->
                EditDialog(field, tone, personality, intensity, save = { save(field.key, it) }, onDismiss = { editing = null })
            }
            if (pickingBirth) {
                BirthDatePicker(
                    shownUser.optString(FIELD_BIRTH),
                    onPick = { picked ->
                        scope.launch {
                            if (!save(FIELD_BIRTH, picked)) {
                                Toast.makeText(context, ApiClient.lastError ?: "저장하지 못했어요", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onDismiss = { pickingBirth = false }
                )
            }
        }
        error != null -> Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(error.orEmpty(), color = RespecColors.TextSub, fontSize = 14.sp, textAlign = TextAlign.Center)
            TextButton(onClick = { reload++ }) { Text("다시 불러오기", color = RespecColors.AccentText) }
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = RespecColors.Lime)
        }
    }
}

/** 항목 하나 수정. 어조·성격·강도는 문장 미리보기, 목소리는 미리 듣기를 함께 보여 줌. 저장에 실패하면 창을 닫지 않고 오류를 보여 줌 */
@Composable
private fun EditDialog(
    field: EditField,
    tone: String,
    personality: String,
    intensity: String,
    save: suspend (String) -> Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(field.current) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val line = previewLine(
        if (field.key == FIELD_TONE) value else tone,
        if (field.key == FIELD_PERSONALITY) value else personality,
        if (field.key == FIELD_INTENSITY) value else intensity
    )

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = RespecColors.Card,
        title = { Text(field.title, color = RespecColors.Text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val choices = field.choices
                if (choices != null) {
                    ChipRow(choices, value) { value = it }
                } else {
                    InputField(value, { value = it }, "${field.title}을 입력해 주세요", field.maxLen)
                }
                if (field.key == FIELD_TONE || field.key == FIELD_PERSONALITY || field.key == FIELD_INTENSITY) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("예시", color = RespecColors.TextSub, fontSize = 12.sp)
                        Text("“$line”", color = RespecColors.Text, fontSize = 14.sp)
                    }
                }
                if (field.key == FIELD_VOICE) VoicePreview(value, line)
                error?.let { Text(it, color = RespecColors.Danger, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank() && !saving,
                onClick = {
                    val text = value.trim()
                    if (text == field.current) {
                        onDismiss()
                        return@TextButton
                    }
                    saving = true
                    error = null
                    scope.launch {
                        val ok = save(text)
                        saving = false
                        if (ok) onDismiss() else error = ApiClient.lastError ?: "저장하지 못했어요. 다시 시도해 주세요"
                    }
                }
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = RespecColors.Lime, strokeWidth = 2.dp)
                } else {
                    Text("저장", color = if (value.isNotBlank()) RespecColors.AccentText else RespecColors.TextSub, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("취소", color = RespecColors.TextSub) }
        }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = RespecColors.Text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 10.dp)
    )
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape),
        content = content
    )
}

/** 항목 한 줄: 이름 · 값 · 화살표. 누르면 그 항목을 고침 */
@Composable
private fun InfoRow(label: String, value: String, first: Boolean = false, onClick: () -> Unit) {
    if (!first) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = RespecColors.CardBorder)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 12.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = RespecColors.TextSub, fontSize = 14.sp)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            color = RespecColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = RespecColors.TextSub)
    }
}
