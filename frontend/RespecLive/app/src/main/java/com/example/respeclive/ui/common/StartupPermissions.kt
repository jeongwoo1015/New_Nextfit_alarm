package com.example.respeclive.ui.common

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneLocked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.respeclive.ui.theme.RespecColors

/**
 * 앱에서 쓰는 권한 하나. 시스템 권한 창으로 묻는 권한(runtime)과 설정 화면에서 켜야 하는 권한(special)이 있음.
 * required: 없으면 모닝콜·굿나잇콜을 제대로 쓸 수 없는 권한 (안내 화면의 '필수 접근 권한')
 */
private enum class AppPermission(
    val title: String,
    val why: String,
    val deniedEffect: String,
    val icon: ImageVector,
    val required: Boolean,
    val runtime: String? = null
) {
    NOTIFICATION(
        "알림",
        "모닝콜과 굿나잇콜이 울리고, 굿나잇콜 미리 알림과 수면 음악 재생 알림을 보여 줘요.",
        "모닝콜과 굿나잇콜이 울리지 않아요. 굿나잇콜 팝업과 수면 음악 재생 알림도 뜨지 않아요.",
        Icons.Filled.Notifications, required = true,
        runtime = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
    ),
    MIC(
        "마이크",
        "넥스티와 전화로 대화하고, 내일 아침의 나에게 들려줄 한마디를 녹음해요.",
        "넥스티에게 말로 대답할 수 없어요. 통화를 받을 때 다시 물어봐요.",
        Icons.Filled.Mic, required = true, runtime = Manifest.permission.RECORD_AUDIO
    ),
    CALENDAR(
        "캘린더",
        "아침 브리핑과 굿나잇콜에서 오늘과 내일 일정을 이야기해요.",
        "브리핑과 굿나잇콜에서 일정 이야기가 빠져요.",
        Icons.Filled.CalendarMonth, required = false, runtime = Manifest.permission.READ_CALENDAR
    ),
    LOCATION(
        "위치 (대략적인 위치)",
        "아침 브리핑에서 지금 있는 곳의 날씨를 알려 줘요.",
        "브리핑의 날씨가 기본 지역 기준으로 나와요.",
        Icons.Filled.LocationOn, required = false, runtime = Manifest.permission.ACCESS_COARSE_LOCATION
    ),
    FULL_SCREEN(
        "전체 화면 알림",
        "화면이 꺼져 있거나 잠겨 있어도 모닝콜이 울릴 때 넥스티 전화 화면이 바로 열려요.",
        "잠금 화면에서는 모닝콜이 알림으로만 와요. 알림을 눌러야 받을 수 있어서 깊이 잠들었을 때 놓치기 쉬워요.",
        Icons.Filled.PhoneLocked, required = false
    ),
    OVERLAY(
        "다른 앱 위에 표시",
        "다른 앱을 쓰는 중에 모닝콜이 울려도 넥스티 전화 화면이 바로 열려요.",
        "폰을 쓰는 중에 모닝콜이 울리면 화면 위쪽 알림으로만 와요. 알림을 눌러야 받을 수 있어요.",
        Icons.Filled.Layers, required = false
    );

    /** 이 기기에서 물어볼 필요가 있는 권한인지 (안드로이드 버전에 따라 없는 권한은 빼고 보여 줌) */
    val applies: Boolean
        get() = when (this) {
            NOTIFICATION -> Build.VERSION.SDK_INT >= 33
            FULL_SCREEN -> Build.VERSION.SDK_INT >= 34
            else -> true
        }

    fun granted(context: Context): Boolean = when (this) {
        FULL_SCREEN -> Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        OVERLAY -> Settings.canDrawOverlays(context)
        else -> runtime == null || ContextCompat.checkSelfPermission(context, runtime) == PackageManager.PERMISSION_GRANTED
    }

    /** 설정 화면에서 켜야 하는 권한의 설정 화면 */
    fun settingsIntent(context: Context): Intent {
        val pkg = Uri.parse("package:${context.packageName}")
        return when (this) {
            FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
    }
}

/** 알림 권한을 거절했을 때의 영향 */
const val NOTIFICATION_DENIED_EFFECT =
    "알림을 허용하지 않으면 모닝콜과 굿나잇콜이 울리지 않아요. 굿나잇콜 팝업과 수면 음악 재생 알림도 뜨지 않아요."

private const val PREFS = "startup_permissions"
private const val KEY_GUIDE_DONE = "guide_done"

private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** 앱 처음 시작 때의 권한 안내를 이미 마쳤는지 (마친 뒤에는 각 화면이 필요할 때만 다시 물음) */
fun permissionGuideDone(context: Context): Boolean = prefs(context).getBoolean(KEY_GUIDE_DONE, false)

private enum class Step { Guide, Runtime, Special, Summary }

/**
 * 앱 처음 시작 때의 권한 안내 화면. 앱이 쓰는 권한을 필수·선택으로 한 화면에 보여 주고,
 * [허용]을 누르면 ① 시스템 권한 창(알림·마이크·캘린더·위치)을 차례대로 띄운 뒤 ② 설정 화면에서 켜야 하는 권한
 * (전체 화면 알림·다른 앱 위에 표시)의 설정 화면을 하나씩 열고, ③ 허용하지 않은 권한이 있으면 영향을 한 번에 알려 줌.
 * 한 번 마치면(닫기 포함) 다시 띄우지 않음. 이후에는 통화 화면 등이 필요할 때 그 권한만 다시 물음.
 * enabled가 false인 동안(알람으로 열린 수신 화면 등)은 띄우지 않음.
 * notificationDenied: 안내를 마친 뒤 다시 연 알림 권한 창에서 거절했을 때 true → 영향 안내를 한 번 보여 줌
 */
@Composable
fun StartupPermissions(enabled: Boolean, notificationDenied: Boolean, onNotificationExplained: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(if (permissionGuideDone(context)) null else Step.Guide) }
    var specialQueue by remember { mutableStateOf<List<AppPermission>>(emptyList()) }
    var waitingSettings by remember { mutableStateOf<AppPermission?>(null) }   // 설정 화면에 다녀오는 중
    var denied by remember { mutableStateOf<List<AppPermission>>(emptyList()) }

    fun finish() {
        prefs(context).edit().putBoolean(KEY_GUIDE_DONE, true).apply()
        denied = AppPermission.entries.filter { it.applies && !it.granted(context) }
        step = if (denied.isEmpty()) null else Step.Summary
    }

    // 설정 화면에서 켜야 하는 권한을 하나씩 엶. 다 돌면 마무리
    fun nextSpecial() {
        val p = specialQueue.firstOrNull()
        if (p == null) {
            finish()
            return
        }
        specialQueue = specialQueue.drop(1)
        waitingSettings = p
        try {
            context.startActivity(p.settingsIntent(context))
        } catch (_: ActivityNotFoundException) {
            waitingSettings = null
            nextSpecial()
        }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        step = Step.Special
        specialQueue = AppPermission.entries.filter { it.runtime == null && it.applies && !it.granted(context) }
        nextSpecial()
    }

    fun start() {
        val missing = AppPermission.entries.mapNotNull { p -> p.runtime?.takeIf { p.applies && !p.granted(context) } }
        if (missing.isEmpty()) {
            step = Step.Special
            specialQueue = AppPermission.entries.filter { it.runtime == null && it.applies && !it.granted(context) }
            nextSpecial()
        } else {
            step = Step.Runtime
            runtimeLauncher.launch(missing.toTypedArray())
        }
    }

    // 설정 화면에서 돌아오면 다음 권한으로
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && waitingSettings != null) {
                waitingSettings = null
                nextSpecial()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (!enabled) return

    when (step) {
        // 닫기(X·뒤로)는 '나중에': 안내만 마친 것으로 하고, 권한은 각 화면이 필요할 때 다시 물음
        Step.Guide -> PermissionGuide(onAllow = { start() }, onClose = {
            prefs(context).edit().putBoolean(KEY_GUIDE_DONE, true).apply()
            step = null
        })
        Step.Runtime, Step.Special -> {}     // 시스템 권한 창·설정 화면이 떠 있는 중
        Step.Summary -> InfoDialog(
            "허용하지 않은 권한이 있어요",
            denied.joinToString("\n\n") { "· ${it.title}: ${it.deniedEffect}" } + "\n\n설정 > 권한에서 언제든 켤 수 있어요."
        ) { step = null }
        null -> if (notificationDenied) {
            InfoDialog("알림이 꺼져 있어요", NOTIFICATION_DENIED_EFFECT + "\n\n설정 > 권한에서 언제든 켤 수 있어요.") {
                onNotificationExplained()
            }
        }
    }
}

