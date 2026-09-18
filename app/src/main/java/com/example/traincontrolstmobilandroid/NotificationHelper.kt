package com.example.traincontrolstmobilandroid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

class NotificationHelper(private val context: Context) {

    private var alarmToneGenerator: ToneGenerator? = null

    init {
        try {
            alarmToneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendGarminNotification(
        message: String,
        title: String = "Zug-Anzeige",
        isSilent: Boolean = false,
        notificationId: Int = 1001
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

        val channelId = if (isSilent) "train_info_silent_v1" else "train_delay_instant_v7"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (isSilent) {
            val silentChannel = NotificationChannel(
                channelId,
                "Status-Infos (leise)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Unaufdringliche Statusmeldungen"
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(silentChannel)
        } else {
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
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message.replace("\n", " "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(if (isSilent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            // Disable system-generated actions (like "Open Map")
            .setExtras(Bundle().apply {
                putBoolean("android.allowSystemGeneratedContextualActions", false)
            })

        // Add Content Intent to open the app
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(openPendingIntent)

        // Add Refresh Action
        val refreshIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            putExtra("timer_index", 1) // Default to 1 for manual refresh
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_rotate, "Aktualisieren", refreshPendingIntent)

        // Explicit "App öffnen" action
        builder.addAction(0, "App öffnen", openPendingIntent)

        if (!isSilent) {
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
        }

        notificationManager.notify(notificationId, builder.build())
    }

    fun playSingleBeep() {
        try {
            alarmToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 400)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelNotification(id: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(id)
    }
}
