package com.example.traincontrolstmobilandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ScheduleHelper.scheduleAlarm(context, 1)
            ScheduleHelper.scheduleAlarm(context, 2)
        }
    }
}
