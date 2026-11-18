package com.example.respeclive.ui.common

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.respeclive.ui.theme.RespecColors

/**
 * 앱 시작 때 묻는 시스템 설정 권한 (전체 화면 알람, 다른 앱 위에 표시)과 거절했을 때의 영향 안내.
 * 권한마다 ① 왜 필요한지 묻고 → 설정 화면으로 보냄 ② 허용하지 않고 돌아오거나 "허용 안 함"이면 어떤 영향이 있는지 알려 줌.
 * 한 번 안내를 마친 권한은 다음 시작부터 다시 묻지 않음 (설정 > 권한에서 언제든 켤 수 있음)
 */
private enum class SpecialPermission(
    val title: String,
    val why: String,
    val deniedEffect: String
) {
    FULL_SCREEN(
        "잠금 화면에서 모닝콜 바로 받기",
        "'전체 화면 알림'을 허용하면 화면이 꺼져 있거나 잠겨 있어도 모닝콜이 울릴 때 넥스티 전화 화면이 바로 열려요.",
        "잠금 화면에서는 모닝콜이 알림으로만 와요. 알림을 눌러야 넥스티 전화를 받을 수 있어서, 깊이 잠들었을 때 놓치기 쉬워요."
    ),
    OVERLAY(
        "폰을 쓰는 중에도 모닝콜 바로 받기",
        "'다른 앱 위에 표시'를 허용하면 다른 앱을 쓰는 중에 모닝콜이 울려도 넥스티 전화 화면이 바로 열려요.",
        "폰을 쓰는 중에 모닝콜이 울리면 화면 위쪽 알림으로만 와요. 알림을 눌러야 넥스티 전화를 받을 수 있어요."
    );

    fun granted(context: Context): Boolean = when (this) {
        FULL_SCREEN -> Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        OVERLAY -> Settings.canDrawOverlays(context)
    }

    fun settingsIntent(context: Context): Intent {
        val pkg = Uri.parse("package:${context.packageName}")
        return when (this) {
            FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
        }
    }
}

/** 알림 권한을 거절했을 때의 영향 */
const val NOTIFICATION_DENIED_EFFECT =
    "알림을 허용하지 않으면 모닝콜과 굿나잇콜이 울리지 않아요. 굿나잇콜 팝업과 수면 음악 재생 알림도 뜨지 않아요."

private const val PREFS = "startup_permissions"

private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/**
 * 앱 시작 권한 안내. enabled가 false인 동안(알람으로 열린 수신 화면 등)은 띄우지 않음.
 * notificationDenied: 시스템 알림 권한 창에서 거절했을 때 true → 영향 안내를 한 번 보여 줌
 */
@Composable
fun StartupPermissions(enabled: Boolean, notificationDenied: Boolean, onNotificationExplained: () -> Unit) {
    val context = LocalContext.current
    // 지금 보여 줄 단계: 묻기(asking) 또는 거절 영향 안내(explaining)
    var asking by remember { mutableStateOf<SpecialPermission?>(null) }
    var explaining by remember { mutableStateOf<SpecialPermission?>(null) }
    var waitingSettings by remember { mutableStateOf<SpecialPermission?>(null) }   // 설정 화면에 다녀오는 중

    fun done(p: SpecialPermission) = prefs(context).edit().putBoolean(p.name, true).apply()
    fun next() {
        asking = SpecialPermission.entries.firstOrNull { !it.granted(context) && !prefs(context).getBoolean(it.name, false) }
    }

    // 시작할 때, 그리고 설정 화면에서 돌아올 때 확인
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME || !enabled) return@LifecycleEventObserver
            val back = waitingSettings
            if (back != null) {
                waitingSettings = null
                done(back)
                if (back.granted(context)) next() else explaining = back
            } else if (asking == null && explaining == null) {
                next()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (!enabled) return

    if (notificationDenied) {
        InfoDialog("알림이 꺼져 있어요", NOTIFICATION_DENIED_EFFECT + "\n\n설정 > 권한에서 언제든 켤 수 있어요.") {
            onNotificationExplained()
        }
        return
    }

    explaining?.let { p ->
        InfoDialog("허용하지 않으면", p.deniedEffect + "\n\n설정 > 권한에서 언제든 켤 수 있어요.") {
            explaining = null
            next()
        }
        return
    }

    asking?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = RespecColors.Card,
            title = { Text(p.title, color = RespecColors.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = { Text(p.why, color = RespecColors.TextSub, fontSize = 15.sp, lineHeight = 22.sp) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    waitingSettings = p
                    try {
                        context.startActivity(p.settingsIntent(context))
                    } catch (_: ActivityNotFoundException) {
                        waitingSettings = null
                        done(p)
                        explaining = p
                    }
                }) { Text("허용하러 가기", color = RespecColors.AccentText, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    asking = null
                    done(p)
                    explaining = p
                }) { Text("허용 안 함", color = RespecColors.TextSub) }
            }
        )
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
