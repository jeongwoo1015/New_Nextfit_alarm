package com.example.respeclive.ui.common

import com.example.respeclive.data.Alarm
import com.example.respeclive.data.RoutineCatalog
import com.example.respeclive.data.durationText

/**
 * 알람 내용 한 줄. 루틴: "{프리셋 이름} · {합계}", 콘텐츠: "{종류} · {세부} · {고정 시간}".
 * 프리셋 이름·합계는 RoutineCatalog에 받아 둔 값 (아직 없으면 그 부분만 뺌)
 */
fun alarmSummary(alarm: Alarm): String {
    val parts = if (alarm.isContent) {
        listOfNotNull(alarm.themeLabel, alarm.contentOptionLabel, durationText(RoutineCatalog.limits.contentSec))
    } else {
        val routine = alarm.serverId?.let { RoutineCatalog.routines[it] }
        listOfNotNull(
            alarm.templateId?.let { RoutineCatalog.presetNames[it] },
            routine?.let { durationText(it.totalSec) }
        )
    }
    return parts.joinToString(" · ")
}