/** 권한 안내 화면 (전체 화면). 필수 → 선택 순서로 권한마다 아이콘·이름·쓰는 곳을 보여 줌 */
@Composable
private fun PermissionGuide(onAllow: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val items = AppPermission.entries.filter { it.applies }
    // Surface가 터치를 막아 아래 화면(시작·온보딩)이 눌리지 않음
    Surface(Modifier.fillMaxSize(), color = RespecColors.Background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, "나중에", tint = RespecColors.TextSub)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    "서비스 이용을 위한\n앱 접근 권한을\n허용해 주세요.",
                    color = RespecColors.Text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp
                )
                Spacer(Modifier.height(28.dp))
                GuideSection("필수 접근 권한", "모닝콜과 굿나잇콜을 쓰려면 꼭 필요한 권한이에요.", items.filter { it.required })
                Spacer(Modifier.height(28.dp))
                GuideSection(
                    "선택 접근 권한",
                    "허용하지 않아도 앱을 쓸 수 있지만, 일부 기능이 빠지거나 불편할 수 있어요.",
                    items.filter { !it.required }
                )
                Spacer(Modifier.height(24.dp))
            }
            Button(
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RespecColors.Button, contentColor = RespecColors.OnButton)
            ) {
                Text("허용", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun GuideSection(title: String, desc: String, items: List<AppPermission>) {
    Text(title, color = RespecColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(desc, color = RespecColors.TextSub, fontSize = 13.sp, lineHeight = 19.sp)
    items.forEach { p ->
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(RespecColors.Card),
                contentAlignment = Alignment.Center
            ) {
                Icon(p.icon, null, tint = RespecColors.AccentText, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, color = RespecColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(p.why, color = RespecColors.TextSub, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun InfoDialog(title: String, text: String, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        containerColor = RespecColors.Card,
        title = { Text(title, color = RespecColors.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = { Text(text, color = RespecColors.TextSub, fontSize = 15.sp, lineHeight = 22.sp) },
        confirmButton = {
            TextButton(onClick = onOk) { Text("확인", color = RespecColors.AccentText, fontWeight = FontWeight.Bold) }
        }
    )
}
