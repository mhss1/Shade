package com.mhss.app.shade.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.mhss.app.shade.R
import com.mhss.app.shade.service.ScreenCaptureService.Companion.ACTION_CLEAR
import com.mhss.app.shade.service.ScreenCaptureService.Companion.ACTION_STOP

private const val CHANNEL_ID = "screen_capture_channel"

fun Context.createCaptureServiceNotification(): Notification {
    val channel = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.notification_channel_name),
        NotificationManager.IMPORTANCE_LOW
    )
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)

    val pendingIntentFlag = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    val clearIntent = Intent(this, ScreenCaptureService::class.java).apply {
        action = ACTION_CLEAR
    }
    val clearPendingIntent = PendingIntent.getService(this, 1, clearIntent, pendingIntentFlag)

    val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
        action = ACTION_STOP
    }
    val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, pendingIntentFlag)

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setSmallIcon(R.mipmap.ic_launcher_foreground)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .addAction(
            R.mipmap.ic_launcher_foreground,
            getString(R.string.notification_action_clear),
            clearPendingIntent
        )
        .addAction(
            R.mipmap.ic_launcher_foreground,
            getString(R.string.notification_action_stop),
            stopPendingIntent
        )
        .build()
}