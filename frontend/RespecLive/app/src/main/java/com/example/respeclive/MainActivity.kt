package com.example.respeclive

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.respeclive.alarm.AlarmExtras
import com.example.respeclive.alarm.AlarmLaunch
import com.example.respeclive.alarm.AlarmNotifier
import com.example.respeclive.alarm.AlarmScheduler
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.navigation.AppNavHost
import com.example.respeclive.ui.theme.AppTheme
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.delay

/** 기기 글꼴 크기 설정을 이 배율까지만 반영 (더 키우면 고정 크기 화면에서 글자가 넘침) */
private const val MAX_FONT_SCALE = 1.2f

class MainActivity : ComponentActivity() {

    private var launchRequest by mutableStateOf<AlarmLaunch?>(null)
    private var askedFullScreenIntent = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.init(applicationContext)
        applySystemBars(AppTheme.isDark)

        AlarmRepository.init(applicationContext)
        AlarmNotifier.ensureChannel(this)
        AlarmScheduler.rescheduleAll(this)   // 앱을 열 때마다 알람 등록 상태를 최신으로 맞춤
        requestNotificationPermission()
        handleAlarmIntent(intent)

        setContent {
            // 기기 글꼴 크기를 아주 크게 해도 통화 화면처럼 자리가 정해진 배치가 깨지지 않도록 이 배율까지만 키움
            val density = LocalDensity.current
            val dark = AppTheme.isDark

            // 자동 테마: 해가 뜨고 지는 시각에 맞춰 다시 확인
            LaunchedEffect(AppTheme.mode) {
                while (true) delay(AppTheme.refresh(applicationContext))
            }
            LaunchedEffect(dark) { applySystemBars(dark) }

            CompositionLocalProvider(
                LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(MAX_FONT_SCALE))
            ) {
                val scheme = if (dark) {
                    darkColorScheme(
                        primary = RespecColors.Lime,
                        onPrimary = RespecColors.OnAccent,
                        background = RespecColors.Background,
                        surface = RespecColors.Card,
                        surfaceContainerHigh = RespecColors.Card,
                        onBackground = RespecColors.Text,
                        onSurface = RespecColors.Text
                    )
                } else {
                    lightColorScheme(
                        primary = RespecColors.Lime,
                        onPrimary = RespecColors.OnAccent,
                        background = RespecColors.Background,
                        surface = RespecColors.Card,
                        surfaceContainerHigh = RespecColors.Card,
                        onBackground = RespecColors.Text,
                        onSurface = RespecColors.Text
                    )
                }
                MaterialTheme(colorScheme = scheme) {
                    AppNavHost(
                        launch = launchRequest,
                        onLaunchHandled = { launchRequest = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlarmIntent(intent)
    }

    /** 상태바·내비게이션바 아이콘 색을 테마에 맞춤 */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onResume() {
        super.onResume()
        AppTheme.refresh(applicationContext)   // 앱을 다시 열었을 때 그사이 해가 뜨거나 졌을 수 있음
        if (Build.VERSION.SDK_INT >= 34 && !askedFullScreenIntent) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                askedFullScreenIntent = true
                Toast.makeText(this, "잠금화면에서 알람이 뜨도록 '전체 화면 알림'을 허용해 주세요", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        setLockScreenMode(false)
    }

    private fun handleAlarmIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AlarmExtras.FROM_ALARM, false) != true) return
        intent.removeExtra(AlarmExtras.FROM_ALARM)

        setLockScreenMode(true)

        val id = intent.getIntExtra(AlarmExtras.ID, -1)
        val type = runCatching {
            AlarmType.valueOf(intent.getStringExtra(AlarmExtras.TYPE) ?: "")
        }.getOrDefault(AlarmType.WAKE)
        val label = intent.getStringExtra(AlarmExtras.LABEL) ?: ""

        NotificationManagerCompat.from(this).cancel(id)
        launchRequest = AlarmLaunch(route = type.route, label = label, alarmId = id)
    }

    private fun setLockScreenMode(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
