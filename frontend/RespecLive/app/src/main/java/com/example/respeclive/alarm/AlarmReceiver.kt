package com.example.respeclive.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.respeclive.data.AlarmRepository
import com.example.respeclive.data.AlarmType

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AlarmExtras.ID, -1)
        val type = intent.getStringExtra(AlarmExtras.TYPE) ?: AlarmType.WAKE.name
        val label = intent.getStringExtra(AlarmExtras.LABEL) ?: ""

        AlarmRepository.init(context)
        val alarm = AlarmRepository.get(id)
        // 굿나잇콜을 +10분으로 미뤄서 다시 울린 것인지 보려고, 다시 예약해 기록이 지워지기 전에 읽어 둠
        val previousShift = if (type == AlarmType.BEDTIME.name) BedtimeReminder.peekShift(context, id) else null

        // 다음 요일 알람 재등록 (setAlarmClock은 한 번만 울리므로)
        alarm?.let { if (it.enabled) AlarmScheduler.schedule(context, it) }

        // 굿나잇콜은 전화 화면을 바로 열지 않고 팝업을 띄움 (누르면 수신 화면)
        if (type == AlarmType.BEDTIME.name) {
            alarm?.takeIf { it.type == AlarmType.BEDTIME && it.enabled }?.let { BedtimeReminder.ring(context, it, previousShift) }
            return
        }
        AlarmNotifier.show(context, id, type, label)
    }
}