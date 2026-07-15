package com.example.smartdoorbell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person

object DoorbellNotifier {
    const val CALL_CHANNEL_ID = "doorbell_calls_custom_v1"
    private const val LEGACY_CALL_CHANNEL_ID = "doorbell_calls"
    private const val CALL_NOTIFICATION_ID = 42010
    const val CALL_TIMEOUT_MS = 90_000L

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val doorbellSound = Uri.parse(
            "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.doorbell_chime}"
        )
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val call = NotificationChannel(
            CALL_CHANNEL_ID,
            context.getString(R.string.doorbell_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Som exclusivo e tela cheia quando alguém toca a campainha"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 220, 120, 420, 140, 220)
            setSound(doorbellSound, audio)
            setShowBadge(false)
        }
        manager.createNotificationChannel(call)
        manager.deleteNotificationChannel(LEGACY_CALL_CHANNEL_ID)
    }

    fun showIncomingCall(
        context: Context,
        title: String = context.getString(R.string.incoming_call_title),
        body: String = context.getString(R.string.incoming_call_body),
        openUrl: String = DoorbellConfig.DEFAULT_ATTEND_URL
    ) {
        ensureChannels(context)
        wakeBriefly(context)

        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(DoorbellConfig.EXTRA_TITLE, title)
            putExtra(DoorbellConfig.EXTRA_BODY, body)
            putExtra(DoorbellConfig.EXTRA_OPEN_URL, openUrl)
            putExtra(DoorbellConfig.EXTRA_PREVIEW_URL, DoorbellConfig.previewUrlFromAnswer(openUrl))
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            100,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answerIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DoorbellConfig.EXTRA_OPEN_URL, openUrl)
        }
        val declineIntent = Intent(context, DoorbellActionReceiver::class.java).apply {
            action = DoorbellConfig.ACTION_DECLINE
        }
        val answerPendingIntent = PendingIntent.getActivity(
            context,
            101,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            102,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val caller = Person.Builder()
            .setName(title)
            .setImportant(true)
            .build()

        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_doorbell)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(CALL_TIMEOUT_MS)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePendingIntent, answerPendingIntent))
            .build()

        NotificationManagerCompat.from(context).notify(CALL_NOTIFICATION_ID, notification)
        runCatching { context.startActivity(fullScreenIntent) }
    }

    fun cancelIncomingCall(context: Context) {
        NotificationManagerCompat.from(context).cancel(CALL_NOTIFICATION_ID)
    }


    private fun wakeBriefly(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Campainha:incomingCall"
            )
            wakeLock.acquire(10_000)
        } catch (_: Throwable) {
        }
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else true
    }

    fun fullScreenSettingsIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else null
    }
}
