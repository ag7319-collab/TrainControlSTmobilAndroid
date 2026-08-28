package com.example.traincontrolstmobilandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timerIndex = intent.getIntExtra("timer_index", 1)
        
        // Trigger a manual refresh via WorkManager
        val workRequest = OneTimeWorkRequestBuilder<TrainWorker>()
            .setInputData(Data.Builder().putInt("timer_index", timerIndex).build())
            .build()
        
        WorkManager.getInstance(context).enqueue(workRequest)
    }
}
