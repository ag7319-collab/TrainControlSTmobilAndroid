package com.example.traincontrolstmobilandroid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

class NotificationHelper(private val context: Context) {

    private var alarmToneGenerator: ToneGenerator? = null

    init {
        try {
            alarmToneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendGarminNotification(
        message: String,
        title: String = "Zug-Anzeige",
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                android.util.Log.w("TrainControlSTmobil", "POST_NOTIFICATIONS nicht erlaubt")
                return
            }
        }

        val channelId = "train_delay_instant_v7"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            setBypassDnd(true)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message.replace("\n", " "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            .setSound(null)
            .setAutoCancel(true)

        notificationManager.notify(1001, builder.build())
    }

    fun playSingleBeep() {
        try {
            alarmToneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 350)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
