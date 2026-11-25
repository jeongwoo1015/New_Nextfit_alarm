package com.example.respeclive.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.respeclive.BuildConfig
import com.example.respeclive.data.ApiConfig
import com.example.respeclive.data.ServerAuth
import com.example.respeclive.ui.common.FitText
import com.example.respeclive.ui.nexty.NextyCharacter
import com.example.respeclive.ui.nexty.NextyMotion
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.launch

private const val MIN_PASSWORD = 6   // Supabase Auth 기본 최소 길이
private val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/**
 * 로그인 / 회원가입 화면 (Supabase Auth 이메일·비밀번호).
 * 성공하면 ServerAuth.loggedIn이 true가 되어 AppNavHost가 온보딩 또는 메인으로 넘김.
 * 디버그 빌드에서는 local.properties의 테스트 계정(SUPABASE_DEV_*)을 미리 채워 둠
 */
@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var signUp by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf(if (BuildConfig.DEBUG) ApiConfig.DEV_EMAIL else "") }
    var password by rememberSaveable { mutableStateOf(if (BuildConfig.DEBUG) ApiConfig.DEV_PASSWORD else "") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val emailOk = EMAIL_RE.matches(email.trim())
    val passwordOk = password.length >= MIN_PASSWORD
    val confirmOk = !signUp || confirm == password
    val canSubmit = emailOk && passwordOk && confirmOk && !busy

    // 입력 중에 보이는 안내 (값을 넣기 시작한 칸만)
    val emailHint = if (email.isNotBlank() && !emailOk) "이메일 주소 형식을 확인해 주세요" else null
    val passwordHint = if (signUp && password.isNotEmpty() && !passwordOk) "비밀번호는 ${MIN_PASSWORD}자 이상이에요" else null
    val confirmHint = if (signUp && confirm.isNotEmpty() && !confirmOk) "비밀번호가 서로 달라요" else null

    fun submit() {
        if (!canSubmit) return
        focus.clearFocus()
        busy = true
        error = null
        notice = null
        scope.launch {
            val mail = email.trim()
            if (signUp) {
                when (val r = ServerAuth.signUp(mail, password)) {
                    ServerAuth.SignUpResult.Done -> Unit   // 로그인 상태가 바뀌어 화면이 넘어감
                    ServerAuth.SignUpResult.NeedsConfirm -> {
                        notice = "$mail 로 인증 메일을 보냈어요. 메일의 링크를 누른 뒤 로그인해 주세요"
                        signUp = false
                        confirm = ""
                    }
                    is ServerAuth.SignUpResult.Failed -> error = r.message
                }
            } else {
                error = ServerAuth.signIn(mail, password)
            }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        NextyCharacter(NextyMotion.HELLO,Modifier.size(160.dp))
        Spacer(Modifier.height(8.dp))
        Text("Re:Spec", color = RespecColors.Text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (signUp) "계정을 만들고 넥스티와 아침을 시작해요" else "넥스티가 기다리고 있었어요!",
            color = RespecColors.TextSub,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        ModeTabs(signUp) { toSignUp ->
            if (toSignUp != signUp) {
                signUp = toSignUp
                error = null
                notice = null
                confirm = ""
            }
        }
        Spacer(Modifier.height(16.dp))

        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(RespecColors.Card)
                .border(1.dp, RespecColors.CardBorder, shape)
                .padding(20.dp)
        ) {
            FieldLabel("이메일")
            AuthField(
                value = email,
                onChange = { email = it.trim(); error = null },
                placeholder = "example@email.com",
                hint = emailHint,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                actions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel("비밀번호")
            AuthField(
                value = password,
                onChange = { password = it; error = null },
                placeholder = if (signUp) "${MIN_PASSWORD}자 이상" else "비밀번호",
                hint = passwordHint,
                password = true,
                showPassword = showPassword,
                onTogglePassword = { showPassword = !showPassword },
                keyboard = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (signUp) ImeAction.Next else ImeAction.Done
                ),
                actions = KeyboardActions(
                    onNext = { focus.moveFocus(FocusDirection.Down) },
                    onDone = { submit() }
                )
            )
            if (signUp) {
                Spacer(Modifier.height(12.dp))
                FieldLabel("비밀번호 확인")
                AuthField(
                    value = confirm,
                    onChange = { confirm = it; error = null },
                    placeholder = "비밀번호를 한 번 더 입력해 주세요",
                    hint = confirmHint,
                    password = true,
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword },
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    actions = KeyboardActions(onDone = { submit() })
                )
            }
        }

        error?.let { Message(it, RespecColors.Danger) }
        notice?.let { Message(it, RespecColors.Mint) }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { submit() },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = RespecColors.Button,
                contentColor = RespecColors.OnButton,
                disabledContainerColor = RespecColors.Disabled,
                disabledContentColor = RespecColors.TextSub
            )
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), color = RespecColors.OnButton, strokeWidth = 2.dp)
            } else {
                FitText(
                    if (signUp) "가입하기" else "로그인",
                    color = if (canSubmit) RespecColors.OnButton else RespecColors.TextSub,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (signUp) "이미 계정이 있나요?" else "처음이신가요?", color = RespecColors.TextSub, fontSize = 14.sp)
            Text(
                if (signUp) "로그인" else "회원가입",
                color = RespecColors.AccentText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !busy) {
                        signUp = !signUp
                        error = null
                        notice = null
                        confirm = ""
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 로그인 / 회원가입 전환 (알약 모양 두 칸) */
@Composable
private fun ModeTabs(signUp: Boolean, onSelect: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.PurpleDark)
            .padding(4.dp)
    ) {
        listOf(false to "로그인", true to "회원가입").forEach { (mode, label) ->
            val selected = mode == signUp
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(shape)
                    .background(if (selected) RespecColors.Card else RespecColors.PurpleDark)
                    .clickable { onSelect(mode) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (selected) RespecColors.AccentText else RespecColors.TextSub,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        color = RespecColors.Text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

@Composable
private fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    hint: String?,
    keyboard: KeyboardOptions,
    actions: KeyboardActions,
    password: Boolean = false,
    showPassword: Boolean = false,
    onTogglePassword: () -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 100) onChange(it) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = hint != null,
        shape = RoundedCornerShape(16.dp),
        placeholder = { Text(placeholder, color = RespecColors.TextSub) },
        visualTransformation = if (password && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboard,
        keyboardActions = actions,
        trailingIcon = if (password) {
            {
                IconButton(onClick = onTogglePassword) {
                    Icon(
                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showPassword) "비밀번호 숨기기" else "비밀번호 보기",
                        tint = RespecColors.TextSub
                    )
                }
            }
        } else null,
        supportingText = hint?.let { { Text(it, fontSize = 12.sp) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = RespecColors.Text,
            unfocusedTextColor = RespecColors.Text,
            focusedContainerColor = RespecColors.Background,
            unfocusedContainerColor = RespecColors.Background,
            focusedBorderColor = RespecColors.Lime,
            unfocusedBorderColor = RespecColors.CardBorder,
            errorBorderColor = RespecColors.Danger,
            errorContainerColor = RespecColors.Background,
            errorTextColor = RespecColors.Text,
            errorSupportingTextColor = RespecColors.Danger,
            errorCursorColor = RespecColors.Lime,
            cursorColor = RespecColors.Lime
        )
    )
}

@Composable
private fun Message(text: String, color: androidx.compose.ui.graphics.Color) {
    Spacer(Modifier.height(12.dp))
    Text(text, color = color, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}
