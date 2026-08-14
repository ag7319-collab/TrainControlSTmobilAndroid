package com.example.traincontrolstmobilandroid

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object ScheduleHelper {

    fun scheduleAlarm(context: Context, timerIndex: Int) {
        val prefs = context.getSharedPreferences("TrainControlSTmobilPrefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("timer_${timerIndex}_enabled", false)
        if (!isEnabled) {
            cancelAlarm(context, timerIndex)
            return
        }

        val hour = prefs.getInt("timer_${timerIndex}_hour", if (timerIndex == 1) 7 else 16)
        val minute = prefs.getInt("timer_${timerIndex}_minute", 0)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("timer_index", timerIndex)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            timerIndex,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (!alarmManager.canScheduleExactAlarms()) {
            // If we can't schedule exact alarms, we could fallback to setWindow or just return.
            // For this app, exact timing is important, so we might want to notify the user.
            return
        }

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent,
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun cancelAlarm(context: Context, timerIndex: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            timerIndex,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
    }
}
