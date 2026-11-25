package com.example.respeclive.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.respeclive.data.AlarmRepository

/** 재부팅, 앱 업데이트, 시간 변경 후 알람 다시 등록 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmRepository.init(context)
        AlarmScheduler.rescheduleAll(context)
    }
}