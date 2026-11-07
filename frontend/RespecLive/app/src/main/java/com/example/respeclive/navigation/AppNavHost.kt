package com.example.respeclive.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.respeclive.alarm.AlarmLaunch
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType
import com.example.respeclive.data.RespecApi
import com.example.respeclive.ui.alarm.AlarmEditScreen
import com.example.respeclive.ui.home.HomeScreen
import com.example.respeclive.ui.morning.MorningCallScreen
import com.example.respeclive.ui.morning.MorningReportScreen
import com.example.respeclive.ui.nexty.NextyDebugScreen
import com.example.respeclive.ui.night.NightCallScreen
import com.example.respeclive.ui.night.NightChatScreen
import com.example.respeclive.ui.diary.DiaryDayScreen
import com.example.respeclive.ui.diary.DiaryScreen
import com.example.respeclive.ui.onboarding.OnboardingScreen
import com.example.respeclive.ui.profile.ProfileScreen
import com.example.respeclive.ui.report.ReportScreen
import com.example.respeclive.ui.routine.RoutineScreen
import com.example.respeclive.ui.theme.RespecColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("routine", "루틴", Icons.Filled.Schedule),
    TabItem("report", "리포트", Icons.Filled.BarChart),
    TabItem("home", "홈", Icons.Filled.Home),
    TabItem("diary", "일기", Icons.AutoMirrored.Filled.MenuBook),
    TabItem("profile", "내 정보", Icons.Filled.Person),
)

private fun titleFor(route: String?) = when (route) {
    "home" -> "리스펙 알람"
    "routine" -> "루틴 목록"
    "report" -> "리포트"
    "diary" -> "한 줄 일기"
    "profile" -> "내 정보"
    else -> ""
}

/** 앱 시작: 서버에서 온보딩 여부를 확인(GET /users/me)해 온보딩 또는 메인 화면으로. 서버에 연결되지 않으면 메인으로 */
@Composable
fun AppNavHost(
    launch: AlarmLaunch? = null,
    onLaunchHandled: () -> Unit = {}
) {
    var needOnboarding by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        val profile = withTimeoutOrNull(8_000) { RespecApi.profile() }
        needOnboarding = profile != null && !profile.optBoolean("onboarding_done", true)
    }
    when (needOnboarding) {
        null -> Box(Modifier.fillMaxSize().background(RespecColors.Background), contentAlignment = Alignment.Center) {
            Text("Re:Spec", color = RespecColors.AccentText, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        }
        true -> OnboardingScreen(onDone = { needOnboarding = false })
        false -> MainNav(launch, onLaunchHandled)
    }
}

@Composable
private fun MainNav(
    launch: AlarmLaunch?,
    onLaunchHandled: () -> Unit
) {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val showBars = tabs.any { it.route == route }

    // 수신 화면에 표시할 시각 (알람으로 열렸을 때/디버그 진입 시 설정)
    var activeLabel by remember { mutableStateOf("") }
    var activeAlarmId by remember { mutableIntStateOf(-1) }

    // 알람으로 앱이 열리면 해당 화면(모닝콜/굿나잇콜)으로 이동
    LaunchedEffect(launch) {
        if (launch != null) {
            nav.currentBackStackEntryFlow.first() // 내비게이션 그래프가 준비될 때까지 대기
            activeLabel = launch.label
            activeAlarmId = launch.alarmId
            nav.navigate(launch.route) { launchSingleTop = true }
            onLaunchHandled()
        }
    }

    Scaffold(
        containerColor = RespecColors.Background,
        topBar = { if (showBars) RespecTopBar(titleFor(route)) },
        bottomBar = {
            if (showBars) RespecBottomBar(route) { target ->
                if (target == "home") {
                    if (!nav.popBackStack("home", inclusive = false)) {
                        nav.navigate("home") { launchSingleTop = true }
                    }
                } else {
                    nav.navigate(target) {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    ) { inner ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(inner)) {
            composable("home") {
                HomeScreen(
                    onStartNightChat = {
                        activeLabel = AlarmRepository.alarms
                            .firstOrNull { it.type == AlarmType.BEDTIME }?.timeLabel() ?: ""
                        nav.navigate("nightCall")
                    },
                    onCreateAlarm = { nav.navigate("alarmEdit/-1") },
                    onEditAlarm = { id -> nav.navigate("alarmEdit/$id") },
                    onOpenDebug = { nav.navigate("nextyDebug") }
                )
            }
            composable("nextyDebug") { NextyDebugScreen(onBack = { nav.popBackStack() }) }
            composable("routine") { RoutineScreen() }
            composable("report") { ReportScreen() }
            composable("diary") { DiaryScreen(onOpenDay = { date -> nav.navigate("diaryDay/$date") }) }
            composable("diaryDay/{date}") { entry ->
                DiaryDayScreen(date = entry.arguments?.getString("date").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("profile") { ProfileScreen() }

            composable(
                "alarmEdit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.IntType })
            ) { entry ->
                AlarmEditScreen(
                    alarmId = entry.arguments?.getInt("id") ?: -1,
                    onBack = { nav.popBackStack() }
                )
            }

            composable("morningCall") {
                MorningCallScreen(
                    onSnooze = { if (!nav.popBackStack("home", inclusive = false)) nav.navigate("home") },
                    alarmId = activeAlarmId,
                    alarmLabel = activeLabel.ifBlank { "오전 6:00" },
                    onFinish = {
                        nav.navigate("routine") {
                            popUpTo("home")
                            launchSingleTop = true
                        }
                    },
                    onCallEnded = { sessionId, talkSec ->
                        nav.navigate("morningReport/$sessionId/$talkSec") {
                            popUpTo("home")
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(
                "morningReport/{sessionId}/{talkSec}",
                arguments = listOf(navArgument("talkSec") { type = NavType.IntType })
            ) { entry ->
                MorningReportScreen(
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    talkSec = entry.arguments?.getInt("talkSec") ?: 0,
                    onOpenReport = {
                        nav.navigate("report") {
                            popUpTo("home")
                            launchSingleTop = true
                        }
                    },
                    onHome = { if (!nav.popBackStack("home", inclusive = false)) nav.navigate("home") }
                )
            }
            composable("nightCall") {
                NightCallScreen(
                    bedtimeLabel = activeLabel.ifBlank { "오후 10:00" },
                    onAccept = {
                        nav.navigate("nightChat") {
                            popUpTo("nightCall") { inclusive = true }
                        }
                    },
                    onDecline = {
                        if (!nav.popBackStack()) nav.navigate("home")
                    }
                )
            }
            composable("nightChat") {
                NightChatScreen(onClose = {
                    if (!nav.popBackStack()) nav.navigate("home")
                })
            }
        }
    }
}

@Composable
private fun RespecTopBar(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = RespecColors.AccentText, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(RespecColors.PurpleDark)
                .clickable { /* TODO: 설정 화면 */ },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "설정", tint = RespecColors.Purple)
        }
    }
}

@Composable
private fun RespecBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    NavigationBar(containerColor = RespecColors.Card) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onSelect(tab.route) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, fontSize = 12.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = RespecColors.AccentText,
                    selectedTextColor = RespecColors.AccentText,
                    unselectedIconColor = RespecColors.TextSub,
                    unselectedTextColor = RespecColors.TextSub,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}