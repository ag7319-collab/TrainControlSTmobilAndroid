package com.example.traincontrolstmobilandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timerIndex = intent.getIntExtra("timer_index", 1)
        val dayKey = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "mon"
            Calendar.TUESDAY -> "tue"
            Calendar.WEDNESDAY -> "wed"
            Calendar.THURSDAY -> "thu"
            Calendar.FRIDAY -> "fri"
            Calendar.SATURDAY -> "sat"
            Calendar.SUNDAY -> "sun"
            else -> ""
        }

        val prefs = context.getSharedPreferences("TrainControlSTmobilPrefs", Context.MODE_PRIVATE)
        val selectedDays = prefs.getStringSet("timer_${timerIndex}_days", emptySet()) ?: emptySet()

        if (dayKey in selectedDays) {
            val workRequest = OneTimeWorkRequestBuilder<TrainWorker>()
                .setInputData(Data.Builder().putInt("timer_index", timerIndex).build())
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
        
        // Re-schedule for next day
        ScheduleHelper.scheduleAlarm(context, timerIndex)
    }
}
