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

        // 다음 요일 알람 재등록 (setAlarmClock은 한 번만 울리므로)
        AlarmRepository.init(context)
        AlarmRepository.get(id)?.let { if (it.enabled) AlarmScheduler.schedule(context, it) }

        AlarmNotifier.show(context, id, type, label)
    }
}