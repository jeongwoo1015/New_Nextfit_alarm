package com.example.respeclive.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.respeclive.BuildConfig
import com.example.respeclive.ui.common.SubScreenHeaderTop
import com.example.respeclive.ui.theme.RespecColors

// 아직 정해지지 않은 연락처·문서 주소. 비어 있으면 누를 때 "준비 중이에요" 안내
private const val FEEDBACK_EMAIL = ""
private const val PRIVACY_URL = ""
private const val TERMS_URL = ""

/** 권한 한 줄의 현재 상태 (화면에 돌아올 때마다 다시 확인) */
private data class PermissionState(
    val notifications: Boolean,
    val fullScreen: Boolean,
    val overlay: Boolean,
    val battery: Boolean,
    val mic: Boolean
)

private fun readPermissions(context: Context): PermissionState {
    val nm = context.getSystemService(NotificationManager::class.java)
    val pm = context.getSystemService(PowerManager::class.java)
    return PermissionState(
        notifications = nm.areNotificationsEnabled(),
        fullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
        overlay = Settings.canDrawOverlays(context),
        battery = pm.isIgnoringBatteryOptimizations(context.packageName),
        mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    )
}

/**
 * 설정: 언어 / 권한(알림, 전체 화면 알람, 다른 앱 위에 표시, 배터리 최적화 제외, 마이크) / 의견 / 정보.
 * 권한은 허용돼 있으면 '허용됨', 아니면 바로가기 표시이고, 누르면 그 권한의 시스템 설정 화면을 엶
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var perms by remember { mutableStateOf(readPermissions(context)) }

    // 시스템 설정에서 바꾸고 돌아오면 바로 반영
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) perms = readPermissions(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val pkg = Uri.parse("package:${context.packageName}")
    fun open(intent: Intent, fallback: Intent? = null) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            fallback?.let { runCatching { context.startActivity(it) } }
        }
    }
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    fun openUrl(url: String) {
        if (url.isBlank()) Toast.makeText(context, "준비 중이에요", Toast.LENGTH_SHORT).show()
        else open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RespecColors.Background)
            // 상태 표시줄 여백은 Scaffold가 이미 줌. 헤더 위 여백은 SubScreenHeaderTop으로 알람 설정·일기 상세와 같게
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // 헤더: 보조 포인트(연한 오렌지) 원 뒤로 가기 + 제목
        // 첫 구역 제목의 위 여백(16dp)과 합쳐 알람 설정 화면처럼 헤더 아래 20dp
        Row(Modifier.padding(top = SubScreenHeaderTop, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(RespecColors.PurpleDark)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = RespecColors.AccentText)
            }
            Spacer(Modifier.width(14.dp))
            Text("설정", color = RespecColors.AccentText, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        }

        Section("언어") {
            SettingRow("언어 설정", trailing = { Text("한국어", color = RespecColors.TextSub, fontSize = 14.sp) })
        }

        Section("권한") {
            PermissionRow("알림 허용", "정해진 시각에 알람을 울리기 위해 필요해요", perms.notifications) {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName), appDetails)
            }
            Divider()
            PermissionRow("전체 화면 알람", "잠긴 화면에서도 알람을 띄우기 위해 필요해요", perms.fullScreen) {
                if (Build.VERSION.SDK_INT >= 34) open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg), appDetails)
                else open(appDetails)
            }
            Divider()
            PermissionRow("다른 앱 위에 표시", "다른 앱을 쓰는 중에도 알람을 바로 띄워요", perms.overlay) {
                open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg), appDetails)
            }
            Divider()
            PermissionRow("배터리 최적화 제외", "알람이 늦거나 빠지지 않도록 해요", perms.battery) {
                open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails)
            }
            Divider()
            PermissionRow("마이크", "넥스티와 대화할 때 목소리를 듣기 위해 필요해요", perms.mic) {
                open(appDetails)
            }
        }

        Section("의견") {
            SettingRow("의견 보내기", "사용하며 좋은 기능이나 개선점을 알려 주세요", trailing = { Chevron() }) {
                if (FEEDBACK_EMAIL.isBlank()) Toast.makeText(context, "준비 중이에요", Toast.LENGTH_SHORT).show()
                else open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_EMAIL")).putExtra(Intent.EXTRA_SUBJECT, "넥스티 의견"))
            }
            Divider()
            SettingRow("리뷰 남기기", trailing = { LinkIcon() }) {
                open(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}")),
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"))
                )
            }
        }

        Section("정보") {
            SettingRow("앱 정보", trailing = { Text("버전 ${BuildConfig.VERSION_NAME}", color = RespecColors.TextSub, fontSize = 14.sp) })
            Divider()
            SettingRow("개인정보처리방침", trailing = { LinkIcon() }) { openUrl(PRIVACY_URL) }
            Divider()
            SettingRow("이용약관", trailing = { LinkIcon() }) { openUrl(TERMS_URL) }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 작은 회색 제목 + 흰 카드 */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        color = RespecColors.TextSub,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp)
    )
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RespecColors.Card)
            .border(1.dp, RespecColors.CardBorder, shape),
        content = content
    )
}

/** 한 줄: 제목 · 설명(있으면) / 오른쪽 표시. onClick이 없으면 누를 수 없음 */
@Composable
private fun SettingRow(
    title: String,
    description: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = RespecColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            description?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = RespecColors.TextSub, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** 권한 줄: 허용돼 있으면 '허용됨' 배지, 아니면 바로가기 아이콘 */
@Composable
private fun PermissionRow(title: String, description: String, granted: Boolean, onClick: () -> Unit) {
    SettingRow(title, description, trailing = { if (granted) GrantedBadge() else LinkIcon() }, onClick = onClick)
}

@Composable
private fun GrantedBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(RespecColors.PurpleDark)   // 보조 포인트: 알약 배경
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text("허용됨", color = RespecColors.AccentText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LinkIcon() {
    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = RespecColors.TextSub, modifier = Modifier.size(18.dp))
}

@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = RespecColors.TextSub, modifier = Modifier.size(22.dp))
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(horizontal = 18.dp), thickness = 1.dp, color = RespecColors.CardBorder)
}
