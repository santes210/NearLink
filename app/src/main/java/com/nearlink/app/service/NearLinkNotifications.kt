package com.nearlink.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.nearlink.app.MainActivity
import com.nearlink.app.R

/** Canales y notificaciones de NearLink. */
object NearLinkNotifications {

    const val SERVICE_CHANNEL = "nearlink_service"
    const val MESSAGES_CHANNEL = "nearlink_messages"
    const val SOS_CHANNEL = "nearlink_sos"
    const val SERVICE_NOTIFICATION_ID = 1984
    const val ACTION_STOP = "com.nearlink.app.action.STOP"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val service = NotificationChannel(
            SERVICE_CHANNEL,
            context.getString(R.string.notification_channel_service_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.notification_channel_service_description) }

        val messages = NotificationChannel(
            MESSAGES_CHANNEL,
            context.getString(R.string.notification_channel_messages_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.notification_channel_messages_description) }

        val sos = NotificationChannel(
            SOS_CHANNEL,
            context.getString(R.string.notification_channel_sos_name),
            NotificationManager.IMPORTANCE_MAX,
        ).apply { description = context.getString(R.string.notification_channel_sos_description) }

        manager.createNotificationChannels(listOf(service, messages, sos))
    }

    fun serviceNotification(
        context: Context,
        peers: Int,
        advertising: Boolean,
    ): Notification {
        val stopIntent = Intent(context, NearLinkForegroundService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = Intent(context, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            context,
            2,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = context.getString(R.string.notification_service_title)
        val text = if (advertising) {
            context.resources.getQuantityString(R.plurals.notification_service_text, peers, peers)
        } else {
            context.getString(R.string.notification_service_idle)
        }
        return NotificationCompat.Builder(context, SERVICE_CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_nearlink)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.notification_action_stop),
                stopPending,
            )
            .build()
    }

    fun showMessage(
        context: Context,
        peerId: String,
        peerName: String,
        preview: String,
        isSos: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PermissionChecker.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_PEER_ID, peerId)
        }
        val pending = PendingIntent.getActivity(
            context,
            peerId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(
            context,
            if (isSos) SOS_CHANNEL else MESSAGES_CHANNEL,
        )
            .setSmallIcon(R.drawable.ic_stat_nearlink)
            .setContentTitle(peerName)
            .setContentText(preview)
            .setPriority(if (isSos) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isSos) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(peerId.hashCode(), notification)
        }
    }
}
